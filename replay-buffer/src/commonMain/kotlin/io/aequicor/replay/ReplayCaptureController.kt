package io.aequicor.replay

import io.aequicor.capture.core.AudioCaptureAdapter
import io.aequicor.capture.core.MediaClock
import io.aequicor.capture.core.RecordingException
import io.aequicor.capture.core.RecordingSession
import io.aequicor.capture.core.RecordingSessionIdFactory
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.RecordingSettingsValidator
import io.aequicor.capture.core.ValidationIssue
import io.aequicor.capture.core.VideoCaptureAdapter
import io.aequicor.capture.core.VideoFrame
import io.aequicor.capture.core.release
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ReplayCaptureController(
    private val videoCaptureAdapter: VideoCaptureAdapter,
    private val audioCaptureAdapter: AudioCaptureAdapter,
    private val mediaBuffer: ReplayMediaBuffer,
    private val scope: CoroutineScope,
    private val clock: MediaClock,
    private val sessionIdFactory: RecordingSessionIdFactory,
) {
    private val lifecycleMutex = Mutex()
    private val statsMutex = Mutex()
    private val saveMutex = Mutex()
    private val mutableState = MutableStateFlow<ReplayCaptureState>(ReplayCaptureState.Idle)
    private var activeSession: ActiveReplaySession? = null

    val state: StateFlow<ReplayCaptureState> = mutableState.asStateFlow()

    suspend fun start(settings: RecordingSettings): StartReplayResult = lifecycleMutex.withLock {
        activeSession?.let {
            return@withLock StartReplayResult.Busy(mutableState.value)
        }
        val validationIssues = buildList {
            addAll(RecordingSettingsValidator.validate(settings))
            if (settings.replayDuration == null) {
                add(ValidationIssue("replayDuration", "Replay duration is required."))
            }
        }
        if (validationIssues.isNotEmpty()) {
            return@withLock StartReplayResult.Rejected(validationIssues)
        }
        val duration = requireNotNull(settings.replayDuration)
        val session = RecordingSession(
            id = sessionIdFactory.nextId(),
            settings = settings,
            startedAtNanoseconds = clock.nowNanoseconds(),
        )
        val active = ActiveReplaySession(session = session)
        mutableState.value = ReplayCaptureState.Preparing(session, duration)
        try {
            mediaBuffer.open(session, duration)
        } catch (throwable: Throwable) {
            val failed = ReplayCaptureState.Failed(
                session = session,
                stats = active.stats,
                message = throwable.replayMessage("Failed to open replay buffer."),
            )
            mutableState.value = failed
            return@withLock StartReplayResult.Failed(failed)
        }

        activeSession = active
        val job = scope.launch { runSession(active, settings) }
        if (activeSession?.session?.id == session.id) {
            activeSession = active.copy(job = job)
        }
        StartReplayResult.Started(session)
    }

    suspend fun save(outputPath: String): SaveReplayResult = saveMutex.withLock {
        val active = lifecycleMutex.withLock {
            val current = activeSession ?: return@withLock null
            if (current.isSaving) {
                return@withLock null
            }
            val saving = current.copy(isSaving = true, saveOutputPath = outputPath)
            activeSession = saving
            mutableState.value = ReplayCaptureState.Saving(saving.session, saving.stats, outputPath)
            saving
        } ?: return@withLock SaveReplayResult.NotBuffering(mutableState.value)

        val result = runCatching { mediaBuffer.save(outputPath) }
        result.exceptionOrNull()?.let { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }
        }
        return@withLock lifecycleMutex.withLock {
            val current = activeSession?.takeIf { it.session.id == active.session.id }
            if (current == null) {
                val failed = mutableState.value as? ReplayCaptureState.Failed
                return@withLock failed?.let { SaveReplayResult.Failed(it.message, it) }
                    ?: SaveReplayResult.NotBuffering(mutableState.value)
            }
            result.fold(
                onSuccess = { saved ->
                    val buffering = current.copy(isSaving = false, saveOutputPath = null)
                    activeSession = buffering
                    mutableState.value = ReplayCaptureState.Buffering(buffering.session, buffering.stats)
                    SaveReplayResult.Saved(saved)
                },
                onFailure = { throwable ->
                    val buffering = current.copy(isSaving = false, saveOutputPath = null)
                    activeSession = buffering
                    mutableState.value = ReplayCaptureState.Buffering(buffering.session, buffering.stats)
                    SaveReplayResult.Failed(
                        message = throwable.replayMessage("Failed to save replay."),
                        state = mutableState.value,
                    )
                },
            )
        }
    }

    suspend fun stop(): StopReplayResult {
        val active = lifecycleMutex.withLock {
            val current = activeSession ?: return@withLock null
            mutableState.value = ReplayCaptureState.Stopping(current.session, current.stats)
            current
        } ?: return StopReplayResult.NotBuffering(mutableState.value)

        active.job?.cancelAndJoin()
        return lifecycleMutex.withLock {
            val current = activeSession?.takeIf { it.session.id == active.session.id } ?: active
            return@withLock try {
                mediaBuffer.close()
                activeSession = null
                mutableState.value = ReplayCaptureState.Idle
                StopReplayResult.Stopped(current.session, current.stats)
            } catch (throwable: Throwable) {
                val failed = ReplayCaptureState.Failed(
                    session = current.session,
                    stats = current.stats,
                    message = throwable.replayMessage("Failed to close replay buffer."),
                )
                activeSession = null
                mutableState.value = failed
                StopReplayResult.Failed(failed)
            }
        }
    }

    private suspend fun runSession(active: ActiveReplaySession, settings: RecordingSettings) {
        try {
            mutableState.value = ReplayCaptureState.Buffering(active.session, active.stats)
            coroutineScope {
                launch {
                    runVideoPipeline(active, settings)
                }
                if (settings.audioSources.isNotEmpty()) {
                    launch {
                        audioCaptureAdapter.frames(settings).collect { frame ->
                            updateStats(active, mediaBuffer.writeAudioFrame(frame))
                        }
                    }
                }
            }
            throw ReplayBufferException("Capture stream ended while replay buffering was active.")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            fail(active, throwable)
        }
    }

    private suspend fun runVideoPipeline(active: ActiveReplaySession, settings: RecordingSettings): Unit = coroutineScope {
        val frames = Channel<VideoFrame>(
            capacity = REPLAY_CAPTURE_FRAME_BUFFER_SIZE,
            onUndeliveredElement = { frame -> frame.release() },
        )
        launch {
            try {
                videoCaptureAdapter.frames(settings).collect(frames::send)
            } finally {
                frames.close()
            }
        }
        try {
            for (frame in frames) {
                try {
                    updateStats(active, mediaBuffer.writeVideoFrame(frame))
                } finally {
                    frame.release()
                }
            }
            throw ReplayBufferException("Capture stream ended while replay buffering was active.")
        } finally {
            frames.cancel()
        }
    }

    private suspend fun updateStats(active: ActiveReplaySession, stats: ReplayBufferStats) {
        val current = statsMutex.withLock {
            val latest = activeSession?.takeIf { it.session.id == active.session.id } ?: return
            latest.copy(stats = stats).also { activeSession = it }
        }
        mutableState.value = if (current.isSaving) {
            ReplayCaptureState.Saving(
                session = current.session,
                stats = current.stats,
                outputPath = requireNotNull(current.saveOutputPath),
            )
        } else {
            ReplayCaptureState.Buffering(current.session, current.stats)
        }
    }

    private suspend fun fail(active: ActiveReplaySession, throwable: Throwable) {
        val current = statsMutex.withLock {
            activeSession?.takeIf { it.session.id == active.session.id } ?: active
        }
        runCatching { mediaBuffer.close() }
        val failed = ReplayCaptureState.Failed(
            session = current.session,
            stats = current.stats,
            message = throwable.replayMessage("Replay capture failed."),
        )
        lifecycleMutex.withLock {
            activeSession = null
            mutableState.value = failed
        }
    }

    private data class ActiveReplaySession(
        val session: RecordingSession,
        val stats: ReplayBufferStats = EMPTY_REPLAY_STATS,
        val job: Job? = null,
        val isSaving: Boolean = false,
        val saveOutputPath: String? = null,
    )
}

sealed interface ReplayCaptureState {
    data object Idle : ReplayCaptureState
    data class Preparing(
        val session: RecordingSession,
        val duration: kotlin.time.Duration,
    ) : ReplayCaptureState
    data class Buffering(
        val session: RecordingSession,
        val stats: ReplayBufferStats,
    ) : ReplayCaptureState
    data class Saving(
        val session: RecordingSession,
        val stats: ReplayBufferStats,
        val outputPath: String,
    ) : ReplayCaptureState
    data class Stopping(
        val session: RecordingSession,
        val stats: ReplayBufferStats,
    ) : ReplayCaptureState
    data class Failed(
        val session: RecordingSession?,
        val stats: ReplayBufferStats,
        val message: String,
    ) : ReplayCaptureState
}

sealed interface StartReplayResult {
    data class Started(val session: RecordingSession) : StartReplayResult
    data class Busy(val state: ReplayCaptureState) : StartReplayResult
    data class Rejected(val issues: List<ValidationIssue>) : StartReplayResult
    data class Failed(val state: ReplayCaptureState.Failed) : StartReplayResult
}

sealed interface SaveReplayResult {
    data class Saved(val result: ReplaySaveResult) : SaveReplayResult
    data class NotBuffering(val state: ReplayCaptureState) : SaveReplayResult
    data class Failed(val message: String, val state: ReplayCaptureState) : SaveReplayResult
}

sealed interface StopReplayResult {
    data class Stopped(
        val session: RecordingSession,
        val stats: ReplayBufferStats,
    ) : StopReplayResult
    data class NotBuffering(val state: ReplayCaptureState) : StopReplayResult
    data class Failed(val state: ReplayCaptureState.Failed) : StopReplayResult
}

private val EMPTY_REPLAY_STATS = ReplayBufferStats(
    videoFrameCount = 0,
    audioFrameCount = 0,
    retainedDuration = kotlin.time.Duration.ZERO,
    storagePolicy = ReplayStoragePolicy.DiskSegments,
)

private const val REPLAY_CAPTURE_FRAME_BUFFER_SIZE = 3

private fun Throwable.replayMessage(fallback: String): String =
    when (this) {
        is RecordingException -> error.message
        else -> message ?: fallback
    }
