package io.aequicor.desktop

import io.aequicor.audio.core.AudioLevels
import io.aequicor.audio.core.AudioMuteController
import io.aequicor.audio.core.NoopAudioMuteController
import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.CancelRecordingResult
import io.aequicor.capture.core.EncoderSettings
import io.aequicor.capture.core.MarkImportantFrameResult
import io.aequicor.capture.core.PauseRecordingResult
import io.aequicor.capture.core.PixelFormat
import io.aequicor.capture.core.RecordingController
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.RecordingState
import io.aequicor.capture.core.ResumeRecordingResult
import io.aequicor.capture.core.StartRecordingResult
import io.aequicor.capture.core.StopRecordingResult
import io.aequicor.capture.core.VideoFrame
import io.aequicor.capture.core.release
import io.aequicor.capture.platform.AudioSourceRepository
import io.aequicor.capture.platform.AudioSourceRequest
import io.aequicor.capture.platform.CaptureSourceRepository
import io.aequicor.capture.platform.CaptureSourceRequest
import io.aequicor.capture.platform.CaptureRegionSelection
import io.aequicor.capture.platform.CaptureRegionSelector
import io.aequicor.capture.platform.GrantedPermissionGateway
import io.aequicor.capture.platform.PermissionAuthorization
import io.aequicor.capture.platform.PermissionGateway
import io.aequicor.capture.platform.UnavailableCaptureRegionSelector
import io.aequicor.capture.platform.authorize
import io.aequicor.capture.platform.message
import io.aequicor.compose.ui.RecorderMicrophoneUi
import io.aequicor.compose.ui.PreviewUiStatus
import io.aequicor.compose.ui.RecorderProfileUi
import io.aequicor.compose.ui.MAX_VIDEO_BITRATE_MBPS
import io.aequicor.compose.ui.MAX_AUDIO_GAIN_PERCENT
import io.aequicor.compose.ui.MIN_AUDIO_GAIN_PERCENT
import io.aequicor.compose.ui.MIN_VIDEO_BITRATE_MBPS
import io.aequicor.compose.ui.RecorderSourceKind
import io.aequicor.compose.ui.RecorderSourceUi
import io.aequicor.compose.ui.RecorderStatus
import io.aequicor.compose.ui.RecorderSystemAudioUi
import io.aequicor.compose.ui.RecorderUiAction
import io.aequicor.compose.ui.RecorderUiState
import io.aequicor.compose.ui.ReplayUiStatus
import io.aequicor.compose.ui.StoryboardMode
import io.aequicor.replay.ReplayCaptureController
import io.aequicor.replay.ReplayCaptureState
import io.aequicor.replay.SaveReplayResult
import io.aequicor.replay.StartReplayResult
import io.aequicor.replay.StopReplayResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes

internal interface DesktopRecordingEngine {
    val state: StateFlow<RecordingState>

    suspend fun start(settings: RecordingSettings): StartRecordingResult
    suspend fun pause(): PauseRecordingResult
    suspend fun resume(): ResumeRecordingResult
    suspend fun stop(): StopRecordingResult
    suspend fun cancel(): CancelRecordingResult
    suspend fun markImportantFrame(): MarkImportantFrameResult
}

internal class RecordingControllerEngine(
    private val controller: RecordingController,
) : DesktopRecordingEngine {
    override val state: StateFlow<RecordingState> = controller.recordingState

    override suspend fun start(settings: RecordingSettings): StartRecordingResult = controller.start(settings)

    override suspend fun pause(): PauseRecordingResult = controller.pause()

    override suspend fun resume(): ResumeRecordingResult = controller.resume()

    override suspend fun stop(): StopRecordingResult = controller.stop()

    override suspend fun cancel(): CancelRecordingResult = controller.cancel()

    override suspend fun markImportantFrame(): MarkImportantFrameResult = controller.markImportantFrame()
}

internal interface DesktopReplayEngine {
    val state: StateFlow<ReplayCaptureState>

    suspend fun start(settings: RecordingSettings): StartReplayResult
    suspend fun save(outputPath: String): SaveReplayResult
    suspend fun stop(): StopReplayResult
}

internal class ReplayCaptureControllerEngine(
    private val controller: ReplayCaptureController,
) : DesktopReplayEngine {
    override val state: StateFlow<ReplayCaptureState> = controller.state

    override suspend fun start(settings: RecordingSettings): StartReplayResult = controller.start(settings)

    override suspend fun save(outputPath: String): SaveReplayResult = controller.save(outputPath)

    override suspend fun stop(): StopReplayResult = controller.stop()
}

internal fun interface DesktopPreviewEngine {
    fun frames(settings: RecordingSettings): Flow<VideoFrame>
}

internal data class DesktopPreviewFrame(
    val width: Int,
    val height: Int,
    val pixelFormat: PixelFormat,
    val strideBytes: Int,
    val pixelData: ByteArray,
)

private val UnavailableDesktopPreviewEngine = DesktopPreviewEngine { emptyFlow() }

internal data class DesktopStoryboardExportRequest(
    val inputVideoPath: String,
    val outputPath: String,
    val mode: StoryboardMode,
)

internal data class DesktopStoryboardExportResult(
    val outputPath: String,
    val frameCount: Int,
)

internal fun interface DesktopStoryboardExporter {
    suspend fun export(request: DesktopStoryboardExportRequest): DesktopStoryboardExportResult
}

internal class DesktopRecorderViewModel(
    private val scope: CoroutineScope,
    private val captureSourceRepository: CaptureSourceRepository,
    private val audioSourceRepository: AudioSourceRepository,
    private val recordingEngine: DesktopRecordingEngine,
    private val replayEngine: DesktopReplayEngine,
    private val storyboardExporter: DesktopStoryboardExporter,
    private val previewEngine: DesktopPreviewEngine = UnavailableDesktopPreviewEngine,
    private val nextOutputPath: () -> String,
    private val nextReplayOutputPath: () -> String,
    private val screenshotSaver: DesktopScreenshotSaver = UnavailableDesktopScreenshotSaver,
    private val nextScreenshotOutputPath: (String) -> String = { "screenshot.png" },
    private val captureRegionSelector: CaptureRegionSelector = UnavailableCaptureRegionSelector,
    private val audioLevels: StateFlow<Map<AudioSourceId, AudioLevels>> = MutableStateFlow(emptyMap()),
    private val outputFileSelector: DesktopOutputFileSelector = UnavailableDesktopOutputFileSelector,
    private val recordingsDirectoryOpener: DesktopRecordingsDirectoryOpener =
        UnavailableDesktopRecordingsDirectoryOpener,
    private val audioMuteController: AudioMuteController = NoopAudioMuteController,
    private val permissionGateway: PermissionGateway = GrantedPermissionGateway,
    initialPreferences: DesktopRecorderPreferences = DesktopRecorderPreferences(),
    initialStoryboardInputPath: String = "",
    initialShowApplicationInRecording: Boolean = false,
    initialShowCaptureBorder: Boolean = true,
    private val preferencesWriter: DesktopRecorderPreferencesWriter = NoopDesktopRecorderPreferencesWriter,
    initialProfileCatalog: DesktopRecorderProfileCatalog? = null,
    private val profileStore: DesktopRecorderProfileStore = NoopDesktopRecorderProfileStore,
) {
    private val startupProfile = initialProfileCatalog?.selected
    private val startupPreferences = startupProfile?.preferences ?: initialPreferences
    private val startupMicrophoneGainPercent = startupProfile?.audioSources
        ?.filterIsInstance<AudioSource.Microphone>()
        ?.firstOrNull()
        ?.gain
        .toGainPercent()
    private val startupSystemAudioGainPercent = startupProfile?.audioSources
        ?.filterIsInstance<AudioSource.SystemLoopback>()
        ?.firstOrNull()
        ?.gain
        .toGainPercent()
    private val mutableState = MutableStateFlow(
        RecorderUiState(
            profiles = initialProfileCatalog?.profiles?.map { profile -> profile.toUiModel() }
                ?: listOf(RecorderProfileUi("default", "Default local recording")),
            selectedProfileId = startupProfile?.summary?.id ?: "default",
            outputPath = startupProfile?.outputPath ?: nextOutputPath(),
            overwriteOutput = startupProfile?.overwriteOutput ?: false,
            outputDirectory = startupProfile?.outputPolicy?.directory ?: DesktopOutputPolicy().directory,
            outputFileNamePattern = startupProfile?.outputPolicy?.fileNamePattern
                ?: DesktopOutputPolicy().fileNamePattern,
            frameRate = startupPreferences.frameRate,
            captureCursor = startupPreferences.captureCursor,
            showInputOverlay = startupPreferences.showInputOverlay,
            showMouseTrail = startupPreferences.showMouseTrail,
            recordMouseTrail = startupPreferences.recordMouseTrail,
            showApplicationInRecording = initialShowApplicationInRecording,
            showCaptureBorder = initialShowCaptureBorder,
            replayDurationMinutes = startupPreferences.replayDurationMinutes,
            storyboardMode = startupPreferences.storyboardMode,
            storyboardInputPath = initialStoryboardInputPath,
            videoBitrateMbps = startupPreferences.videoBitrateMbps,
            microphoneGainPercent = startupMicrophoneGainPercent,
            systemAudioGainPercent = startupSystemAudioGainPercent,
        ),
    )
    private val mutablePreviewFrame = MutableStateFlow<DesktopPreviewFrame?>(null)
    private var encoderSettings = startupPreferences.encoderSettings
    private var overwriteOutput = startupProfile?.overwriteOutput ?: false
    private var pendingProfileConfiguration: DesktopRecorderProfileConfiguration? = startupProfile
    private val preferenceUpdates = Channel<DesktopRecorderPreferences>(Channel.CONFLATED)
    private val profileUpdates = Channel<QueuedProfileSnapshot>(Channel.CONFLATED)
    private val profileStoreMutex = Mutex()
    private var profileRevision = 0L
    private val preferenceJob: Job
    private val profileJob: Job
    private val previewJob = AtomicReference<Job?>()

    @Volatile
    private var captureSourcesById: Map<String, CaptureSource> = emptyMap()

    @Volatile
    private var microphonesById: Map<String, AudioSource.Microphone> = emptyMap()

    @Volatile
    private var systemAudioSourcesById: Map<String, AudioSource.SystemLoopback> = emptyMap()

    @Volatile
    private var selectedRegionSource: CaptureSource.Region? = null

    val state: StateFlow<RecorderUiState> = mutableState.asStateFlow()
    val previewFrame: StateFlow<DesktopPreviewFrame?> = mutablePreviewFrame.asStateFlow()

    fun captureSource(sourceId: String?): CaptureSource? = sourceId?.let(captureSourcesById::get)

    init {
        preferenceJob = scope.launch {
            for (preferences in preferenceUpdates) {
                runCatching { preferencesWriter.save(preferences) }
                    .onFailure { failure ->
                        mutableState.update { current ->
                            current.copy(errorMessage = failure.message ?: "Could not save recorder preferences.")
                        }
                    }
            }
        }
        profileJob = scope.launch {
            for (update in profileUpdates) {
                runCatching {
                    profileStoreMutex.withLock {
                        if (update.revision == profileRevision) {
                            profileStore.save(update.snapshot)
                        }
                    }
                }
                    .onFailure { failure ->
                        mutableState.update { current ->
                            current.copy(errorMessage = failure.message ?: "Could not save recording profile.")
                        }
                    }
            }
        }
        scope.launch {
            recordingEngine.state.collect(::applyRecordingState)
        }
        scope.launch {
            replayEngine.state.collect(::applyReplayState)
        }
        scope.launch {
            audioLevels.collect(::applyAudioLevels)
        }
        scope.launch {
            audioMuteController.mutedSourceIds.collect(::applyMutedSources)
        }
        scope.launch {
            audioMuteController.soloSourceId.collect(::applySoloSource)
        }
        refreshSources()
    }

    fun onAction(action: RecorderUiAction) {
        when (action) {
            is RecorderUiAction.SelectProfile -> selectProfile(action.profileId)
            is RecorderUiAction.CreateProfile -> createProfile(action.name)
            RecorderUiAction.ShowCreateProfileDialog -> if (mutableState.value.canManageProfiles) {
                mutableState.update { it.copy(showCreateProfileDialog = true) }
            }
            RecorderUiAction.DismissCreateProfileDialog -> mutableState.update {
                it.copy(showCreateProfileDialog = false)
            }
            RecorderUiAction.ShowDeleteProfileDialog -> if (mutableState.value.canDeleteProfile) {
                mutableState.update { it.copy(showDeleteProfileDialog = true) }
            }
            RecorderUiAction.DismissDeleteProfileDialog -> mutableState.update {
                it.copy(showDeleteProfileDialog = false)
            }
            RecorderUiAction.DeleteSelectedProfile -> deleteSelectedProfile()
            is RecorderUiAction.SelectSource -> selectSource(action.sourceId)
            is RecorderUiAction.SelectMicrophone -> selectMicrophone(action.microphoneId)
            is RecorderUiAction.SetSystemAudioEnabled -> setSystemAudioEnabled(action.enabled)
            is RecorderUiAction.SelectSystemAudio -> selectSystemAudio(action.sourceId)
            is RecorderUiAction.SetMicrophoneMuted -> setMicrophoneMuted(action.muted)
            is RecorderUiAction.SetSystemAudioMuted -> setSystemAudioMuted(action.muted)
            is RecorderUiAction.SetMicrophoneSolo -> setMicrophoneSolo(action.solo)
            is RecorderUiAction.SetSystemAudioSolo -> setSystemAudioSolo(action.solo)
            is RecorderUiAction.SetMicrophoneGainPercent -> setMicrophoneGainPercent(action.percent)
            is RecorderUiAction.SetSystemAudioGainPercent -> setSystemAudioGainPercent(action.percent)
            is RecorderUiAction.SetOutputPath -> setOutputPath(action.path)
            is RecorderUiAction.SetOverwriteOutput -> setOverwriteOutput(action.enabled)
            RecorderUiAction.ShowOutputNamingDialog -> showOutputNamingDialog()
            RecorderUiAction.DismissOutputNamingDialog -> mutableState.update {
                it.copy(showOutputNamingDialog = false)
            }
            is RecorderUiAction.ApplyOutputNaming -> applyOutputNaming(
                directory = action.directory,
                fileNamePattern = action.fileNamePattern,
            )
            is RecorderUiAction.SetFrameRate -> setFrameRate(action.frameRate)
            is RecorderUiAction.SetCaptureCursor -> setCaptureCursor(action.enabled)
            is RecorderUiAction.SetShowInputOverlay -> setShowInputOverlay(action.enabled)
            is RecorderUiAction.SetShowMouseTrail -> setShowMouseTrail(action.enabled)
            is RecorderUiAction.SetRecordMouseTrail -> setRecordMouseTrail(action.enabled)
            is RecorderUiAction.SetShowApplicationInRecording -> mutableState.update {
                it.copy(showApplicationInRecording = action.enabled)
            }
            is RecorderUiAction.SetShowCaptureBorder -> mutableState.update {
                it.copy(showCaptureBorder = action.enabled)
            }
            is RecorderUiAction.SetVideoBitrateMbps -> setVideoBitrateMbps(action.megabitsPerSecond)
            is RecorderUiAction.SetStoryboardInputPath -> setStoryboardInputPath(action.path)
            RecorderUiAction.ChooseStoryboardInputFile -> Unit
            is RecorderUiAction.SetStoryboardMode -> setStoryboardMode(action.mode)
            is RecorderUiAction.SetReplayDurationMinutes -> setReplayDurationMinutes(action.minutes)
            RecorderUiAction.SelectRegion -> selectRegion(RegionSelectionFollowUp.None)
            RecorderUiAction.SelectRegionAndStartRecording -> selectRegion(RegionSelectionFollowUp.StartRecording)
            RecorderUiAction.SelectRegionAndTakeScreenshot -> selectRegion(RegionSelectionFollowUp.TakeScreenshot)
            RecorderUiAction.ChooseOutputFile -> chooseOutputFile()
            RecorderUiAction.OpenRecordingsFolder -> openRecordingsFolder()
            RecorderUiAction.RefreshSources -> refreshSources()
            RecorderUiAction.StartRecording -> startRecording()
            RecorderUiAction.StartPreview -> startPreview()
            RecorderUiAction.StopPreview -> stopPreview()
            RecorderUiAction.PauseRecording -> pauseRecording()
            RecorderUiAction.ResumeRecording -> resumeRecording()
            RecorderUiAction.StopRecording -> stopRecording()
            RecorderUiAction.MarkImportantFrame -> markImportantFrame()
            RecorderUiAction.TakeScreenshot -> takeScreenshot()
            RecorderUiAction.TakeScreenScreenshot -> takeScreenScreenshot()
            RecorderUiAction.OpenEditor -> Unit
            RecorderUiAction.ExportStoryboard -> exportStoryboard()
            RecorderUiAction.StartReplayBuffer -> startReplayBuffer()
            RecorderUiAction.SaveReplayBuffer -> saveReplayBuffer()
            RecorderUiAction.StopReplayBuffer -> stopReplayBuffer()
            RecorderUiAction.DismissError -> mutableState.update { it.copy(errorMessage = null) }
        }
    }

    fun reportPlatformError(message: String) {
        mutableState.update { current -> current.copy(errorMessage = message) }
    }

    fun shutdown(onComplete: () -> Unit) {
        val completed = AtomicBoolean(false)
        fun completeOnce() {
            if (completed.compareAndSet(false, true)) {
                onComplete()
            }
        }
        scope.launch {
            withTimeoutOrNull(SHUTDOWN_TIMEOUT_MILLIS) {
                stopPreviewAndJoin()
                runCatching { recordingEngine.cancel() }
                runCatching { replayEngine.stop() }
                audioMuteController.clear()
                preferenceUpdates.trySend(mutableState.value.toRecorderPreferences(encoderSettings))
                currentProfileSnapshot()?.let(::queueProfileSnapshot)
                preferenceUpdates.close()
                profileUpdates.close()
                runCatching { preferenceJob.join() }
                runCatching { profileJob.join() }
            }
        }.invokeOnCompletion { completeOnce() }
    }

    private fun selectSource(sourceId: String) {
        if (sourceId in captureSourcesById && !mutableState.value.isBusy) {
            mutableState.update { it.copy(selectedSourceId = sourceId) }
            queueRecorderPreferences()
        }
    }

    private fun selectMicrophone(microphoneId: String?) {
        if ((microphoneId == null || microphoneId in microphonesById) && !mutableState.value.isBusy) {
            val previousMicrophoneId = mutableState.value.selectedMicrophoneId
            if (previousMicrophoneId != microphoneId) {
                previousMicrophoneId?.let(::AudioSourceId)?.let { sourceId ->
                    audioMuteController.setMuted(sourceId, false)
                    if (audioMuteController.soloSourceId.value == sourceId) {
                        audioMuteController.setSolo(null)
                    }
                }
            }
            mutableState.update {
                it.copy(
                    selectedMicrophoneId = microphoneId,
                    microphoneLevel = 0f,
                    microphoneMuted = false,
                    microphoneSolo = false,
                )
            }
            queueRecorderPreferences()
        }
    }

    private fun setSystemAudioEnabled(enabled: Boolean) {
        val current = mutableState.value
        when {
            current.systemAudioAvailable && !current.isBusy -> {
                if (!enabled) {
                    selectedSystemAudioSource(current)?.id?.let { sourceId -> audioMuteController.setMuted(sourceId, false) }
                    audioMuteController.setSolo(null)
                }
                mutableState.update {
                    it.copy(
                    systemAudioEnabled = enabled,
                        systemAudioLevel = if (enabled) it.systemAudioLevel else 0f,
                        systemAudioMuted = if (enabled) it.systemAudioMuted else false,
                        microphoneSolo = if (enabled) it.microphoneSolo else false,
                        systemAudioSolo = if (enabled) it.systemAudioSolo else false,
                    )
                }
                queueRecorderPreferences()
            }
            enabled -> mutableState.update {
                it.copy(errorMessage = "System audio is not available in the current desktop backend.")
            }
        }
    }

    private fun selectSystemAudio(sourceId: String) {
        val current = mutableState.value
        if (current.isBusy || sourceId !in systemAudioSourcesById) {
            return
        }
        current.selectedSystemAudioId
            ?.let(systemAudioSourcesById::get)
            ?.id
            ?.let { previous ->
                audioMuteController.setMuted(previous, false)
                if (audioMuteController.soloSourceId.value == previous) {
                    audioMuteController.setSolo(null)
                }
            }
        mutableState.update {
            it.copy(
                selectedSystemAudioId = sourceId,
                systemAudioLevel = 0f,
                systemAudioMuted = false,
                systemAudioSolo = false,
            )
        }
        queueRecorderPreferences()
    }

    private fun setMicrophoneMuted(muted: Boolean) {
        val sourceId = mutableState.value.selectedMicrophoneId?.let(::AudioSourceId) ?: return
        audioMuteController.setMuted(sourceId, muted)
    }

    private fun setSystemAudioMuted(muted: Boolean) {
        val current = mutableState.value
        val sourceId = selectedSystemAudioSource(current)?.id
        if (!current.systemAudioAvailable || !current.systemAudioEnabled || sourceId == null) {
            return
        }
        audioMuteController.setMuted(sourceId, muted)
    }

    private fun setMicrophoneSolo(solo: Boolean) {
        val current = mutableState.value
        val sourceId = current.selectedMicrophoneId?.let(::AudioSourceId)
        if (!current.canToggleAudioSolo || sourceId == null) {
            return
        }
        if (solo) {
            audioMuteController.setSolo(sourceId)
        } else if (audioMuteController.soloSourceId.value == sourceId) {
            audioMuteController.setSolo(null)
        }
    }

    private fun setSystemAudioSolo(solo: Boolean) {
        val current = mutableState.value
        val sourceId = selectedSystemAudioSource(current)?.id
        if (!current.canToggleAudioSolo || sourceId == null) {
            return
        }
        if (solo) {
            audioMuteController.setSolo(sourceId)
        } else if (audioMuteController.soloSourceId.value == sourceId) {
            audioMuteController.setSolo(null)
        }
    }

    private fun setMicrophoneGainPercent(percent: Int) {
        if (!mutableState.value.isBusy && percent in MIN_AUDIO_GAIN_PERCENT..MAX_AUDIO_GAIN_PERCENT) {
            mutableState.update { it.copy(microphoneGainPercent = percent) }
            queueRecorderPreferences()
        }
    }

    private fun setSystemAudioGainPercent(percent: Int) {
        if (!mutableState.value.isBusy && percent in MIN_AUDIO_GAIN_PERCENT..MAX_AUDIO_GAIN_PERCENT) {
            mutableState.update { it.copy(systemAudioGainPercent = percent) }
            queueRecorderPreferences()
        }
    }

    private fun setOutputPath(path: String) {
        if (!mutableState.value.isBusy) {
            mutableState.update { it.copy(outputPath = path) }
        }
    }

    private fun setOverwriteOutput(enabled: Boolean) {
        if (!mutableState.value.isBusy) {
            overwriteOutput = enabled
            mutableState.update { it.copy(overwriteOutput = enabled) }
            queueRecorderPreferences()
        }
    }

    private fun showOutputNamingDialog() {
        if (!mutableState.value.isBusy && !mutableState.value.isRefreshingSources) {
            mutableState.update { it.copy(showOutputNamingDialog = true, errorMessage = null) }
        }
    }

    private fun applyOutputNaming(directory: String, fileNamePattern: String) {
        val current = mutableState.value
        if (current.isBusy || current.isRefreshingSources) {
            return
        }
        val outputPolicy = DesktopOutputPolicy(
            directory = directory.trim(),
            fileNamePattern = fileNamePattern.trim(),
        )
        val validationError = outputPolicy.validationError()
        if (validationError != null) {
            mutableState.update { it.copy(errorMessage = validationError) }
            return
        }
        val previewPath = runCatching {
            profileStore.previewOutputPath(current.selectedProfileId, outputPolicy)
        }.getOrElse { failure ->
            mutableState.update {
                it.copy(errorMessage = failure.message ?: "Could not resolve the output naming pattern.")
            }
            return
        }
        mutableState.update {
            it.copy(
                outputDirectory = outputPolicy.directory,
                outputFileNamePattern = outputPolicy.fileNamePattern,
                outputPath = previewPath ?: it.outputPath,
                showOutputNamingDialog = false,
                errorMessage = null,
            )
        }
        queueRecorderPreferences()
    }

    private fun setFrameRate(frameRate: Int) {
        if (!mutableState.value.isBusy && frameRate in SUPPORTED_DESKTOP_FRAME_RATES) {
            mutableState.update { it.copy(frameRate = frameRate) }
            queueRecorderPreferences()
        }
    }

    private fun setCaptureCursor(enabled: Boolean) {
        if (!mutableState.value.isBusy) {
            mutableState.update { state ->
                state.copy(captureCursor = enabled)
            }
            queueRecorderPreferences()
        }
    }

    private fun setShowInputOverlay(enabled: Boolean) {
        if (!mutableState.value.isBusy) {
            mutableState.update { it.copy(showInputOverlay = enabled) }
            queueRecorderPreferences()
        }
    }

    private fun setShowMouseTrail(enabled: Boolean) {
        if (!mutableState.value.isBusy) {
            mutableState.update { it.copy(showMouseTrail = enabled) }
            queueRecorderPreferences()
        }
    }

    private fun setRecordMouseTrail(enabled: Boolean) {
        if (!mutableState.value.isBusy) {
            mutableState.update { it.copy(recordMouseTrail = enabled) }
            queueRecorderPreferences()
        }
    }

    private fun setVideoBitrateMbps(megabitsPerSecond: Int) {
        val supported = megabitsPerSecond in MIN_VIDEO_BITRATE_MBPS..MAX_VIDEO_BITRATE_MBPS
        if (!mutableState.value.isBusy && supported) {
            encoderSettings = encoderSettings.copy(
                videoBitrateBitsPerSecond = (megabitsPerSecond * BITS_PER_MEGABIT).toInt(),
            )
            mutableState.update { it.copy(videoBitrateMbps = megabitsPerSecond) }
            queueRecorderPreferences()
        }
    }

    private fun chooseOutputFile() {
        val current = mutableState.value
        if (current.isBusy || current.isRefreshingSources) {
            return
        }
        mutableState.update { it.copy(isChoosingOutputFile = true, errorMessage = null) }
        scope.launch {
            try {
                when (val selection = outputFileSelector.chooseOutputFile(current.outputPath)) {
                    DesktopOutputFileSelection.Cancelled -> mutableState.update {
                        it.copy(isChoosingOutputFile = false)
                    }
                    is DesktopOutputFileSelection.Selected -> mutableState.update {
                        it.copy(
                            outputPath = selection.path,
                            isChoosingOutputFile = false,
                            errorMessage = null,
                        )
                    }
                    is DesktopOutputFileSelection.Unavailable -> mutableState.update {
                        it.copy(
                            isChoosingOutputFile = false,
                            errorMessage = selection.message,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(isChoosingOutputFile = false) }
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.update {
                    it.copy(
                        isChoosingOutputFile = false,
                        errorMessage = failure.message ?: "Failed to choose an output file.",
                    )
                }
            }
        }
    }

    private fun openRecordingsFolder() {
        val current = mutableState.value
        val outputPath = (current.lastOutputPath ?: current.outputPath).trim()
        if (outputPath.isBlank()) {
            return
        }
        scope.launch {
            when (val result = recordingsDirectoryOpener.openForOutput(outputPath)) {
                is DesktopDirectoryOpenResult.Opened -> mutableState.update {
                    it.copy(errorMessage = null)
                }
                is DesktopDirectoryOpenResult.Unavailable -> mutableState.update {
                    it.copy(errorMessage = result.message)
                }
            }
        }
    }

    private fun setStoryboardMode(mode: StoryboardMode) {
        if (!mutableState.value.isBusy) {
            mutableState.update { it.copy(storyboardMode = mode) }
            queueRecorderPreferences()
        }
    }

    private fun setStoryboardInputPath(path: String) {
        if (!mutableState.value.isBusy) {
            mutableState.update { it.copy(storyboardInputPath = path) }
        }
    }

    private fun setReplayDurationMinutes(minutes: Int) {
        if (!mutableState.value.isBusy && minutes in MIN_REPLAY_MINUTES..MAX_REPLAY_MINUTES) {
            mutableState.update { it.copy(replayDurationMinutes = minutes) }
            queueRecorderPreferences()
        }
    }

    private fun queueRecorderPreferences() {
        preferenceUpdates.trySend(mutableState.value.toRecorderPreferences(encoderSettings))
        currentProfileSnapshot()?.let(::queueProfileSnapshot)
    }

    private fun queueProfileSnapshot(snapshot: DesktopRecorderProfileSnapshot) {
        profileUpdates.trySend(QueuedProfileSnapshot(profileRevision, snapshot))
    }

    private fun selectProfile(profileId: String) {
        val current = mutableState.value
        if (!current.canManageProfiles || profileId == current.selectedProfileId || current.profiles.none { it.id == profileId }) {
            return
        }
        runProfileOperation { snapshot ->
            profileStore.save(snapshot)
            profileStore.select(profileId)
        }
    }

    private fun createProfile(name: String) {
        val current = mutableState.value
        if (!current.canManageProfiles) {
            return
        }
        if (name.isBlank()) {
            mutableState.update { it.copy(errorMessage = "Profile name must not be blank.") }
            return
        }
        runProfileOperation { snapshot ->
            profileStore.save(snapshot)
            profileStore.create(name.trim(), snapshot)
        }
    }

    private fun deleteSelectedProfile() {
        val current = mutableState.value
        if (!current.canDeleteProfile) {
            return
        }
        runProfileOperation { snapshot -> profileStore.delete(snapshot.profileId) }
    }

    private fun runProfileOperation(
        operation: suspend (DesktopRecorderProfileSnapshot) -> DesktopRecorderProfileCatalog,
    ) {
        val snapshot = currentProfileSnapshot() ?: return
        profileRevision += 1
        mutableState.update {
            it.copy(
                isManagingProfiles = true,
                showCreateProfileDialog = false,
                showDeleteProfileDialog = false,
                errorMessage = null,
            )
        }
        scope.launch {
            try {
                val catalog = profileStoreMutex.withLock { operation(snapshot) }
                applyProfileCatalog(catalog)
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(isManagingProfiles = false) }
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.update {
                    it.copy(
                        isManagingProfiles = false,
                        errorMessage = failure.message ?: "Could not update recording profiles.",
                    )
                }
            }
        }
    }

    private fun applyProfileCatalog(catalog: DesktopRecorderProfileCatalog) {
        val configuration = catalog.selected
        encoderSettings = configuration.preferences.encoderSettings
        overwriteOutput = configuration.overwriteOutput
        val region = configuration.captureSource as? CaptureSource.Region
        selectedRegionSource = region
        if (region != null) {
            captureSourcesById = captureSourcesById
                .filterValues { source -> source !is CaptureSource.Region }
                .plus(region.id.value to region)
        }
        audioMuteController.clear()
        val sourceId = configuration.captureSource.id.value.takeIf(captureSourcesById::containsKey)
            ?: captureSourcesById.keys.firstOrNull()
        val microphoneId = configuration.audioSources
            .filterIsInstance<AudioSource.Microphone>()
            .firstOrNull()
            ?.id
            ?.value
            ?.takeIf(microphonesById::containsKey)
        val wantsSystemAudio = configuration.audioSources.any { source -> source is AudioSource.SystemLoopback }
        val microphoneGainPercent = configuration.audioSources
            .filterIsInstance<AudioSource.Microphone>()
            .firstOrNull()
            ?.gain
            .toGainPercent()
        val systemAudioGainPercent = configuration.audioSources
            .filterIsInstance<AudioSource.SystemLoopback>()
            .firstOrNull()
            ?.gain
            .toGainPercent()
        val systemAudioId = configuration.audioSources
            .filterIsInstance<AudioSource.SystemLoopback>()
            .firstOrNull()
            ?.id
            ?.value
            ?.takeIf(systemAudioSourcesById::containsKey)
            ?: systemAudioSourcesById.keys.firstOrNull().takeIf { wantsSystemAudio }
        val sourceUnavailable = sourceId != configuration.captureSource.id.value
        mutableState.update { current ->
            current.copy(
                profiles = catalog.profiles.map { profile -> profile.toUiModel() },
                selectedProfileId = configuration.summary.id,
                selectedSourceId = sourceId,
                selectedMicrophoneId = microphoneId,
                selectedSystemAudioId = systemAudioId,
                systemAudioEnabled = wantsSystemAudio && current.systemAudioAvailable,
                microphoneMuted = false,
                systemAudioMuted = false,
                microphoneSolo = false,
                systemAudioSolo = false,
                microphoneGainPercent = microphoneGainPercent,
                systemAudioGainPercent = systemAudioGainPercent,
                outputPath = configuration.outputPath,
                overwriteOutput = configuration.overwriteOutput,
                outputDirectory = configuration.outputPolicy.directory,
                outputFileNamePattern = configuration.outputPolicy.fileNamePattern,
                showOutputNamingDialog = false,
                frameRate = configuration.preferences.frameRate,
                captureCursor = configuration.preferences.captureCursor,
                showInputOverlay = configuration.preferences.showInputOverlay,
                showMouseTrail = configuration.preferences.showMouseTrail,
                recordMouseTrail = configuration.preferences.recordMouseTrail,
                videoBitrateMbps = configuration.preferences.videoBitrateMbps,
                replayDurationMinutes = configuration.preferences.replayDurationMinutes,
                storyboardMode = configuration.preferences.storyboardMode,
                isManagingProfiles = false,
                errorMessage = if (sourceUnavailable) {
                    "The profile capture source is unavailable. Select an available source."
                } else {
                    null
                },
            )
        }
    }

    private fun currentProfileSnapshot(): DesktopRecorderProfileSnapshot? {
        val current = mutableState.value
        val captureSource = current.selectedSourceId?.let(captureSourcesById::get) ?: return null
        return DesktopRecorderProfileSnapshot(
            profileId = current.selectedProfileId,
            preferences = current.toRecorderPreferences(encoderSettings),
            captureSource = captureSource,
            audioSources = selectedAudioSources(current),
            overwriteOutput = current.overwriteOutput,
            outputPolicy = DesktopOutputPolicy(
                directory = current.outputDirectory,
                fileNamePattern = current.outputFileNamePattern,
            ),
        )
    }

    private fun selectRegion(followUp: RegionSelectionFollowUp) {
        if (
            mutableState.value.isBusy ||
            mutableState.value.isRefreshingSources ||
            mutableState.value.isSavingScreenshot
        ) {
            return
        }
        mutableState.update { it.copy(isSelectingRegion = true, errorMessage = null) }
        scope.launch {
            try {
                applyRegionSelection(
                    result = captureRegionSelector.selectRegion(),
                    followUp = followUp,
                )
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(isSelectingRegion = false) }
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.update {
                    it.copy(
                        isSelectingRegion = false,
                        errorMessage = failure.message ?: "Failed to select a capture region.",
                    )
                }
            }
        }
    }

    private fun applyRegionSelection(
        result: CaptureRegionSelection,
        followUp: RegionSelectionFollowUp,
    ) {
        when (result) {
            CaptureRegionSelection.Cancelled -> mutableState.update { it.copy(isSelectingRegion = false) }
            is CaptureRegionSelection.Unavailable -> mutableState.update {
                it.copy(isSelectingRegion = false, errorMessage = result.message)
            }
            is CaptureRegionSelection.Selected -> {
                val region = result.region
                val source = CaptureSource.Region(
                    id = CaptureSourceId("region:${region.x},${region.y},${region.width}x${region.height}"),
                    displayName = "${region.width}x${region.height} @ ${region.x},${region.y}",
                    region = region,
                )
                selectedRegionSource = source
                captureSourcesById = captureSourcesById
                    .filterValues { existing -> existing !is CaptureSource.Region }
                    .plus(source.id.value to source)
                mutableState.update { current ->
                    current.copy(
                        sources = current.sources
                            .filterNot { existing -> existing.kind == RecorderSourceKind.Region }
                            .plus(source.toUiModel()),
                        selectedSourceId = source.id.value,
                        isSelectingRegion = false,
                        errorMessage = null,
                    )
                }
                queueRecorderPreferences()
                when (followUp) {
                    RegionSelectionFollowUp.None -> Unit
                    RegionSelectionFollowUp.StartRecording -> startRecording()
                    RegionSelectionFollowUp.TakeScreenshot -> takeScreenshot(source)
                }
            }
        }
    }

    private fun refreshSources() {
        if (mutableState.value.isBusy || mutableState.value.isRefreshingSources) {
            return
        }
        scope.launch {
            mutableState.update { it.copy(isRefreshingSources = true, errorMessage = null) }
            runCatching {
                val captureSources = async {
                    captureSourceRepository.listSources(
                        CaptureSourceRequest(
                            includeScreens = true,
                            includeMonitors = true,
                            includeWindows = true,
                            includeApplications = true,
                        ),
                    )
                }
                val audioSources = async {
                    audioSourceRepository.listAudioSources(
                        AudioSourceRequest(includeMicrophones = true, includeSystemLoopback = true),
                    )
                }
                captureSources.await() to audioSources.await()
            }.onSuccess { (discoveredCaptureSources, audioSources) ->
                val pendingProfile = pendingProfileConfiguration
                (pendingProfile?.captureSource as? CaptureSource.Region)?.let { region ->
                    selectedRegionSource = region
                }
                val captureSources = discoveredCaptureSources + listOfNotNull(selectedRegionSource)
                val microphones = audioSources.filterIsInstance<AudioSource.Microphone>()
                val loopbacks = audioSources.filterIsInstance<AudioSource.SystemLoopback>()
                captureSourcesById = captureSources.associateBy { it.id.value }
                microphonesById = microphones.associateBy { it.id.value }
                systemAudioSourcesById = loopbacks.associateBy { source -> source.id.value }
                val availableAudioSourceIds = microphones.mapTo(mutableSetOf()) { it.id }
                loopbacks.mapTo(availableAudioSourceIds) { source -> source.id }
                (audioMuteController.mutedSourceIds.value - availableAudioSourceIds).forEach { sourceId ->
                    audioMuteController.setMuted(sourceId, false)
                }
                audioMuteController.soloSourceId.value
                    ?.takeUnless(availableAudioSourceIds::contains)
                    ?.let { audioMuteController.setSolo(null) }
                mutableState.update { current ->
                    val selectedSourceId = pendingProfile?.captureSource?.id?.value
                        ?.takeIf(captureSourcesById::containsKey)
                        ?: current.selectedSourceId
                        ?.takeIf(captureSourcesById::containsKey)
                        ?: captureSources.firstOrNull()?.id?.value
                    val pendingMicrophoneId = pendingProfile?.audioSources
                        ?.filterIsInstance<AudioSource.Microphone>()
                        ?.firstOrNull()
                        ?.id
                        ?.value
                    val selectedMicrophoneId = pendingMicrophoneId
                        ?.takeIf(microphonesById::containsKey)
                        ?: current.selectedMicrophoneId
                        ?.takeIf(microphonesById::containsKey)
                    val pendingSystemAudio = pendingProfile?.audioSources
                        ?.any { source -> source is AudioSource.SystemLoopback }
                    val pendingSystemAudioId = pendingProfile?.audioSources
                        ?.filterIsInstance<AudioSource.SystemLoopback>()
                        ?.firstOrNull()
                        ?.id
                        ?.value
                    val selectedSystemAudioId = pendingSystemAudioId
                        ?.takeIf(systemAudioSourcesById::containsKey)
                        ?: current.selectedSystemAudioId?.takeIf(systemAudioSourcesById::containsKey)
                        ?: loopbacks.firstOrNull()?.id?.value
                    val pendingMicrophoneGainPercent = pendingProfile?.audioSources
                        ?.filterIsInstance<AudioSource.Microphone>()
                        ?.firstOrNull()
                        ?.gain
                        ?.toGainPercent()
                    val pendingSystemAudioGainPercent = pendingProfile?.audioSources
                        ?.filterIsInstance<AudioSource.SystemLoopback>()
                        ?.firstOrNull()
                        ?.gain
                        ?.toGainPercent()
                    current.copy(
                        sources = captureSources.map(CaptureSource::toUiModel),
                        selectedSourceId = selectedSourceId,
                        microphones = microphones.map(AudioSource.Microphone::toUiModel),
                        selectedMicrophoneId = selectedMicrophoneId,
                        systemAudioSources = loopbacks.map(AudioSource.SystemLoopback::toUiModel),
                        selectedSystemAudioId = selectedSystemAudioId,
                        systemAudioAvailable = loopbacks.isNotEmpty(),
                        systemAudioEnabled = (pendingSystemAudio ?: current.systemAudioEnabled) && loopbacks.isNotEmpty(),
                        microphoneGainPercent = pendingMicrophoneGainPercent ?: current.microphoneGainPercent,
                        systemAudioGainPercent = pendingSystemAudioGainPercent ?: current.systemAudioGainPercent,
                        microphoneMuted = selectedMicrophoneId
                            ?.let(::AudioSourceId)
                            ?.let(audioMuteController.mutedSourceIds.value::contains)
                            ?: false,
                        systemAudioMuted = current.systemAudioEnabled && selectedSystemAudioId
                            ?.let(systemAudioSourcesById::get)
                            ?.id in audioMuteController.mutedSourceIds.value,
                        isRefreshingSources = false,
                    )
                }
                pendingProfileConfiguration = null
            }.onFailure { throwable ->
                mutableState.update {
                    it.copy(
                        isRefreshingSources = false,
                        errorMessage = throwable.message ?: "Failed to discover capture sources.",
                    )
                }
            }
        }
    }

    private fun startRecording() {
        val snapshot = mutableState.value
        if (!snapshot.canStart) {
            return
        }
        val captureSource = snapshot.selectedSourceId?.let(captureSourcesById::get) ?: return
        val audioSources = selectedAudioSources(snapshot)
        val settings = RecordingSettings(
            captureSource = captureSource,
            audioSources = audioSources,
            outputPath = snapshot.outputPath.trim(),
            overwriteOutput = overwriteOutput,
            frameRate = snapshot.frameRate,
            captureCursor = snapshot.captureCursor,
            showInputOverlay = snapshot.showInputOverlay,
            showMouseTrail = snapshot.showMouseTrail,
            recordMouseTrail = snapshot.recordMouseTrail,
            encoder = encoderSettings,
        )

        scope.launch {
            mutableState.update {
                it.copy(
                    status = RecorderStatus.Preparing,
                    elapsedMilliseconds = 0,
                    videoFrames = 0,
                    audioFrames = 0,
                    droppedFrames = 0,
                    effectiveFramesPerSecond = 0.0,
                    errorMessage = null,
                    lastOutputPath = null,
                    lastStoryboardPath = null,
                )
            }
            stopPreviewAndJoin(clearFrame = false)
            permissionError(settings)?.let { message ->
                mutableState.update {
                    it.copy(status = RecorderStatus.Failed, errorMessage = message)
                }
                return@launch
            }
            when (val result = recordingEngine.start(settings)) {
                is StartRecordingResult.Started -> Unit
                is StartRecordingResult.Busy -> mutableState.update {
                    it.copy(status = RecorderStatus.Failed, errorMessage = "Recorder is already busy.")
                }
                is StartRecordingResult.Failed -> mutableState.update {
                    it.copy(status = RecorderStatus.Failed, errorMessage = result.state.error.message)
                }
                is StartRecordingResult.Rejected -> mutableState.update {
                    it.copy(
                        status = RecorderStatus.Failed,
                        errorMessage = result.issues.joinToString("; ") { issue -> issue.message },
                    )
                }
            }
        }
    }

    private fun exportStoryboard() {
        val snapshot = mutableState.value
        if (!snapshot.canExportStoryboard) {
            return
        }
        val inputVideoPath = snapshot.storyboardInputPath.trim()
        if (inputVideoPath.isEmpty()) {
            return
        }
        val request = DesktopStoryboardExportRequest(
            inputVideoPath = inputVideoPath,
            outputPath = storyboardOutputPath(inputVideoPath, snapshot.storyboardMode),
            mode = snapshot.storyboardMode,
        )
        scope.launch {
            mutableState.update {
                it.copy(isExportingStoryboard = true, lastStoryboardPath = null, errorMessage = null)
            }
            runCatching { storyboardExporter.export(request) }
                .onSuccess { result ->
                    mutableState.update {
                        it.copy(
                            isExportingStoryboard = false,
                            lastStoryboardPath = result.outputPath,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure { throwable ->
                    mutableState.update {
                        it.copy(
                            isExportingStoryboard = false,
                            errorMessage = throwable.message ?: "Failed to create storyboard.",
                        )
                    }
                }
        }
    }

    private fun pauseRecording() {
        if (!mutableState.value.canPauseRecording) {
            return
        }
        scope.launch {
            when (recordingEngine.pause()) {
                is PauseRecordingResult.Paused,
                is PauseRecordingResult.AlreadyPaused,
                -> Unit
                is PauseRecordingResult.NotRecording -> mutableState.update {
                    it.copy(errorMessage = "Recording is not active.")
                }
            }
        }
    }

    private fun markImportantFrame() {
        if (!mutableState.value.canMarkImportantFrame) {
            return
        }
        scope.launch {
            when (recordingEngine.markImportantFrame()) {
                MarkImportantFrameResult.Marked -> mutableState.update { current ->
                    current.copy(importantFrameCaptureSequence = current.importantFrameCaptureSequence + 1L)
                }
                is MarkImportantFrameResult.NotRecording -> Unit
            }
        }
    }

    private fun takeScreenScreenshot() {
        val snapshot = mutableState.value
        if (!snapshot.canTakeScreenScreenshot) {
            return
        }
        val selectedSource = snapshot.selectedSourceId?.let(captureSourcesById::get)
        val screenSource = selectedSource
            ?.takeIf { source -> source is CaptureSource.Screen || source is CaptureSource.Monitor }
            ?: captureSourcesById.values.firstOrNull { source -> source is CaptureSource.Screen }
            ?: captureSourcesById.values.firstOrNull { source -> source is CaptureSource.Monitor }
            ?: return
        takeScreenshot(screenSource)
    }

    private fun takeScreenshot(captureSource: CaptureSource? = null) {
        val snapshot = mutableState.value
        val source = captureSource ?: snapshot.selectedSourceId?.let(captureSourcesById::get)
        if (!snapshot.canTakeScreenshot || source == null) {
            return
        }
        val previewFrame = mutablePreviewFrame.value.takeIf {
            snapshot.previewStatus == PreviewUiStatus.Active && snapshot.selectedSourceId == source.id.value
        }
        val outputPath = try {
            nextScreenshotOutputPath(snapshot.outputPath)
        } catch (failure: IllegalArgumentException) {
            mutableState.update { it.copy(errorMessage = failure.message ?: "Screenshot path is invalid.") }
            return
        } catch (failure: SecurityException) {
            mutableState.update { it.copy(errorMessage = failure.message ?: "Screenshot path is not accessible.") }
            return
        }
        mutableState.update {
            it.copy(isSavingScreenshot = true, lastScreenshotPath = null, errorMessage = null)
        }
        scope.launch {
            val frame = try {
                previewFrame ?: captureScreenshotFrame(source = source, snapshot = snapshot)
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(isSavingScreenshot = false) }
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.update {
                    it.copy(
                        isSavingScreenshot = false,
                        errorMessage = failure.message ?: "Could not capture screenshot.",
                    )
                }
                return@launch
            } ?: return@launch
            when (val result = screenshotSaver.save(frame, outputPath)) {
                is DesktopScreenshotSaveResult.Saved -> mutableState.update {
                    it.copy(
                        isSavingScreenshot = false,
                        lastScreenshotPath = result.outputPath,
                        errorMessage = null,
                    )
                }
                is DesktopScreenshotSaveResult.Failed -> mutableState.update {
                    it.copy(isSavingScreenshot = false, errorMessage = result.message)
                }
            }
        }
    }

    private suspend fun captureScreenshotFrame(
        source: CaptureSource,
        snapshot: RecorderUiState,
    ): DesktopPreviewFrame? {
        val settings = RecordingSettings(
            captureSource = source,
            audioSources = emptyList(),
            outputPath = PREVIEW_OUTPUT_PLACEHOLDER,
            frameRate = minOf(snapshot.frameRate, MAX_PREVIEW_FRAME_RATE),
            captureCursor = false,
            showMouseTrail = snapshot.showMouseTrail,
            encoder = encoderSettings,
        )
        permissionError(settings)?.let { message ->
            mutableState.update { it.copy(isSavingScreenshot = false, errorMessage = message) }
            return null
        }
        val frame = withTimeoutOrNull(SCREENSHOT_CAPTURE_TIMEOUT_MILLIS) {
            previewEngine.frames(settings).firstOrNull()
        }
        if (frame == null) {
            mutableState.update {
                it.copy(
                    isSavingScreenshot = false,
                    errorMessage = "Screenshot source did not produce a frame.",
                )
            }
            return null
        }
        return try {
            frame.toDesktopPreviewFrame()
        } finally {
            frame.release()
        }
    }

    private fun resumeRecording() {
        if (!mutableState.value.canResumeRecording) {
            return
        }
        scope.launch {
            when (recordingEngine.resume()) {
                is ResumeRecordingResult.Resumed -> Unit
                is ResumeRecordingResult.NotPaused -> mutableState.update {
                    it.copy(errorMessage = "Recording is not paused.")
                }
            }
        }
    }

    private fun stopRecording() {
        if (!mutableState.value.hasActiveRecording) {
            return
        }
        scope.launch {
            mutableState.update { it.copy(status = RecorderStatus.Stopping) }
            val result = recordingEngine.stop()
            if (result is StopRecordingResult.NotRecording && result.state !is RecordingState.Failed) {
                mutableState.update {
                    it.copy(status = RecorderStatus.Failed, errorMessage = "Recorder stopped unexpectedly.")
                }
            }
        }
    }

    private fun startReplayBuffer() {
        val snapshot = mutableState.value
        if (!snapshot.canStartReplay) {
            return
        }
        val captureSource = snapshot.selectedSourceId?.let(captureSourcesById::get) ?: return
        val audioSources = selectedAudioSources(snapshot)
        val settings = RecordingSettings(
            captureSource = captureSource,
            audioSources = audioSources,
            outputPath = nextReplayOutputPath(),
            overwriteOutput = false,
            frameRate = snapshot.frameRate,
            captureCursor = snapshot.captureCursor,
            showInputOverlay = snapshot.showInputOverlay,
            showMouseTrail = snapshot.showMouseTrail,
            recordMouseTrail = snapshot.recordMouseTrail,
            replayDuration = snapshot.replayDurationMinutes.minutes,
            encoder = encoderSettings,
        )
        scope.launch {
            mutableState.update {
                it.copy(
                    replayStatus = ReplayUiStatus.Preparing,
                    replayRetainedMilliseconds = 0,
                    replayVideoFrames = 0,
                    replayAudioFrames = 0,
                    replayDroppedFrames = 0,
                    lastReplayPath = null,
                    errorMessage = null,
                )
            }
            stopPreviewAndJoin(clearFrame = false)
            permissionError(settings)?.let { message ->
                mutableState.update {
                    it.copy(replayStatus = ReplayUiStatus.Failed, errorMessage = message)
                }
                return@launch
            }
            when (val result = replayEngine.start(settings)) {
                is StartReplayResult.Started -> Unit
                is StartReplayResult.Busy -> mutableState.update {
                    it.copy(replayStatus = ReplayUiStatus.Failed, errorMessage = "Replay buffer is already active.")
                }
                is StartReplayResult.Failed -> mutableState.update {
                    it.copy(replayStatus = ReplayUiStatus.Failed, errorMessage = result.state.message)
                }
                is StartReplayResult.Rejected -> mutableState.update {
                    it.copy(
                        replayStatus = ReplayUiStatus.Failed,
                        errorMessage = result.issues.joinToString("; ") { issue -> issue.message },
                    )
                }
            }
        }
    }

    private fun selectedAudioSources(snapshot: RecorderUiState): List<AudioSource> = buildList {
        snapshot.selectedMicrophoneId
            ?.let(microphonesById::get)
            ?.copy(gain = snapshot.microphoneGainPercent.toAudioGain())
            ?.let(::add)
        if (snapshot.systemAudioEnabled) {
            selectedSystemAudioSource(snapshot)
                ?.copy(gain = snapshot.systemAudioGainPercent.toAudioGain())
                ?.let(::add)
        }
    }

    private fun startPreview() {
        val snapshot = mutableState.value
        if (!snapshot.canStartPreview) {
            return
        }
        val captureSource = snapshot.selectedSourceId?.let(captureSourcesById::get) ?: return
        val settings = RecordingSettings(
            captureSource = captureSource,
            audioSources = emptyList(),
            outputPath = PREVIEW_OUTPUT_PLACEHOLDER,
            frameRate = minOf(snapshot.frameRate, MAX_PREVIEW_FRAME_RATE),
            captureCursor = false,
            showMouseTrail = snapshot.showMouseTrail,
            encoder = encoderSettings,
        )
        val job = scope.launch(start = CoroutineStart.LAZY) {
            mutableState.update {
                it.copy(previewStatus = PreviewUiStatus.Preparing, errorMessage = null)
            }
            permissionError(settings)?.let { message ->
                mutablePreviewFrame.value = null
                mutableState.update {
                    it.copy(previewStatus = PreviewUiStatus.Failed, errorMessage = message)
                }
                return@launch
            }
            try {
                previewEngine.frames(settings).collect { frame ->
                    try {
                        mutablePreviewFrame.value = frame.toDesktopPreviewFrame()
                        mutableState.update { current ->
                            current.copy(previewStatus = PreviewUiStatus.Active, errorMessage = null)
                        }
                    } finally {
                        frame.release()
                    }
                }
                error("Preview source stopped producing frames.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutablePreviewFrame.value = null
                mutableState.update {
                    it.copy(
                        previewStatus = PreviewUiStatus.Failed,
                        errorMessage = failure.message ?: "Preview failed.",
                    )
                }
            }
        }
        if (!previewJob.compareAndSet(null, job)) {
            return
        }
        job.invokeOnCompletion {
            if (previewJob.compareAndSet(job, null)) {
                if (mutableState.value.isPreviewRunning) {
                    mutablePreviewFrame.value = null
                    mutableState.update { it.copy(previewStatus = PreviewUiStatus.Idle) }
                }
            }
        }
        job.start()
    }

    private fun stopPreview() {
        previewJob.getAndSet(null)?.cancel()
        clearPreviewState()
    }

    private suspend fun stopPreviewAndJoin(clearFrame: Boolean = true) {
        previewJob.getAndSet(null)?.cancelAndJoin()
        clearPreviewState(clearFrame)
    }

    private fun clearPreviewState(clearFrame: Boolean = true) {
        if (clearFrame) {
            mutablePreviewFrame.value = null
        }
        mutableState.update { current ->
            if (current.previewStatus == PreviewUiStatus.Idle) current
            else current.copy(previewStatus = PreviewUiStatus.Idle)
        }
    }

    private suspend fun permissionError(settings: RecordingSettings): String? = try {
        when (val authorization = permissionGateway.authorize(settings)) {
            is PermissionAuthorization.Granted -> null
            is PermissionAuthorization.Rejected -> authorization.message()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        "Could not check capture permissions: ${failure.message ?: failure::class.simpleName}."
    }

    private fun saveReplayBuffer() {
        if (!mutableState.value.canSaveReplay) {
            return
        }
        val outputPath = nextReplayOutputPath()
        scope.launch {
            when (val result = replayEngine.save(outputPath)) {
                is SaveReplayResult.Saved -> mutableState.update {
                    it.copy(lastReplayPath = result.result.output.path, errorMessage = null)
                }
                is SaveReplayResult.Failed -> mutableState.update {
                    it.copy(errorMessage = result.message)
                }
                is SaveReplayResult.NotBuffering -> mutableState.update {
                    it.copy(errorMessage = "Replay buffer is not active.")
                }
            }
        }
    }

    private fun stopReplayBuffer() {
        if (!mutableState.value.canStopReplay) {
            return
        }
        scope.launch {
            when (val result = replayEngine.stop()) {
                is StopReplayResult.Stopped -> Unit
                is StopReplayResult.Failed -> mutableState.update {
                    it.copy(replayStatus = ReplayUiStatus.Failed, errorMessage = result.state.message)
                }
                is StopReplayResult.NotBuffering -> mutableState.update {
                    it.copy(errorMessage = "Replay buffer is not active.")
                }
            }
        }
    }

    private fun applyRecordingState(recordingState: RecordingState) {
        mutableState.update { current ->
            when (recordingState) {
                RecordingState.Idle -> current.copy(
                    status = RecorderStatus.Idle,
                    microphoneLevel = 0f,
                    systemAudioLevel = 0f,
                )
                is RecordingState.Preparing -> current.copy(status = RecorderStatus.Preparing)
                is RecordingState.Recording -> current.withMetrics(
                    status = RecorderStatus.Recording,
                    state = recordingState,
                )
                is RecordingState.Paused -> current.copy(
                    status = RecorderStatus.Paused,
                    elapsedMilliseconds = recordingState.metrics.duration.inWholeMilliseconds,
                    videoFrames = recordingState.metrics.videoFrames,
                    audioFrames = recordingState.metrics.audioFrames,
                    droppedFrames = recordingState.metrics.droppedFrames,
                    effectiveFramesPerSecond = recordingState.metrics.effectiveFramesPerSecond,
                )
                is RecordingState.Stopping -> current.copy(
                    status = RecorderStatus.Stopping,
                    elapsedMilliseconds = recordingState.metrics.duration.inWholeMilliseconds,
                    videoFrames = recordingState.metrics.videoFrames,
                    audioFrames = recordingState.metrics.audioFrames,
                    droppedFrames = recordingState.metrics.droppedFrames,
                    effectiveFramesPerSecond = recordingState.metrics.effectiveFramesPerSecond,
                    microphoneLevel = 0f,
                    systemAudioLevel = 0f,
                )
                is RecordingState.Completed -> current.copy(
                    status = RecorderStatus.Completed,
                    elapsedMilliseconds = recordingState.metrics.duration.inWholeMilliseconds,
                    videoFrames = recordingState.metrics.videoFrames,
                    audioFrames = recordingState.metrics.audioFrames,
                    droppedFrames = recordingState.metrics.droppedFrames,
                    effectiveFramesPerSecond = recordingState.metrics.effectiveFramesPerSecond,
                    lastOutputPath = recordingState.outputPath,
                    storyboardInputPath = recordingState.outputPath,
                    outputPath = nextRecordingOutputPath(current.selectedProfileId),
                    errorMessage = null,
                    microphoneLevel = 0f,
                    systemAudioLevel = 0f,
                )
                is RecordingState.Failed -> current.copy(
                    status = RecorderStatus.Failed,
                    elapsedMilliseconds = recordingState.metrics.duration.inWholeMilliseconds,
                    videoFrames = recordingState.metrics.videoFrames,
                    audioFrames = recordingState.metrics.audioFrames,
                    droppedFrames = recordingState.metrics.droppedFrames,
                    effectiveFramesPerSecond = recordingState.metrics.effectiveFramesPerSecond,
                    errorMessage = recordingState.error.message,
                    microphoneLevel = 0f,
                    systemAudioLevel = 0f,
                )
                is RecordingState.Cancelled -> current.copy(
                    status = RecorderStatus.Idle,
                    elapsedMilliseconds = recordingState.metrics.duration.inWholeMilliseconds,
                    videoFrames = recordingState.metrics.videoFrames,
                    audioFrames = recordingState.metrics.audioFrames,
                    droppedFrames = recordingState.metrics.droppedFrames,
                    effectiveFramesPerSecond = recordingState.metrics.effectiveFramesPerSecond,
                    microphoneLevel = 0f,
                    systemAudioLevel = 0f,
                )
            }
        }
    }

    private fun nextRecordingOutputPath(profileId: String): String =
        runCatching { profileStore.nextOutputPath(profileId) }
            .getOrNull()
            ?: nextOutputPath()

    private fun applyReplayState(replayState: ReplayCaptureState) {
        mutableState.update { current ->
            when (replayState) {
                ReplayCaptureState.Idle -> current.copy(
                    replayStatus = ReplayUiStatus.Idle,
                    replayRetainedMilliseconds = 0,
                    replayVideoFrames = 0,
                    replayAudioFrames = 0,
                    replayDroppedFrames = 0,
                    microphoneLevel = 0f,
                    systemAudioLevel = 0f,
                )
                is ReplayCaptureState.Preparing -> current.copy(replayStatus = ReplayUiStatus.Preparing)
                is ReplayCaptureState.Buffering -> current.withReplayStats(
                    status = ReplayUiStatus.Buffering,
                    state = replayState,
                )
                is ReplayCaptureState.Saving -> current.copy(
                    replayStatus = ReplayUiStatus.Saving,
                    replayRetainedMilliseconds = replayState.stats.retainedDuration.inWholeMilliseconds,
                    replayVideoFrames = replayState.stats.videoFrameCount,
                    replayAudioFrames = replayState.stats.audioFrameCount,
                    replayDroppedFrames = replayState.stats.droppedVideoFrameCount,
                )
                is ReplayCaptureState.Stopping -> current.copy(
                    replayStatus = ReplayUiStatus.Stopping,
                    replayRetainedMilliseconds = replayState.stats.retainedDuration.inWholeMilliseconds,
                    replayVideoFrames = replayState.stats.videoFrameCount,
                    replayAudioFrames = replayState.stats.audioFrameCount,
                    replayDroppedFrames = replayState.stats.droppedVideoFrameCount,
                    microphoneLevel = 0f,
                    systemAudioLevel = 0f,
                )
                is ReplayCaptureState.Failed -> current.copy(
                    replayStatus = ReplayUiStatus.Failed,
                    replayRetainedMilliseconds = replayState.stats.retainedDuration.inWholeMilliseconds,
                    replayVideoFrames = replayState.stats.videoFrameCount,
                    replayAudioFrames = replayState.stats.audioFrameCount,
                    replayDroppedFrames = replayState.stats.droppedVideoFrameCount,
                    errorMessage = replayState.message,
                    microphoneLevel = 0f,
                    systemAudioLevel = 0f,
                )
            }
        }
    }

    private fun applyAudioLevels(levels: Map<AudioSourceId, AudioLevels>) {
        mutableState.update { current ->
            val isAudioCaptureActive = current.hasActiveRecording || current.isReplayActive
            current.copy(
                microphoneLevel = if (isAudioCaptureActive) {
                    levels.levelFor(current.selectedMicrophoneId)
                } else {
                    0f
                },
                systemAudioLevel = if (isAudioCaptureActive && current.systemAudioEnabled) {
                    levels.levelFor(current.selectedSystemAudioId)
                } else {
                    0f
                },
            )
        }
    }

    private fun applyMutedSources(mutedSourceIds: Set<AudioSourceId>) {
        mutableState.update { current ->
            current.copy(
                microphoneMuted = current.selectedMicrophoneId
                    ?.let(::AudioSourceId)
                    ?.let(mutedSourceIds::contains)
                    ?: false,
                systemAudioMuted = current.systemAudioEnabled &&
                    selectedSystemAudioSource(current)?.id?.let(mutedSourceIds::contains) == true,
            )
        }
    }

    private fun applySoloSource(soloSourceId: AudioSourceId?) {
        mutableState.update { current ->
            current.copy(
                microphoneSolo = soloSourceId != null && current.selectedMicrophoneId
                    ?.let(::AudioSourceId) == soloSourceId,
                systemAudioSolo = soloSourceId != null && current.systemAudioEnabled &&
                    selectedSystemAudioSource(current)?.id == soloSourceId,
            )
        }
    }

    private fun selectedSystemAudioSource(state: RecorderUiState): AudioSource.SystemLoopback? =
        state.selectedSystemAudioId?.let(systemAudioSourcesById::get)
}

private fun RecorderUiState.withMetrics(
    status: RecorderStatus,
    state: RecordingState.Recording,
): RecorderUiState = copy(
    status = status,
    elapsedMilliseconds = state.metrics.duration.inWholeMilliseconds,
    videoFrames = state.metrics.videoFrames,
    audioFrames = state.metrics.audioFrames,
    droppedFrames = state.metrics.droppedFrames,
    effectiveFramesPerSecond = state.metrics.effectiveFramesPerSecond,
)

private fun RecorderUiState.withReplayStats(
    status: ReplayUiStatus,
    state: ReplayCaptureState.Buffering,
): RecorderUiState = copy(
    replayStatus = status,
    replayRetainedMilliseconds = state.stats.retainedDuration.inWholeMilliseconds,
    replayVideoFrames = state.stats.videoFrameCount,
    replayAudioFrames = state.stats.audioFrameCount,
    replayDroppedFrames = state.stats.droppedVideoFrameCount,
)

private fun CaptureSource.toUiModel(): RecorderSourceUi = RecorderSourceUi(
    id = id.value,
    displayName = displayName,
    kind = when (this) {
        is CaptureSource.Screen -> RecorderSourceKind.Screen
        is CaptureSource.Monitor -> RecorderSourceKind.Monitor
        is CaptureSource.Region -> RecorderSourceKind.Region
        is CaptureSource.Window -> RecorderSourceKind.Window
        is CaptureSource.Application -> RecorderSourceKind.Application
    },
)

private fun AudioSource.Microphone.toUiModel(): RecorderMicrophoneUi = RecorderMicrophoneUi(
    id = id.value,
    displayName = displayName,
)

private fun AudioSource.SystemLoopback.toUiModel(): RecorderSystemAudioUi = RecorderSystemAudioUi(
    id = id.value,
    displayName = displayName,
)

private fun Map<AudioSourceId, AudioLevels>.levelFor(sourceId: String?): Float = sourceId
    ?.let(::AudioSourceId)
    ?.let(::get)
    ?.peak
    ?.toFloat()
    ?.coerceIn(0f, 1f)
    ?: 0f

private fun RecorderUiState.toRecorderPreferences(encoderSettings: EncoderSettings): DesktopRecorderPreferences =
    DesktopRecorderPreferences(
        frameRate = frameRate,
        captureCursor = captureCursor,
        showInputOverlay = showInputOverlay,
        showMouseTrail = showMouseTrail,
        recordMouseTrail = recordMouseTrail,
        replayDurationMinutes = replayDurationMinutes,
        storyboardMode = storyboardMode,
        encoderSettings = encoderSettings,
    )

private fun DesktopRecorderProfileSummary.toUiModel(): RecorderProfileUi = RecorderProfileUi(
    id = id,
    name = name,
)

private data class QueuedProfileSnapshot(
    val revision: Long,
    val snapshot: DesktopRecorderProfileSnapshot,
)

private fun storyboardOutputPath(inputVideoPath: String, mode: StoryboardMode): String {
    val video = Path.of(inputVideoPath)
    val fileName = video.fileName.toString()
    val baseName = fileName.substringBeforeLast('.', fileName)
    val outputName = when (mode) {
        StoryboardMode.SeparatePngFiles -> "$baseName-frames"
        StoryboardMode.ContactSheet -> "$baseName-storyboard.png"
    }
    return video.resolveSibling(outputName).toString()
}

private fun DesktopOutputPolicy.validationError(): String? = when {
    directory.isBlank() -> "Output directory must not be blank."
    fileNamePattern.isBlank() -> "Output file name pattern must not be blank."
    '/' in fileNamePattern || '\\' in fileNamePattern ->
        "Output file name pattern must not contain path separators."
    !fileNamePattern.endsWith(".mp4", ignoreCase = true) ->
        "Output file name pattern must use the .mp4 extension."
    else -> null
}

private fun Double?.toGainPercent(): Int = ((this ?: 1.0) * 100.0)
    .toInt()
    .coerceIn(MIN_AUDIO_GAIN_PERCENT, MAX_AUDIO_GAIN_PERCENT)

private fun Int.toAudioGain(): Double = toDouble() / 100.0

private enum class RegionSelectionFollowUp {
    None,
    StartRecording,
    TakeScreenshot,
}

private fun VideoFrame.toDesktopPreviewFrame(): DesktopPreviewFrame {
    require(pixelFormat == PixelFormat.Rgba8888 || pixelFormat == PixelFormat.Bgra8888) {
        "Preview requires RGBA8888 or BGRA8888 video frames."
    }
    val rowPixelBytes = width.toLong() * PREVIEW_BYTES_PER_PIXEL
    require(width > 0 && height > 0 && strideBytes.toLong() >= rowPixelBytes) {
        "Preview frame dimensions or stride are invalid."
    }
    val source = requireNotNull(pixelData) { "Preview frame does not contain pixels." }
    val requiredBytes = (height - 1).toLong() * strideBytes + rowPixelBytes
    require(requiredBytes <= source.size) { "Preview frame pixel payload is incomplete." }
    return DesktopPreviewFrame(
        width = width,
        height = height,
        pixelFormat = pixelFormat,
        strideBytes = strideBytes,
        pixelData = source.copyOf(),
    )
}

private const val PREVIEW_OUTPUT_PLACEHOLDER = "preview-does-not-create-output.mp4"
private const val SCREENSHOT_CAPTURE_TIMEOUT_MILLIS = 3_000L
private const val SHUTDOWN_TIMEOUT_MILLIS = 3_000L
private const val MAX_PREVIEW_FRAME_RATE = 5
private const val PREVIEW_BYTES_PER_PIXEL = 4
