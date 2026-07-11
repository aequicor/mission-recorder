package io.aequicor.cli

sealed interface CliCommand {
    data object Help : CliCommand
    data class ListSources(val json: Boolean) : CliCommand
    data class ListAudio(val json: Boolean) : CliCommand
    data class Record(val target: RecordTarget, val options: RecordOptions) : CliCommand
    data class Control(
        val action: RecordingControlAction,
        val endpointPath: String,
        val outputPath: String? = null,
        val json: Boolean,
    ) : CliCommand
    data class ReplayStart(val duration: String, val json: Boolean) : CliCommand
    data class ReplaySave(val outputPath: String, val json: Boolean) : CliCommand
    data class ReplayRun(val target: RecordTarget, val options: ReplayRunOptions) : CliCommand
    data class ExportFrames(val options: ExportFramesOptions) : CliCommand
    data class Settings(val action: SettingsAction) : CliCommand
}

sealed interface RecordTarget {
    data object Screen : RecordTarget
    data object Profile : RecordTarget
    data class Monitor(val id: String) : RecordTarget
    data class Region(val x: Int, val y: Int, val width: Int, val height: Int) : RecordTarget
    data class Window(val id: String) : RecordTarget
    data class Application(val id: String) : RecordTarget
}

data class RecordOptions(
    val outputPath: String? = null,
    val fps: Int? = null,
    val captureCursor: Boolean? = null,
    val microphone: String? = null,
    val microphoneGainPercent: Int? = null,
    val systemAudio: Boolean = false,
    val systemAudioEndpoint: String? = null,
    val systemAudioGainPercent: Int? = null,
    val duration: String? = null,
    val settingsPath: String? = null,
    val profileId: String? = null,
    val overwriteOutput: Boolean = false,
    val controlEndpointPath: String? = null,
    val json: Boolean = false,
)

enum class RecordingControlAction {
    Status,
    Pause,
    Resume,
    Save,
    Stop,
}

data class RecordingControlRequest(
    val action: RecordingControlAction,
    val outputPath: String? = null,
)

data class ReplayRunOptions(
    val bufferDuration: String,
    val runDuration: String? = null,
    val outputPath: String,
    val fps: Int? = null,
    val captureCursor: Boolean? = null,
    val microphone: String? = null,
    val microphoneGainPercent: Int? = null,
    val systemAudio: Boolean = false,
    val systemAudioEndpoint: String? = null,
    val systemAudioGainPercent: Int? = null,
    val controlEndpointPath: String? = null,
    val json: Boolean = false,
)

data class ExportFramesOptions(
    val inputPath: String,
    val outputDirectory: String,
    val fps: Int? = null,
    val interval: String? = null,
    val imageFormat: String = "png",
    val layout: FrameExportLayout = FrameExportLayout.SeparatePngFiles,
    val overwrite: Boolean = false,
    val json: Boolean = false,
)

enum class FrameExportLayout {
    SeparatePngFiles,
    ContactSheet,
}

sealed interface SettingsAction {
    val path: String
    val json: Boolean

    data class Init(
        override val path: String,
        val force: Boolean,
        override val json: Boolean,
    ) : SettingsAction

    data class Validate(
        override val path: String,
        override val json: Boolean,
    ) : SettingsAction

    data class Show(
        override val path: String,
        override val json: Boolean,
    ) : SettingsAction
}

enum class CliExitCode(val code: Int) {
    Success(0),
    Usage(64),
    Validation(65),
    Unsupported(78),
    Failure(1),
}

sealed interface CliParseResult {
    data class Parsed(val command: CliCommand) : CliParseResult
    data class Invalid(val message: String) : CliParseResult
}

interface RecordingCommandBackend {
    suspend fun record(command: CliCommand.Record): RecordingCommandResult
}

sealed interface RecordingCommandResult {
    data class Completed(
        val outputPath: String,
        val videoFrames: Long,
        val audioFrames: Long,
    ) : RecordingCommandResult

    data class Rejected(val message: String) : RecordingCommandResult
    data class Unsupported(val message: String) : RecordingCommandResult
    data class Failed(val message: String) : RecordingCommandResult
}

data object UnsupportedRecordingCommandBackend : RecordingCommandBackend {
    override suspend fun record(command: CliCommand.Record): RecordingCommandResult =
        RecordingCommandResult.Unsupported("record is parsed, but no recording backend is wired yet.")
}

interface RecordingControlCommandBackend {
    suspend fun control(command: CliCommand.Control): RecordingControlCommandResult
}

data class RecordingControlStatus(
    val state: String,
    val outputPath: String?,
    val durationMilliseconds: Long,
    val videoFrames: Long,
    val audioFrames: Long,
    val droppedFrames: Long = 0,
) {
    val effectiveFramesPerSecond: Double
        get() = if (durationMilliseconds > 0) {
            videoFrames * 1_000.0 / durationMilliseconds
        } else {
            0.0
        }
}

sealed interface RecordingControlCommandResult {
    data class Completed(
        val action: RecordingControlAction,
        val status: RecordingControlStatus,
        val message: String? = null,
    ) : RecordingControlCommandResult

    data class Rejected(val message: String) : RecordingControlCommandResult
    data class Failed(val message: String) : RecordingControlCommandResult
}

data object UnsupportedRecordingControlCommandBackend : RecordingControlCommandBackend {
    override suspend fun control(command: CliCommand.Control): RecordingControlCommandResult =
        RecordingControlCommandResult.Failed("No local recording control backend is wired.")
}

interface ReplayCommandBackend {
    suspend fun run(command: CliCommand.ReplayRun): ReplayCommandResult
}

sealed interface ReplayCommandResult {
    data class Completed(
        val outputPath: String,
        val videoFrames: Long,
        val audioFrames: Long,
        val durationMilliseconds: Long,
    ) : ReplayCommandResult

    data class Rejected(val message: String) : ReplayCommandResult
    data class Unsupported(val message: String) : ReplayCommandResult
    data class Failed(val message: String) : ReplayCommandResult
}

data object UnsupportedReplayCommandBackend : ReplayCommandBackend {
    override suspend fun run(command: CliCommand.ReplayRun): ReplayCommandResult =
        ReplayCommandResult.Unsupported("replay run is parsed, but no replay backend is wired yet.")
}

interface ExportFramesCommandBackend {
    suspend fun exportFrames(command: CliCommand.ExportFrames): ExportFramesCommandResult
}

sealed interface ExportFramesCommandResult {
    data class Completed(
        val outputDirectory: String,
        val sourceFrames: Int,
        val exportedFrames: Int,
        val imageFormat: String,
    ) : ExportFramesCommandResult

    data class Rejected(val message: String) : ExportFramesCommandResult
    data class Unsupported(val message: String) : ExportFramesCommandResult
    data class Failed(val message: String) : ExportFramesCommandResult
}

data object UnsupportedExportFramesCommandBackend : ExportFramesCommandBackend {
    override suspend fun exportFrames(command: CliCommand.ExportFrames): ExportFramesCommandResult =
        ExportFramesCommandResult.Unsupported("export-frames is parsed, but no export backend is wired yet.")
}

interface SettingsCommandBackend {
    suspend fun handle(command: CliCommand.Settings): SettingsCommandResult
}

sealed interface SettingsCommandResult {
    data class Initialized(val path: String, val profileCount: Int) : SettingsCommandResult
    data class Valid(val path: String, val profileCount: Int) : SettingsCommandResult
    data class Shown(val path: String, val settingsJson: String) : SettingsCommandResult
    data class Rejected(val message: String) : SettingsCommandResult
    data class Failed(val message: String) : SettingsCommandResult
}

data object UnsupportedSettingsCommandBackend : SettingsCommandBackend {
    override suspend fun handle(command: CliCommand.Settings): SettingsCommandResult =
        SettingsCommandResult.Rejected("settings command is parsed, but no settings backend is wired yet.")
}
