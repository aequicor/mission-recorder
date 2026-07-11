package io.aequicor.app

import io.aequicor.capture.core.AudioCaptureAdapter
import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.MediaClock
import io.aequicor.capture.core.MediaEncoder
import io.aequicor.capture.core.PauseRecordingResult
import io.aequicor.capture.core.RecordingController
import io.aequicor.capture.core.RecordingException
import io.aequicor.capture.core.RecordingState
import io.aequicor.capture.core.RecordingSessionId
import io.aequicor.capture.core.RecordingSessionIdFactory
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.ResumeRecordingResult
import io.aequicor.capture.core.StartRecordingResult
import io.aequicor.capture.core.StopRecordingResult
import io.aequicor.capture.core.VideoCaptureAdapter
import io.aequicor.capture.platform.AudioSourceRepository
import io.aequicor.capture.platform.CaptureSourceRepository
import io.aequicor.capture.platform.GrantedPermissionGateway
import io.aequicor.capture.platform.PermissionAuthorization
import io.aequicor.capture.platform.PermissionGateway
import io.aequicor.capture.platform.authorize
import io.aequicor.capture.platform.message
import io.aequicor.capture.platform.EmptyAudioSourceRepository
import io.aequicor.cli.CliCommand
import io.aequicor.cli.RecordTarget
import io.aequicor.cli.RecordingControlAction
import io.aequicor.cli.RecordingControlCommandResult
import io.aequicor.cli.RecordingControlRequest
import io.aequicor.cli.RecordingControlStatus
import io.aequicor.cli.RecordingCommandBackend
import io.aequicor.cli.RecordingCommandResult
import io.aequicor.encoder.FrameSequenceMediaEncoder
import io.aequicor.settings.MissionRecorderSettingsStore
import io.aequicor.settings.MissionRecorderSettingsValidator
import io.aequicor.settings.RecordingProfileSettings
import io.aequicor.settings.toRecordingSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DesktopRecordingCommandBackend(
    private val captureSourceRepository: CaptureSourceRepository,
    private val videoCaptureAdapter: VideoCaptureAdapter,
    private val audioSourceRepository: AudioSourceRepository = EmptyAudioSourceRepository,
    private val audioCaptureAdapter: AudioCaptureAdapter = NoAudioCaptureAdapter,
    private val recordingProfileLoader: RecordingProfileLoader = NoopRecordingProfileLoader,
    private val mediaEncoderFactory: () -> MediaEncoder = { FrameSequenceMediaEncoder() },
    private val permissionGateway: PermissionGateway = GrantedPermissionGateway,
) : RecordingCommandBackend {
    private val sourceResolver = DesktopCaptureSelectionResolver(
        captureSourceRepository = captureSourceRepository,
        audioSourceRepository = audioSourceRepository,
    )
    private val activeRecording = AtomicReference<ActiveCliRecording?>()

    override suspend fun record(command: CliCommand.Record): RecordingCommandResult = coroutineScope {
        val profile = command.options.settingsPath?.let { settingsPath ->
            when (val loaded = recordingProfileLoader.load(settingsPath, command.options.profileId)) {
                is RecordingProfileLoadResult.Loaded -> loaded.profile
                is RecordingProfileLoadResult.Rejected -> return@coroutineScope RecordingCommandResult.Rejected(loaded.message)
                is RecordingProfileLoadResult.Failed -> return@coroutineScope RecordingCommandResult.Failed(loaded.message)
            }
        }
        if (command.target == RecordTarget.Profile && profile == null) {
            return@coroutineScope RecordingCommandResult.Rejected("record profile requires --settings.")
        }

        val resolvedOutputPath = command.options.outputPath ?: profile?.resolveOutputPath()
        val profileSettings = profile?.toRecordingSettings(
            outputPath = resolvedOutputPath ?: profile.resolveOutputPath(),
        )
        val captureCursor = command.options.captureCursor ?: profileSettings?.captureCursor ?: true
        val duration = command.options.duration?.let { rawDuration ->
            rawDuration.parseCliDuration()?.takeIf(Duration::isPositive)
                ?: return@coroutineScope RecordingCommandResult.Rejected(
                    "Invalid --duration: $rawDuration. Use a positive value such as 500ms, 30s, or 5m.",
                )
        }

        val source = sourceResolver.resolveSource(command.target, profileSettings?.captureSource)
            ?: return@coroutineScope RecordingCommandResult.Unsupported("Requested capture source is not available.")
        val outputPath = resolvedOutputPath
            ?: return@coroutineScope RecordingCommandResult.Rejected("record ${command.target.name()} requires --output or --settings.")
        validateControlEndpointPath(command.options.controlEndpointPath, outputPath)?.let { issue ->
            return@coroutineScope RecordingCommandResult.Rejected(issue)
        }
        val audioSources = try {
            when (
                val resolution = sourceResolver.resolveAudioSources(
                    microphoneSelector = command.options.microphone,
                    includeSystemAudio = command.options.systemAudio,
                    systemAudioSelector = command.options.systemAudioEndpoint,
                    microphoneGain = command.options.microphoneGainPercent?.toAudioGain(),
                    systemAudioGain = command.options.systemAudioGainPercent?.toAudioGain(),
                    profileSources = profileSettings?.audioSources.orEmpty(),
                )
            ) {
                is DesktopAudioSourceResolution.Resolved -> resolution.sources
                is DesktopAudioSourceResolution.Rejected -> {
                    return@coroutineScope RecordingCommandResult.Rejected(resolution.message)
                }
            }
        } catch (throwable: Throwable) {
            return@coroutineScope RecordingCommandResult.Failed(
                throwable.message ?: throwable::class.simpleName ?: "Failed to discover audio sources.",
            )
        }

        val settings = profileSettings
            ?.copy(
                captureSource = source,
                audioSources = audioSources,
                outputPath = outputPath,
                frameRate = command.options.fps ?: profileSettings.frameRate,
                captureCursor = captureCursor,
                overwriteOutput = command.options.overwriteOutput || profileSettings.overwriteOutput,
            )
            ?: RecordingSettings(
                captureSource = source,
                audioSources = audioSources,
                outputPath = outputPath,
                frameRate = command.options.fps ?: 30,
                captureCursor = captureCursor,
                overwriteOutput = command.options.overwriteOutput,
            )
        val permissionAuthorization = try {
            permissionGateway.authorize(settings)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return@coroutineScope RecordingCommandResult.Failed(
                "Could not check capture permissions: ${failure.message ?: failure::class.simpleName}.",
            )
        }
        if (permissionAuthorization is PermissionAuthorization.Rejected) {
            return@coroutineScope RecordingCommandResult.Rejected(permissionAuthorization.message())
        }
        val controller = RecordingController(
            videoCaptureAdapter = videoCaptureAdapter,
            audioCaptureAdapter = audioCaptureAdapter,
            mediaEncoder = mediaEncoderFactory(),
            scope = this,
            clock = SystemMediaClock,
            sessionIdFactory = MonotonicSessionIdFactory,
        )
        val recording = ActiveCliRecording(controller, outputPath)
        if (!activeRecording.compareAndSet(null, recording)) {
            return@coroutineScope RecordingCommandResult.Failed("Recorder is busy.")
        }

        return@coroutineScope runActiveRecording(
            recording = recording,
            settings = settings,
            duration = duration,
            controlEndpointPath = command.options.controlEndpointPath,
        )
    }

    suspend fun requestStop(): RecordingCommandResult? {
        val recording = activeRecording.get() ?: return null
        recording.stopRequested.complete(Unit)
        return recording.completion.await()
    }

    private suspend fun runActiveRecording(
        recording: ActiveCliRecording,
        settings: RecordingSettings,
        duration: Duration?,
        controlEndpointPath: String?,
    ): RecordingCommandResult {
        val controlServer = try {
            controlEndpointPath?.let { path ->
                LocalRecordingControlServer.open(path, recording::control)
            }
        } catch (failure: Throwable) {
            val result = RecordingCommandResult.Rejected(
                failure.message ?: "Could not create local recording control endpoint.",
            )
            recording.completion.complete(result)
            activeRecording.compareAndSet(recording, null)
            return result
        }
        try {
            val result = try {
                when (val start = recording.controller.start(settings)) {
                    is StartRecordingResult.Busy -> RecordingCommandResult.Failed("Recorder is busy.")
                    is StartRecordingResult.Failed -> RecordingCommandResult.Failed(start.state.error.message)
                    is StartRecordingResult.Rejected -> RecordingCommandResult.Rejected(
                        start.issues.joinToString("; ") { it.message },
                    )
                    is StartRecordingResult.Started -> {
                        recording.awaitStop(duration)
                        recording.controller.toCommandResult()
                    }
                }
            } catch (cancelled: CancellationException) {
                recording.controller.cancelQuietly()
                recording.completion.cancel(cancelled)
                throw cancelled
            } catch (exception: RecordingException) {
                recording.controller.cancelQuietly()
                RecordingCommandResult.Failed(exception.error.message)
            } catch (exception: Throwable) {
                recording.controller.cancelQuietly()
                RecordingCommandResult.Failed(
                    exception.message ?: exception::class.simpleName ?: "Recording failed.",
                )
            }
            recording.completion.complete(result)
            return result
        } finally {
            controlServer?.close()
            activeRecording.compareAndSet(recording, null)
        }
    }
}

private data class ActiveCliRecording(
    val controller: RecordingController,
    val outputPath: String,
    val stopRequested: CompletableDeferred<Unit> = CompletableDeferred(),
    val completion: CompletableDeferred<RecordingCommandResult> = CompletableDeferred(),
) {
    suspend fun awaitStop(duration: Duration?) {
        if (duration == null) {
            stopRequested.await()
        } else {
            withTimeoutOrNull(duration.inWholeMilliseconds.coerceAtLeast(1)) {
                stopRequested.await()
            }
        }
    }

    suspend fun control(request: RecordingControlRequest): RecordingControlCommandResult = when (val action = request.action) {
        RecordingControlAction.Status -> completedControl(action)
        RecordingControlAction.Pause -> when (controller.pause()) {
            is PauseRecordingResult.Paused -> completedControl(action)
            is PauseRecordingResult.AlreadyPaused -> completedControl(action, "Recording is already paused.")
            is PauseRecordingResult.NotRecording -> RecordingControlCommandResult.Rejected(
                "Recording cannot be paused in state ${controller.recordingState.value.name()}.",
            )
        }
        RecordingControlAction.Resume -> when (controller.resume()) {
            is ResumeRecordingResult.Resumed -> completedControl(action)
            is ResumeRecordingResult.NotPaused -> RecordingControlCommandResult.Rejected(
                "Recording cannot be resumed in state ${controller.recordingState.value.name()}.",
            )
        }
        RecordingControlAction.Save -> RecordingControlCommandResult.Rejected(
            "Save is available for replay buffers, not regular recordings.",
        )
        RecordingControlAction.Stop -> {
            stopRequested.complete(Unit)
            completedControl(action, "Stop requested.")
        }
    }

    private fun completedControl(
        action: RecordingControlAction,
        message: String? = null,
    ): RecordingControlCommandResult.Completed = RecordingControlCommandResult.Completed(
        action = action,
        status = controller.recordingState.value.toControlStatus(outputPath),
        message = message,
    )
}

private fun RecordingState.toControlStatus(fallbackOutputPath: String): RecordingControlStatus {
    val metrics = when (this) {
        RecordingState.Idle, is RecordingState.Preparing -> null
        is RecordingState.Recording -> metrics
        is RecordingState.Paused -> metrics
        is RecordingState.Stopping -> metrics
        is RecordingState.Completed -> metrics
        is RecordingState.Failed -> metrics
        is RecordingState.Cancelled -> metrics
    }
    val outputPath = when (this) {
        is RecordingState.Completed -> outputPath
        else -> fallbackOutputPath
    }
    return RecordingControlStatus(
        state = name(),
        outputPath = outputPath,
        durationMilliseconds = metrics?.duration?.inWholeMilliseconds ?: 0,
        videoFrames = metrics?.videoFrames ?: 0,
        audioFrames = metrics?.audioFrames ?: 0,
        droppedFrames = metrics?.droppedFrames ?: 0,
    )
}

private fun RecordingState.name(): String = when (this) {
    RecordingState.Idle -> "idle"
    is RecordingState.Preparing -> "preparing"
    is RecordingState.Recording -> "recording"
    is RecordingState.Paused -> "paused"
    is RecordingState.Stopping -> "stopping"
    is RecordingState.Completed -> "completed"
    is RecordingState.Failed -> "failed"
    is RecordingState.Cancelled -> "cancelled"
}

private suspend fun RecordingController.toCommandResult(): RecordingCommandResult =
    when (val stop = stop()) {
        is StopRecordingResult.NotRecording -> RecordingCommandResult.Failed(
            (stop.state as? RecordingState.Failed)?.error?.message
                ?: "Recorder stopped unexpectedly.",
        )
        is StopRecordingResult.Stopped -> RecordingCommandResult.Completed(
            outputPath = stop.state.outputPath,
            videoFrames = stop.state.metrics.videoFrames,
            audioFrames = stop.state.metrics.audioFrames,
        )
    }

private suspend fun RecordingController.cancelQuietly() {
    withContext(NonCancellable) {
        runCatching { cancel() }
    }
}

interface RecordingProfileLoader {
    fun load(settingsPath: String, profileId: String?): RecordingProfileLoadResult
}

sealed interface RecordingProfileLoadResult {
    data class Loaded(val profile: RecordingProfileSettings) : RecordingProfileLoadResult
    data class Rejected(val message: String) : RecordingProfileLoadResult
    data class Failed(val message: String) : RecordingProfileLoadResult
}

data object NoopRecordingProfileLoader : RecordingProfileLoader {
    override fun load(settingsPath: String, profileId: String?): RecordingProfileLoadResult =
        RecordingProfileLoadResult.Rejected("No settings backend is wired for recording profiles.")
}

class LocalRecordingProfileLoader : RecordingProfileLoader {
    override fun load(settingsPath: String, profileId: String?): RecordingProfileLoadResult {
        val path = settingsPath.toPath()
        if (!path.exists()) {
            return RecordingProfileLoadResult.Rejected("Settings file does not exist: $path")
        }
        return runCatching {
            val settings = MissionRecorderSettingsStore(path).loadOrDefault()
            val issues = MissionRecorderSettingsValidator.validate(settings)
            if (issues.isNotEmpty()) {
                return@runCatching RecordingProfileLoadResult.Rejected(
                    issues.joinToString("; ") { "${it.field}: ${it.message}" },
                )
            }
            val requestedProfileId = profileId ?: settings.defaultProfileId
            val profile = settings.profiles.firstOrNull { it.id == requestedProfileId }
                ?: return@runCatching RecordingProfileLoadResult.Rejected(
                    "Recording profile does not exist: $requestedProfileId",
                )
            RecordingProfileLoadResult.Loaded(profile)
        }.getOrElse {
            RecordingProfileLoadResult.Failed(it.message ?: it::class.simpleName ?: "Failed to load recording profile.")
        }
    }
}

private data object NoAudioCaptureAdapter : AudioCaptureAdapter {
    override fun frames(settings: RecordingSettings) = emptyFlow<io.aequicor.capture.core.AudioFrame>()
}

internal data object SystemMediaClock : MediaClock {
    private val origin = System.nanoTime()

    override fun nowNanoseconds(): Long = System.nanoTime() - origin
}

internal data object MonotonicSessionIdFactory : RecordingSessionIdFactory {
    override fun nextId(): RecordingSessionId = RecordingSessionId("session-${System.nanoTime()}")
}

internal fun String.parseCliDuration(): Duration? {
    val trimmed = trim().lowercase()
    val number = trimmed.dropLastWhile { it.isLetter() }.toLongOrNull() ?: return null
    return when {
        trimmed.endsWith("ms") -> number.milliseconds
        trimmed.endsWith("s") -> number.seconds
        trimmed.endsWith("m") -> number.minutes
        else -> null
    }
}

private fun RecordingProfileSettings.resolveOutputPath(): String {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val fileName = output.fileNamePattern
        .replace("{timestamp}", timestamp)
        .replace("{profile}", id)
    val directory = Path.of(output.directory).toAbsolutePath().normalize()
    directory.createDirectories()
    return directory.resolve(fileName).toString()
}

private fun String.toPath(): Path = Path.of(this).toAbsolutePath().normalize()

private fun validateControlEndpointPath(controlEndpointPath: String?, outputPath: String): String? {
    if (controlEndpointPath == null) {
        return null
    }
    val control = runCatching(controlEndpointPath::toPath).getOrElse {
        return "Invalid --control-endpoint path: ${it.message}"
    }
    val output = runCatching(outputPath::toPath).getOrElse {
        return "Invalid output path: ${it.message}"
    }
    return if (control == output || control.startsWith(output)) {
        "Control endpoint must be outside the recording output path."
    } else {
        null
    }
}

private fun RecordTarget.name(): String =
    when (this) {
        RecordTarget.Profile -> "profile"
        RecordTarget.Screen -> "screen"
        is RecordTarget.Monitor -> "monitor"
        is RecordTarget.Region -> "region"
        is RecordTarget.Window -> "window"
        is RecordTarget.Application -> "app"
    }
