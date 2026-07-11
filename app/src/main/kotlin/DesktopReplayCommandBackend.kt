package io.aequicor.app

import io.aequicor.capture.core.AudioCaptureAdapter
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.VideoCaptureAdapter
import io.aequicor.capture.platform.AudioSourceRepository
import io.aequicor.capture.platform.CaptureSourceRepository
import io.aequicor.capture.platform.GrantedPermissionGateway
import io.aequicor.capture.platform.PermissionAuthorization
import io.aequicor.capture.platform.PermissionGateway
import io.aequicor.capture.platform.authorize
import io.aequicor.capture.platform.message
import io.aequicor.cli.CliCommand
import io.aequicor.cli.RecordingControlAction
import io.aequicor.cli.RecordingControlCommandResult
import io.aequicor.cli.RecordingControlRequest
import io.aequicor.cli.RecordingControlStatus
import io.aequicor.cli.ReplayCommandBackend
import io.aequicor.cli.ReplayCommandResult
import io.aequicor.replay.ReplayCaptureController
import io.aequicor.replay.ReplayCaptureState
import io.aequicor.replay.ReplayMediaBuffer
import io.aequicor.replay.SaveReplayResult
import io.aequicor.replay.StartReplayResult
import io.aequicor.replay.StopReplayResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.extension
import kotlin.time.Duration

class DesktopReplayCommandBackend(
    captureSourceRepository: CaptureSourceRepository,
    private val videoCaptureAdapter: VideoCaptureAdapter,
    audioSourceRepository: AudioSourceRepository,
    private val audioCaptureAdapter: AudioCaptureAdapter,
    private val mediaBufferFactory: () -> ReplayMediaBuffer,
    private val permissionGateway: PermissionGateway = GrantedPermissionGateway,
) : ReplayCommandBackend {
    private val sourceResolver = DesktopCaptureSelectionResolver(
        captureSourceRepository = captureSourceRepository,
        audioSourceRepository = audioSourceRepository,
    )
    private val activeReplay = AtomicReference<ActiveCliReplay?>()

    override suspend fun run(command: CliCommand.ReplayRun): ReplayCommandResult = coroutineScope {
        val bufferDuration = command.options.bufferDuration.parseCliDuration()
            ?.takeIf { it.isPositive() }
            ?: return@coroutineScope ReplayCommandResult.Rejected(
                "Invalid --buffer duration: ${command.options.bufferDuration}.",
            )
        val runDuration = command.options.runDuration?.let { rawDuration ->
            rawDuration.parseCliDuration()
                ?.takeIf { it.isPositive() }
                ?: return@coroutineScope ReplayCommandResult.Rejected(
                    "Invalid --run-for duration: $rawDuration.",
                )
        }
        val output = Path.of(command.options.outputPath).toAbsolutePath().normalize()
        if (!output.extension.equals("mp4", ignoreCase = true)) {
            return@coroutineScope ReplayCommandResult.Rejected("Replay output must use the .mp4 extension.")
        }
        validateReplayControlEndpoint(command.options.controlEndpointPath, output)?.let { issue ->
            return@coroutineScope ReplayCommandResult.Rejected(issue)
        }
        val source = sourceResolver.resolveSource(command.target)
            ?: return@coroutineScope ReplayCommandResult.Unsupported("Requested replay source is not available.")
        val audioSources = try {
            when (
                val resolution = sourceResolver.resolveAudioSources(
                    microphoneSelector = command.options.microphone,
                    includeSystemAudio = command.options.systemAudio,
                    systemAudioSelector = command.options.systemAudioEndpoint,
                    microphoneGain = command.options.microphoneGainPercent?.toAudioGain(),
                    systemAudioGain = command.options.systemAudioGainPercent?.toAudioGain(),
                )
            ) {
                is DesktopAudioSourceResolution.Resolved -> resolution.sources
                is DesktopAudioSourceResolution.Rejected -> {
                    return@coroutineScope ReplayCommandResult.Rejected(resolution.message)
                }
            }
        } catch (throwable: Throwable) {
            return@coroutineScope ReplayCommandResult.Failed(
                throwable.message ?: throwable::class.simpleName ?: "Failed to discover audio sources.",
            )
        }
        val settings = RecordingSettings(
            captureSource = source,
            audioSources = audioSources,
            outputPath = output.toString(),
            frameRate = command.options.fps ?: DEFAULT_REPLAY_FRAME_RATE,
            captureCursor = command.options.captureCursor ?: true,
            replayDuration = bufferDuration,
        )
        val permissionAuthorization = try {
            permissionGateway.authorize(settings)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return@coroutineScope ReplayCommandResult.Failed(
                "Could not check capture permissions: ${failure.message ?: failure::class.simpleName}.",
            )
        }
        if (permissionAuthorization is PermissionAuthorization.Rejected) {
            return@coroutineScope ReplayCommandResult.Rejected(permissionAuthorization.message())
        }
        val controller = ReplayCaptureController(
            videoCaptureAdapter = videoCaptureAdapter,
            audioCaptureAdapter = audioCaptureAdapter,
            mediaBuffer = mediaBufferFactory(),
            scope = this,
            clock = SystemMediaClock,
            sessionIdFactory = MonotonicSessionIdFactory,
        )
        val replay = ActiveCliReplay(controller = controller, outputPath = output.toString())
        if (!activeReplay.compareAndSet(null, replay)) {
            return@coroutineScope ReplayCommandResult.Failed("Replay buffer is already active.")
        }

        return@coroutineScope runActiveReplay(
            replay = replay,
            settings = settings,
            runDuration = runDuration,
            controlEndpointPath = command.options.controlEndpointPath,
        )
    }

    suspend fun requestSaveAndStop(): ReplayCommandResult? {
        val replay = activeReplay.get() ?: return null
        replay.stopRequested.complete(Unit)
        return replay.completion.await()
    }

    private suspend fun runActiveReplay(
        replay: ActiveCliReplay,
        settings: RecordingSettings,
        runDuration: Duration?,
        controlEndpointPath: String?,
    ): ReplayCommandResult {
        val controlServer = try {
            controlEndpointPath?.let { path ->
                LocalRecordingControlServer.open(path, replay::control)
            }
        } catch (failure: Throwable) {
            val result = ReplayCommandResult.Rejected(
                failure.message ?: "Could not create local replay control endpoint.",
            )
            replay.completion.complete(result)
            activeReplay.compareAndSet(replay, null)
            return result
        }
        try {
            val result = try {
                when (val start = replay.controller.start(settings)) {
                    is StartReplayResult.Started -> {
                        replay.awaitStop(runDuration)
                        replay.controller.saveAndStop(replay.outputPath)
                    }
                    is StartReplayResult.Busy -> ReplayCommandResult.Failed(
                        "Replay buffer is already active.",
                    )
                    is StartReplayResult.Failed -> ReplayCommandResult.Failed(start.state.message)
                    is StartReplayResult.Rejected -> ReplayCommandResult.Rejected(
                        start.issues.joinToString("; ") { it.message },
                    )
                }
            } catch (cancellation: CancellationException) {
                replay.controller.stopQuietly()
                replay.completion.cancel(cancellation)
                throw cancellation
            } catch (throwable: Throwable) {
                replay.controller.stopQuietly()
                ReplayCommandResult.Failed(
                    throwable.message ?: throwable::class.simpleName ?: "Replay capture failed.",
                )
            }
            replay.completion.complete(result)
            return result
        } finally {
            controlServer?.close()
            activeReplay.compareAndSet(replay, null)
        }
    }
}

private data class ActiveCliReplay(
    val controller: ReplayCaptureController,
    val outputPath: String,
    val stopRequested: CompletableDeferred<Unit> = CompletableDeferred(),
    val completion: CompletableDeferred<ReplayCommandResult> = CompletableDeferred(),
) {
    suspend fun awaitStop(runDuration: Duration?) {
        if (runDuration == null) {
            stopRequested.await()
        } else {
            withTimeoutOrNull(runDuration.inWholeMilliseconds.coerceAtLeast(1)) {
                stopRequested.await()
            }
        }
    }

    suspend fun control(request: RecordingControlRequest): RecordingControlCommandResult =
        when (val action = request.action) {
            RecordingControlAction.Status -> completedControl(action)
            RecordingControlAction.Pause,
            RecordingControlAction.Resume,
            -> RecordingControlCommandResult.Rejected(
                "Replay buffering cannot be paused or resumed.",
            )
            RecordingControlAction.Save -> saveSnapshot(request.outputPath)
            RecordingControlAction.Stop -> {
                stopRequested.complete(Unit)
                completedControl(action, "Replay save and stop requested.")
            }
        }

    private suspend fun saveSnapshot(rawOutputPath: String?): RecordingControlCommandResult {
        val output = rawOutputPath?.let { path ->
            runCatching { Path.of(path).toAbsolutePath().normalize() }.getOrNull()
        } ?: return RecordingControlCommandResult.Rejected("Replay control save requires a valid output path.")
        if (!output.extension.equals("mp4", ignoreCase = true)) {
            return RecordingControlCommandResult.Rejected("Replay snapshot output must use the .mp4 extension.")
        }
        if (output == Path.of(outputPath).toAbsolutePath().normalize()) {
            return RecordingControlCommandResult.Rejected(
                "Replay snapshot output must differ from the final replay output.",
            )
        }
        return when (val result = controller.save(output.toString())) {
            is SaveReplayResult.Saved -> RecordingControlCommandResult.Completed(
                action = RecordingControlAction.Save,
                status = RecordingControlStatus(
                    state = controller.state.value.controlName(),
                    outputPath = result.result.output.path,
                    durationMilliseconds = result.result.duration.inWholeMilliseconds,
                    videoFrames = result.result.videoFrames,
                    audioFrames = result.result.audioFrames,
                ),
                message = "Replay snapshot saved.",
            )
            is SaveReplayResult.Failed -> RecordingControlCommandResult.Failed(result.message)
            is SaveReplayResult.NotBuffering -> RecordingControlCommandResult.Rejected(
                "Replay buffer is not active.",
            )
        }
    }

    private fun completedControl(
        action: RecordingControlAction,
        message: String? = null,
    ): RecordingControlCommandResult.Completed = RecordingControlCommandResult.Completed(
        action = action,
        status = controller.state.value.toControlStatus(outputPath),
        message = message,
    )
}

private fun ReplayCaptureState.toControlStatus(fallbackOutputPath: String): RecordingControlStatus {
    val stats = when (this) {
        ReplayCaptureState.Idle, is ReplayCaptureState.Preparing -> null
        is ReplayCaptureState.Buffering -> stats
        is ReplayCaptureState.Saving -> stats
        is ReplayCaptureState.Stopping -> stats
        is ReplayCaptureState.Failed -> stats
    }
    val activeOutputPath = when (this) {
        is ReplayCaptureState.Saving -> outputPath
        else -> fallbackOutputPath
    }
    return RecordingControlStatus(
        state = controlName(),
        outputPath = activeOutputPath,
        durationMilliseconds = stats?.retainedDuration?.inWholeMilliseconds ?: 0,
        videoFrames = stats?.videoFrameCount?.toLong() ?: 0,
        audioFrames = stats?.audioFrameCount?.toLong() ?: 0,
    )
}

private fun ReplayCaptureState.controlName(): String = when (this) {
    ReplayCaptureState.Idle -> "idle"
    is ReplayCaptureState.Preparing -> "replay-preparing"
    is ReplayCaptureState.Buffering -> "replay-buffering"
    is ReplayCaptureState.Saving -> "replay-saving"
    is ReplayCaptureState.Stopping -> "replay-stopping"
    is ReplayCaptureState.Failed -> "replay-failed"
}

private fun validateReplayControlEndpoint(controlEndpointPath: String?, outputPath: Path): String? {
    if (controlEndpointPath == null) {
        return null
    }
    val endpoint = runCatching { Path.of(controlEndpointPath).toAbsolutePath().normalize() }.getOrElse {
        return "Invalid replay control endpoint path: ${it.message}"
    }
    return if (endpoint == outputPath) {
        "Replay control endpoint must differ from the replay output path."
    } else {
        null
    }
}

private suspend fun ReplayCaptureController.saveAndStop(outputPath: String): ReplayCommandResult {
    val save = save(outputPath)
    val stop = stop()
    if (stop is StopReplayResult.Failed) {
        return ReplayCommandResult.Failed(stop.state.message)
    }
    return when (save) {
        is SaveReplayResult.Saved -> ReplayCommandResult.Completed(
            outputPath = save.result.output.path,
            videoFrames = save.result.videoFrames,
            audioFrames = save.result.audioFrames,
            durationMilliseconds = save.result.duration.inWholeMilliseconds,
        )
        is SaveReplayResult.Failed -> ReplayCommandResult.Failed(save.message)
        is SaveReplayResult.NotBuffering -> ReplayCommandResult.Failed(
            "Replay buffer stopped before it could be saved.",
        )
    }
}

private suspend fun ReplayCaptureController.stopQuietly() {
    withContext(NonCancellable) {
        runCatching { stop() }
    }
}

private const val DEFAULT_REPLAY_FRAME_RATE = 30
