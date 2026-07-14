package io.aequicor.capture.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.nanoseconds

class RecordingController(
    private val videoCaptureAdapter: VideoCaptureAdapter,
    private val audioCaptureAdapter: AudioCaptureAdapter,
    private val mediaEncoder: MediaEncoder,
    private val scope: CoroutineScope,
    private val clock: MediaClock,
    private val sessionIdFactory: RecordingSessionIdFactory,
) {
    private val lifecycleMutex = Mutex()
    private val frameGateMutex = Mutex()
    private val metricsMutex = Mutex()
    private val state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    private var activeSession: ActiveSession? = null

    val recordingState: StateFlow<RecordingState> = state.asStateFlow()

    suspend fun start(settings: RecordingSettings): StartRecordingResult = lifecycleMutex.withLock {
        activeSession?.let {
            return@withLock StartRecordingResult.Busy(state.value)
        }

        val validationIssues = RecordingSettingsValidator.validate(settings)
        if (validationIssues.isNotEmpty()) {
            return@withLock StartRecordingResult.Rejected(validationIssues)
        }

        val session = RecordingSession(
            id = sessionIdFactory.nextId(),
            settings = settings,
            startedAtNanoseconds = clock.nowNanoseconds(),
        )
        val active = ActiveSession(session = session)
        state.value = RecordingState.Preparing(session)

        try {
            mediaEncoder.open(session, settings)
        } catch (throwable: Throwable) {
            val error = throwable.toRecordingError(default = ::encoderFailed)
            val failed = RecordingState.Failed(session, active.metrics, error)
            state.value = failed
            return@withLock StartRecordingResult.Failed(failed)
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            runSession(active, settings)
        }
        activeSession = active.copy(job = job)
        job.start()
        StartRecordingResult.Started(session)
    }

    suspend fun stop(): StopRecordingResult {
        val active = lifecycleMutex.withLock {
            val current = activeSession ?: return@withLock null
            if (state.value is RecordingState.Stopping) {
                return@withLock null
            }
            frameGateMutex.withLock {
                val latest = updateDuration(current, clock.nowNanoseconds())
                state.value = RecordingState.Stopping(latest.session, latest.metrics)
                latest
            }
        } ?: return StopRecordingResult.NotRecording(state.value)

        active.job?.cancelAndJoin()
        return lifecycleMutex.withLock {
            val latest = activeSession ?: return@withLock StopRecordingResult.NotRecording(state.value)
            val output = try {
                mediaEncoder.finish(latest.session, latest.metrics)
            } catch (throwable: Throwable) {
                val failed = RecordingState.Failed(
                    latest.session,
                    latest.metrics,
                    throwable.toRecordingError(default = ::encoderFailed),
                )
                state.value = failed
                activeSession = null
                return@withLock StopRecordingResult.NotRecording(failed)
            }
            val completed = RecordingState.Completed(latest.session, latest.metrics, output.path)
            state.value = completed
            activeSession = null
            StopRecordingResult.Stopped(completed)
        }
    }

    suspend fun pause(): PauseRecordingResult = lifecycleMutex.withLock {
        when (val currentState = state.value) {
            is RecordingState.Paused -> return@withLock PauseRecordingResult.AlreadyPaused(currentState)
            !is RecordingState.Recording -> return@withLock PauseRecordingResult.NotRecording(currentState)
            else -> Unit
        }
        val current = activeSession ?: return@withLock PauseRecordingResult.NotRecording(state.value)
        frameGateMutex.withLock {
            val now = clock.nowNanoseconds()
            val paused = metricsMutex.withLock {
                val latest = activeSession?.takeIf { it.session.id == current.session.id } ?: current
                latest.pause(now).also { activeSession = it }
            }
            val pausedState = RecordingState.Paused(paused.session, paused.metrics)
            state.value = pausedState
            PauseRecordingResult.Paused(pausedState)
        }
    }

    suspend fun resume(): ResumeRecordingResult = lifecycleMutex.withLock {
        val currentState = state.value
        if (currentState !is RecordingState.Paused) {
            return@withLock ResumeRecordingResult.NotPaused(currentState)
        }
        val current = activeSession ?: return@withLock ResumeRecordingResult.NotPaused(currentState)
        frameGateMutex.withLock {
            val resumed = metricsMutex.withLock {
                val latest = activeSession?.takeIf { it.session.id == current.session.id } ?: current
                latest.resume(clock.nowNanoseconds()).also { activeSession = it }
            }
            val recordingState = RecordingState.Recording(resumed.session, resumed.metrics)
            state.value = recordingState
            ResumeRecordingResult.Resumed(recordingState)
        }
    }

    /** Marks the next encoded frame as important while recording is active. */
    suspend fun markImportantFrame(): MarkImportantFrameResult = frameGateMutex.withLock {
        if (state.value !is RecordingState.Recording) {
            return@withLock MarkImportantFrameResult.NotRecording(state.value)
        }
        val marked = metricsMutex.withLock metricsLock@{
            val current = activeSession ?: return@metricsLock false
            current.copy(importantFramePending = true).also { activeSession = it }
            true
        }
        if (marked) MarkImportantFrameResult.Marked else MarkImportantFrameResult.NotRecording(state.value)
    }

    suspend fun cancel(): CancelRecordingResult {
        val active = lifecycleMutex.withLock {
            val current = activeSession ?: return@withLock null
            if (state.value is RecordingState.Stopping) {
                return@withLock null
            }
            frameGateMutex.withLock {
                val latest = updateDuration(current, clock.nowNanoseconds())
                state.value = RecordingState.Stopping(latest.session, latest.metrics)
                latest
            }
        } ?: return CancelRecordingResult.NotRecording(state.value)

        active.job?.cancelAndJoin()
        return lifecycleMutex.withLock {
            val latest = activeSession ?: return@withLock CancelRecordingResult.NotRecording(state.value)
            mediaEncoder.cancel(latest.session)
            val cancelled = RecordingState.Cancelled(latest.session, latest.metrics)
            state.value = cancelled
            activeSession = null
            CancelRecordingResult.Cancelled(cancelled)
        }
    }

    private suspend fun runSession(active: ActiveSession, settings: RecordingSettings) {
        try {
            val canRun = lifecycleMutex.withLock {
                if (activeSession?.session?.id == active.session.id && state.value is RecordingState.Preparing) {
                    state.value = RecordingState.Recording(active.session, active.metrics)
                    true
                } else {
                    false
                }
            }
            if (!canRun) {
                return
            }
            coroutineScope {
                launch {
                    runVideoPipeline(active, settings)
                }
                if (settings.audioSources.isNotEmpty()) {
                    launch {
                        audioCaptureAdapter.frames(settings).collect { frame ->
                            processAudioFrame(active, frame)
                        }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            fail(active, throwable)
        }
    }

    private suspend fun runVideoPipeline(active: ActiveSession, settings: RecordingSettings): Unit = coroutineScope {
        val frames = Channel<VideoFrame>(
            capacity = CAPTURE_FRAME_BUFFER_SIZE,
            onUndeliveredElement = { frame -> frame.release() },
        )
        launch {
            try {
                val sourceFrames = if (
                    mediaEncoder is NativeVideoFrameMediaEncoder && mediaEncoder.supportsNativeVideoFrames()
                ) {
                    videoCaptureAdapter.nativeFrames(settings)
                } else {
                    videoCaptureAdapter.frames(settings)
                }
                sourceFrames.collect(frames::send)
            } finally {
                frames.close()
            }
        }
        try {
            for (frame in frames) {
                try {
                    processVideoFrame(active, frame)
                    yield()
                } finally {
                    frame.release()
                }
            }
            throw RecordingException(RecordingError.SourceUnavailable("Capture stream ended before stop."))
        } finally {
            frames.cancel()
        }
    }

    private suspend fun processVideoFrame(active: ActiveSession, frame: VideoFrame) {
        frameGateMutex.withLock {
            val current = currentSession(active) ?: return@withLock
            if (current.isPaused || state.value !is RecordingState.Recording) {
                return@withLock
            }
            val shouldMarkImportantFrame = current.importantFramePending || frame.importantFrame
            val adjustedFrame = frame.withPauseOffset(current.accumulatedPausedNanoseconds).let { adjusted ->
                if (shouldMarkImportantFrame) {
                    adjusted.withInputEventFrameMarker()
                } else {
                    adjusted
                }
            }
            mediaEncoder.writeVideoFrame(adjustedFrame)
            updateVideoMetrics(
                active = active,
                timestamp = adjustedFrame.timestamp,
                importantFrameConsumed = current.importantFramePending,
            )
        }
    }

    private suspend fun processAudioFrame(active: ActiveSession, frame: AudioFrame) {
        frameGateMutex.withLock {
            val current = currentSession(active) ?: return@withLock
            if (current.isPaused || state.value !is RecordingState.Recording) {
                return@withLock
            }
            mediaEncoder.writeAudioFrame(frame.withPauseOffset(current.accumulatedPausedNanoseconds))
            updateMetrics(active, publishState = false) { copy(audioFrames = audioFrames + 1) }
        }
    }

    private suspend fun currentSession(active: ActiveSession): ActiveSession? = metricsMutex.withLock {
        activeSession?.takeIf { it.session.id == active.session.id }
    }

    private suspend fun updateMetrics(
        active: ActiveSession,
        publishState: Boolean = true,
        transform: RecordingMetrics.() -> RecordingMetrics,
    ) {
        val updatedSession = metricsMutex.withLock {
            val current = activeSession?.takeIf { it.session.id == active.session.id } ?: return@withLock null
            val updated = current.metrics
                .copy(duration = current.recordedDurationAt(clock.nowNanoseconds()).nanoseconds)
                .transform()
            current.copy(metrics = updated).also { activeSession = it }
        } ?: return
        if (publishState) {
            when (state.value) {
                is RecordingState.Recording ->
                    state.value = RecordingState.Recording(updatedSession.session, updatedSession.metrics)
                is RecordingState.Paused ->
                    state.value = RecordingState.Paused(updatedSession.session, updatedSession.metrics)
                else -> Unit
            }
        }
    }

    private suspend fun updateVideoMetrics(
        active: ActiveSession,
        timestamp: MediaTimestamp,
        importantFrameConsumed: Boolean,
    ) {
        val updatedSession = metricsMutex.withLock {
            val current = activeSession?.takeIf { it.session.id == active.session.id } ?: return@withLock null
            val nowNanoseconds = clock.nowNanoseconds()
            val recording = current.withRecordingStartedAt(nowNanoseconds, timestamp.nanoseconds)
            val droppedFrames = current.estimatedDroppedFrames(timestamp.nanoseconds)
            recording.copy(
                metrics = recording.metrics.copy(
                    duration = recording.recordedDurationAt(nowNanoseconds).nanoseconds,
                    videoFrames = recording.metrics.videoFrames + 1,
                    droppedFrames = recording.metrics.droppedFrames + droppedFrames,
                ),
                lastVideoTimestampNanoseconds = timestamp.nanoseconds,
                importantFramePending = current.importantFramePending && !importantFrameConsumed,
            ).also { activeSession = it }
        } ?: return
        if (state.value is RecordingState.Recording) {
            state.value = RecordingState.Recording(updatedSession.session, updatedSession.metrics)
        }
    }

    private suspend fun updateDuration(active: ActiveSession, nowNanoseconds: Long): ActiveSession =
        metricsMutex.withLock {
            val latest = activeSession?.takeIf { it.session.id == active.session.id } ?: active
            latest.copy(
                metrics = latest.metrics.copy(duration = latest.recordedDurationAt(nowNanoseconds).nanoseconds),
            ).also { activeSession = it }
        }

    private suspend fun fail(active: ActiveSession, throwable: Throwable) {
        val latest = metricsMutex.withLock {
            activeSession?.takeIf { it.session.id == active.session.id } ?: active
        }
        runCatching { mediaEncoder.cancel(latest.session) }
        val failed = RecordingState.Failed(
            latest.session,
            latest.metrics,
            throwable.toRecordingError(default = ::sourceUnavailable),
        )
        lifecycleMutex.withLock {
            state.value = failed
            activeSession = null
        }
    }

    private data class ActiveSession(
        val session: RecordingSession,
        val metrics: RecordingMetrics = RecordingMetrics(),
        val job: Job? = null,
        val pausedAtNanoseconds: Long? = null,
        val accumulatedPausedNanoseconds: Long = 0,
        val recordingStartedAtNanoseconds: Long? = null,
        val lastVideoTimestampNanoseconds: Long? = null,
        val importantFramePending: Boolean = false,
    ) {
        val isPaused: Boolean
            get() = pausedAtNanoseconds != null

        fun pause(nowNanoseconds: Long): ActiveSession = copy(
            metrics = metrics.copy(duration = recordedDurationAt(nowNanoseconds).nanoseconds),
            pausedAtNanoseconds = nowNanoseconds,
        )

        fun resume(nowNanoseconds: Long): ActiveSession {
            val pausedAt = pausedAtNanoseconds ?: return this
            val pauseDuration = (nowNanoseconds - pausedAt).coerceAtLeast(0)
            return copy(
                pausedAtNanoseconds = null,
                accumulatedPausedNanoseconds = accumulatedPausedNanoseconds + pauseDuration,
            )
        }

        fun withRecordingStartedAt(
            nowNanoseconds: Long,
            firstFrameTimestampNanoseconds: Long,
        ): ActiveSession = if (recordingStartedAtNanoseconds == null) {
            copy(
                recordingStartedAtNanoseconds =
                    (nowNanoseconds - firstFrameTimestampNanoseconds).coerceAtLeast(session.startedAtNanoseconds),
            )
        } else {
            this
        }

        fun recordedDurationAt(nowNanoseconds: Long): Long {
            val recordingStartedAt = recordingStartedAtNanoseconds ?: return 0
            val activePauseDuration = pausedAtNanoseconds
                ?.let { pausedAt -> (nowNanoseconds - pausedAt).coerceAtLeast(0) }
                ?: 0
            return (
                nowNanoseconds - recordingStartedAt -
                    accumulatedPausedNanoseconds - activePauseDuration
                ).coerceAtLeast(0)
        }

        fun estimatedDroppedFrames(timestampNanoseconds: Long): Long {
            return estimateDroppedVideoFrames(
                previousTimestampNanoseconds = lastVideoTimestampNanoseconds,
                timestampNanoseconds = timestampNanoseconds,
                frameRate = session.settings.frameRate,
            )
        }
    }
}

private const val CAPTURE_FRAME_BUFFER_SIZE = 3

private fun VideoFrame.withPauseOffset(offsetNanoseconds: Long): VideoFrame =
    copy(timestamp = timestamp.minusOffset(offsetNanoseconds))

private fun AudioFrame.withPauseOffset(offsetNanoseconds: Long): AudioFrame =
    copy(timestamp = timestamp.minusOffset(offsetNanoseconds))

private fun MediaTimestamp.minusOffset(offsetNanoseconds: Long): MediaTimestamp =
    MediaTimestamp((nanoseconds - offsetNanoseconds).coerceAtLeast(0))

private fun Throwable.toRecordingError(default: (String) -> RecordingError): RecordingError =
    when (this) {
        is RecordingException -> error
        else -> default(message ?: this::class.simpleName ?: "Recording failed.")
    }

private fun encoderFailed(message: String): RecordingError = RecordingError.EncoderFailed(message)

private fun sourceUnavailable(message: String): RecordingError = RecordingError.SourceUnavailable(message)

class RecordingException(val error: RecordingError) : RuntimeException(error.message)
