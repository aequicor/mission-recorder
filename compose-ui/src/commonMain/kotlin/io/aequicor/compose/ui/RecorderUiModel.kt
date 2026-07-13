package io.aequicor.compose.ui

enum class RecorderSourceKind {
    Screen,
    Monitor,
    Region,
    Window,
    Application,
}

data class RecorderSourceUi(
    val id: String,
    val displayName: String,
    val kind: RecorderSourceKind,
)

data class RecorderMicrophoneUi(
    val id: String,
    val displayName: String,
)

data class RecorderSystemAudioUi(
    val id: String,
    val displayName: String,
)

data class RecorderProfileUi(
    val id: String,
    val name: String,
)

enum class RecorderStatus {
    Idle,
    Preparing,
    Recording,
    Paused,
    Stopping,
    Completed,
    Failed,
}

enum class StoryboardMode {
    SeparatePngFiles,
    ContactSheet,
}

enum class ReplayUiStatus {
    Idle,
    Preparing,
    Buffering,
    Saving,
    Stopping,
    Failed,
}

enum class PreviewUiStatus {
    Idle,
    Preparing,
    Active,
    Failed,
}

data class RecorderUiState(
    val profiles: List<RecorderProfileUi> = listOf(RecorderProfileUi("default", "Default local recording")),
    val selectedProfileId: String = "default",
    val showCreateProfileDialog: Boolean = false,
    val showDeleteProfileDialog: Boolean = false,
    val isManagingProfiles: Boolean = false,
    val sources: List<RecorderSourceUi> = emptyList(),
    val selectedSourceId: String? = null,
    val microphones: List<RecorderMicrophoneUi> = emptyList(),
    val selectedMicrophoneId: String? = null,
    val systemAudioEnabled: Boolean = false,
    val systemAudioAvailable: Boolean = false,
    val systemAudioSources: List<RecorderSystemAudioUi> = emptyList(),
    val selectedSystemAudioId: String? = null,
    val microphoneLevel: Float = 0f,
    val systemAudioLevel: Float = 0f,
    val microphoneGainPercent: Int = DEFAULT_AUDIO_GAIN_PERCENT,
    val systemAudioGainPercent: Int = DEFAULT_AUDIO_GAIN_PERCENT,
    val microphoneMuted: Boolean = false,
    val systemAudioMuted: Boolean = false,
    val microphoneSolo: Boolean = false,
    val systemAudioSolo: Boolean = false,
    val outputPath: String = "",
    val overwriteOutput: Boolean = false,
    val outputDirectory: String = "recordings",
    val outputFileNamePattern: String = "mission-{timestamp}.mp4",
    val showOutputNamingDialog: Boolean = false,
    val frameRate: Int = 30,
    val captureCursor: Boolean = true,
    val showInputOverlay: Boolean = false,
    val showApplicationInRecording: Boolean = false,
    val showCaptureBorder: Boolean = true,
    val previewStatus: PreviewUiStatus = PreviewUiStatus.Idle,
    val videoBitrateMbps: Int = DEFAULT_VIDEO_BITRATE_MBPS,
    val status: RecorderStatus = RecorderStatus.Idle,
    val elapsedMilliseconds: Long = 0,
    val videoFrames: Long = 0,
    val audioFrames: Long = 0,
    val droppedFrames: Long = 0,
    val effectiveFramesPerSecond: Double = 0.0,
    val isRefreshingSources: Boolean = false,
    val isSelectingRegion: Boolean = false,
    val isChoosingOutputFile: Boolean = false,
    val lastOutputPath: String? = null,
    val storyboardInputPath: String = lastOutputPath.orEmpty(),
    val storyboardMode: StoryboardMode = StoryboardMode.SeparatePngFiles,
    val isExportingStoryboard: Boolean = false,
    val lastStoryboardPath: String? = null,
    val replayDurationMinutes: Int = 5,
    val replayStatus: ReplayUiStatus = ReplayUiStatus.Idle,
    val replayRetainedMilliseconds: Long = 0,
    val replayVideoFrames: Int = 0,
    val replayAudioFrames: Int = 0,
    val replayDroppedFrames: Long = 0,
    val lastReplayPath: String? = null,
    val errorMessage: String? = null,
) {
    val isRecording: Boolean
        get() = status == RecorderStatus.Recording

    val isPaused: Boolean
        get() = status == RecorderStatus.Paused

    val hasActiveRecording: Boolean
        get() = isRecording || isPaused

    val isBusy: Boolean
        get() = hasBlockingOperation

    private val hasBlockingOperation: Boolean
        get() = status == RecorderStatus.Preparing ||
            status == RecorderStatus.Recording ||
            status == RecorderStatus.Paused ||
            status == RecorderStatus.Stopping ||
            isExportingStoryboard ||
            isSelectingRegion ||
            isChoosingOutputFile ||
            isManagingProfiles ||
            isReplayActive

    val isPreviewRunning: Boolean
        get() = previewStatus == PreviewUiStatus.Preparing || previewStatus == PreviewUiStatus.Active

    val isReplayActive: Boolean
        get() = replayStatus == ReplayUiStatus.Preparing ||
            replayStatus == ReplayUiStatus.Buffering ||
            replayStatus == ReplayUiStatus.Saving ||
            replayStatus == ReplayUiStatus.Stopping

    val canStart: Boolean
        get() = !hasBlockingOperation && !isRefreshingSources && selectedSourceId != null && outputPath.isNotBlank()

    val canExportStoryboard: Boolean
        get() = !isBusy && !isRefreshingSources && storyboardInputPath.isNotBlank()

    val canStartReplay: Boolean
        get() = !hasBlockingOperation &&
            !isRefreshingSources &&
            selectedSourceId != null &&
            replayDurationMinutes > 0

    val canStartPreview: Boolean
        get() = previewStatus == PreviewUiStatus.Idle &&
            !hasBlockingOperation &&
            !isRefreshingSources &&
            selectedSourceId != null

    val canStopPreview: Boolean
        get() = isPreviewRunning

    val canSaveReplay: Boolean
        get() = replayStatus == ReplayUiStatus.Buffering && replayVideoFrames > 0

    val canStopReplay: Boolean
        get() = replayStatus == ReplayUiStatus.Buffering

    val canPauseRecording: Boolean
        get() = isRecording

    val canResumeRecording: Boolean
        get() = isPaused

    val canToggleMicrophoneMute: Boolean
        get() = selectedMicrophoneId != null

    val canToggleSystemAudioMute: Boolean
        get() = systemAudioAvailable && systemAudioEnabled

    val canToggleAudioSolo: Boolean
        get() = selectedMicrophoneId != null && systemAudioAvailable && systemAudioEnabled

    val canManageProfiles: Boolean
        get() = !isBusy && !isRefreshingSources

    val canDeleteProfile: Boolean
        get() = canManageProfiles && profiles.size > 1
}

sealed interface RecorderUiAction {
    data class SelectProfile(val profileId: String) : RecorderUiAction
    data class CreateProfile(val name: String) : RecorderUiAction
    data object ShowCreateProfileDialog : RecorderUiAction
    data object DismissCreateProfileDialog : RecorderUiAction
    data object ShowDeleteProfileDialog : RecorderUiAction
    data object DismissDeleteProfileDialog : RecorderUiAction
    data object DeleteSelectedProfile : RecorderUiAction
    data class SelectSource(val sourceId: String) : RecorderUiAction
    data class SelectMicrophone(val microphoneId: String?) : RecorderUiAction
    data class SetSystemAudioEnabled(val enabled: Boolean) : RecorderUiAction
    data class SelectSystemAudio(val sourceId: String) : RecorderUiAction
    data class SetMicrophoneMuted(val muted: Boolean) : RecorderUiAction
    data class SetSystemAudioMuted(val muted: Boolean) : RecorderUiAction
    data class SetMicrophoneSolo(val solo: Boolean) : RecorderUiAction
    data class SetSystemAudioSolo(val solo: Boolean) : RecorderUiAction
    data class SetMicrophoneGainPercent(val percent: Int) : RecorderUiAction
    data class SetSystemAudioGainPercent(val percent: Int) : RecorderUiAction
    data class SetOutputPath(val path: String) : RecorderUiAction
    data class SetOverwriteOutput(val enabled: Boolean) : RecorderUiAction
    data object ShowOutputNamingDialog : RecorderUiAction
    data object DismissOutputNamingDialog : RecorderUiAction
    data class ApplyOutputNaming(val directory: String, val fileNamePattern: String) : RecorderUiAction
    data class SetFrameRate(val frameRate: Int) : RecorderUiAction
    data class SetCaptureCursor(val enabled: Boolean) : RecorderUiAction
    data class SetShowInputOverlay(val enabled: Boolean) : RecorderUiAction
    data class SetShowApplicationInRecording(val enabled: Boolean) : RecorderUiAction
    data class SetShowCaptureBorder(val enabled: Boolean) : RecorderUiAction
    data class SetVideoBitrateMbps(val megabitsPerSecond: Int) : RecorderUiAction
    data class SetStoryboardInputPath(val path: String) : RecorderUiAction
    data class SetStoryboardMode(val mode: StoryboardMode) : RecorderUiAction
    data class SetReplayDurationMinutes(val minutes: Int) : RecorderUiAction
    data object SelectRegion : RecorderUiAction
    data object ChooseOutputFile : RecorderUiAction
    data object OpenRecordingsFolder : RecorderUiAction
    data object RefreshSources : RecorderUiAction
    data object StartRecording : RecorderUiAction
    data object StartPreview : RecorderUiAction
    data object StopPreview : RecorderUiAction
    data object PauseRecording : RecorderUiAction
    data object ResumeRecording : RecorderUiAction
    data object StopRecording : RecorderUiAction
    data object ExportStoryboard : RecorderUiAction
    data object StartReplayBuffer : RecorderUiAction
    data object SaveReplayBuffer : RecorderUiAction
    data object StopReplayBuffer : RecorderUiAction
    data object DismissError : RecorderUiAction
}

const val MIN_VIDEO_BITRATE_MBPS: Int = 2
const val MAX_VIDEO_BITRATE_MBPS: Int = 80
const val DEFAULT_VIDEO_BITRATE_MBPS: Int = 24
const val DEFAULT_AUDIO_GAIN_PERCENT: Int = 100
const val MIN_AUDIO_GAIN_PERCENT: Int = 0
const val MAX_AUDIO_GAIN_PERCENT: Int = 200
