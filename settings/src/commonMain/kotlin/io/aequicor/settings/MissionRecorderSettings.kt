package io.aequicor.settings

import io.aequicor.capture.core.AudioCodec
import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.CaptureCapabilities
import io.aequicor.capture.core.CaptureRegion
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.ContainerFormat
import io.aequicor.capture.core.EncoderSettings
import io.aequicor.capture.core.HardwareAccelerationMode
import io.aequicor.capture.core.PixelFormat
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.VideoCodec
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

const val CURRENT_SETTINGS_SCHEMA_VERSION: Int = 2

@Serializable
data class MissionRecorderSettings(
    val schemaVersion: Int = CURRENT_SETTINGS_SCHEMA_VERSION,
    val defaultProfileId: String,
    val profiles: List<RecordingProfileSettings>,
    val desktopUi: DesktopUiSettings = DesktopUiSettings(),
)

@Serializable
data class DesktopUiSettings(
    val miniController: MiniControllerSettings = MiniControllerSettings(),
    val storyboardLayout: StoryboardLayoutSetting = StoryboardLayoutSetting.SeparatePngFiles,
    /** Most recently opened or recorded videos, newest first, for the desktop storyboard workspace. */
    val recentEditorMediaPaths: List<String> = emptyList(),
    val globalHotkeysEnabled: Boolean = true,
    val globalHotkeys: GlobalHotkeySettings = GlobalHotkeySettings(),
    val showApplicationInRecording: Boolean = false,
    val showCaptureBorder: Boolean = true,
)

/** Persisted gestures for desktop global hotkey actions. */
@Serializable
data class GlobalHotkeySettings(
    val selectRegionAndStartRecording: GlobalHotkeyGestureSettings = GlobalHotkeyGestureSettings(
        key = GlobalHotkeyKeySetting.F7,
    ),
    val selectRegion: GlobalHotkeyGestureSettings = GlobalHotkeyGestureSettings(
        key = GlobalHotkeyKeySetting.F8,
    ),
    val toggleRecording: GlobalHotkeyGestureSettings = GlobalHotkeyGestureSettings(
        key = GlobalHotkeyKeySetting.F9,
    ),
    val togglePause: GlobalHotkeyGestureSettings = GlobalHotkeyGestureSettings(
        key = GlobalHotkeyKeySetting.F10,
    ),
    val saveReplay: GlobalHotkeyGestureSettings = GlobalHotkeyGestureSettings(
        key = GlobalHotkeyKeySetting.F11,
    ),
    val markImportantFrame: GlobalHotkeyGestureSettings = GlobalHotkeyGestureSettings(
        key = GlobalHotkeyKeySetting.F12,
    ),
)

/** Persisted platform-neutral global hotkey gesture. */
@Serializable
data class GlobalHotkeyGestureSettings(
    val modifiers: Set<GlobalHotkeyModifierSetting> = setOf(
        GlobalHotkeyModifierSetting.Control,
        GlobalHotkeyModifierSetting.Shift,
    ),
    val key: GlobalHotkeyKeySetting,
)

/** Modifier keys supported by desktop global hotkey adapters. */
@Serializable
enum class GlobalHotkeyModifierSetting {
    Alt,
    Control,
    Shift,
    Meta,
}

/** Stable key identifier persisted for a desktop global hotkey. */
@Serializable
@JvmInline
value class GlobalHotkeyKeySetting(val value: String) {
    companion object {
        val F7: GlobalHotkeyKeySetting = GlobalHotkeyKeySetting("F7")
        val F8: GlobalHotkeyKeySetting = GlobalHotkeyKeySetting("F8")
        val F9: GlobalHotkeyKeySetting = GlobalHotkeyKeySetting("F9")
        val F10: GlobalHotkeyKeySetting = GlobalHotkeyKeySetting("F10")
        val F11: GlobalHotkeyKeySetting = GlobalHotkeyKeySetting("F11")
        val F12: GlobalHotkeyKeySetting = GlobalHotkeyKeySetting("F12")
    }
}

@Serializable
enum class StoryboardLayoutSetting {
    SeparatePngFiles,
    ContactSheet,
}

@Serializable
data class MiniControllerSettings(
    val position: DesktopWindowPositionSettings? = null,
)

@Serializable
data class DesktopWindowPositionSettings(
    val x: Int,
    val y: Int,
)

@Serializable
data class RecordingProfileSettings(
    val id: String,
    val name: String,
    val source: CaptureSourceSettings,
    val output: OutputSettings = OutputSettings(),
    val video: VideoSettings = VideoSettings(),
    val audio: AudioSettings = AudioSettings(),
    val encoder: EncoderProfileSettings = EncoderProfileSettings(),
    val replay: ReplaySettings = ReplaySettings(),
    val export: ExportProfileSettings = ExportProfileSettings(),
)

@Serializable
data class CaptureSourceSettings(
    val type: CaptureSourceType = CaptureSourceType.Screen,
    val id: String = "screen:all",
    val displayName: String = "All screens",
    val monitorIndex: Int? = null,
    val region: CaptureRegionSettings? = null,
)

@Serializable
enum class CaptureSourceType {
    Screen,
    Monitor,
    Region,
    Window,
    Application,
}

@Serializable
data class CaptureRegionSettings(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val monitorId: String? = null,
    val scaleFactor: Double = 1.0,
)

@Serializable
data class OutputSettings(
    val directory: String = "recordings",
    val fileNamePattern: String = "mission-{timestamp}.mp4",
    val overwrite: Boolean = false,
)

@Serializable
data class VideoSettings(
    val frameRate: Int = 30,
    val captureCursor: Boolean = true,
    /** Opt-in profile setting for recording keyboard and mouse press labels in the video. */
    val showInputOverlay: Boolean = false,
    /** Opt-in profile setting for saving cursor movement for storyboard-only trails. */
    val recordMouseTrail: Boolean = false,
)

@Serializable
data class AudioSettings(
    val sources: List<AudioSourceSettings> = emptyList(),
)

@Serializable
data class AudioSourceSettings(
    val type: AudioSourceType,
    val id: String,
    val displayName: String,
    val sampleRate: Int = 48_000,
    val channelCount: Int = 2,
    val enabled: Boolean = true,
    val gain: Double = 1.0,
)

@Serializable
enum class AudioSourceType {
    Microphone,
    SystemLoopback,
}

@Serializable
data class EncoderProfileSettings(
    val container: ContainerFormatSetting = ContainerFormatSetting.Mp4,
    val videoCodec: VideoCodecSetting = VideoCodecSetting.H264,
    val audioCodec: AudioCodecSetting = AudioCodecSetting.Aac,
    val videoBitrateBitsPerSecond: Int = 24_000_000,
    val audioBitrateBitsPerSecond: Int = 192_000,
    val keyframeIntervalSeconds: Int = 2,
    val hardwareAcceleration: HardwareAccelerationSetting = HardwareAccelerationSetting.Auto,
    val pixelFormat: PixelFormatSetting = PixelFormatSetting.Bgra8888,
)

@Serializable
enum class ContainerFormatSetting {
    Mp4,
    WebM,
    Matroska,
}

@Serializable
enum class VideoCodecSetting {
    H264,
    H265,
    Vp9,
    Av1,
}

@Serializable
enum class AudioCodecSetting {
    Aac,
    Opus,
    Pcm,
}

@Serializable
enum class HardwareAccelerationSetting {
    Auto,
    Disabled,
    Required,
}

@Serializable
enum class PixelFormatSetting {
    Bgra8888,
    Rgba8888,
    Nv12,
}

@Serializable
data class ReplaySettings(
    val enabled: Boolean = false,
    val durationSeconds: Long = 300,
    val maxVideoFrames: Int? = null,
    val maxAudioFrames: Int? = null,
)

@Serializable
data class ExportProfileSettings(
    val imageFormat: ExportImageFormatSetting = ExportImageFormatSetting.Png,
    val targetFps: Int? = null,
    val overwrite: Boolean = false,
)

@Serializable
enum class ExportImageFormatSetting {
    Png,
    Jpeg,
}

object MissionRecorderSettingsFactory {
    fun defaultLocal(): MissionRecorderSettings =
        MissionRecorderSettings(
            defaultProfileId = "default",
            profiles = listOf(
                RecordingProfileSettings(
                    id = "default",
                    name = "Default local recording",
                    source = CaptureSourceSettings(),
                ),
            ),
        )
}

fun RecordingProfileSettings.toRecordingSettings(outputPath: String): RecordingSettings =
    RecordingSettings(
        captureSource = source.toCaptureSource(),
        audioSources = audio.sources.filter { it.enabled }.map { it.toAudioSource() },
        outputPath = outputPath,
        overwriteOutput = output.overwrite,
        frameRate = video.frameRate,
        captureCursor = video.captureCursor,
        showInputOverlay = video.showInputOverlay,
        recordMouseTrail = video.recordMouseTrail,
        replayDuration = replay.takeIf { it.enabled }?.durationSeconds?.seconds,
        encoder = encoder.toEncoderSettings(),
    )

private fun CaptureSourceSettings.toCaptureSource(): CaptureSource =
    when (type) {
        CaptureSourceType.Screen -> CaptureSource.Screen(
            id = CaptureSourceId(id),
            displayName = displayName,
            capabilities = CaptureCapabilities(supportsRegionCapture = true),
        )
        CaptureSourceType.Monitor -> CaptureSource.Monitor(
            id = CaptureSourceId(id),
            displayName = displayName,
            index = monitorIndex ?: 0,
            capabilities = CaptureCapabilities(supportsRegionCapture = true),
        )
        CaptureSourceType.Region -> {
            val regionSettings = requireNotNull(region) { "Region source requires region settings." }
            CaptureSource.Region(
                id = CaptureSourceId(id),
                displayName = displayName,
                region = CaptureRegion(
                    x = regionSettings.x,
                    y = regionSettings.y,
                    width = regionSettings.width,
                    height = regionSettings.height,
                    monitorId = regionSettings.monitorId?.let(::CaptureSourceId),
                    scaleFactor = regionSettings.scaleFactor,
                ),
            )
        }
        CaptureSourceType.Window -> CaptureSource.Window(
            id = CaptureSourceId(id),
            displayName = displayName,
        )
        CaptureSourceType.Application -> CaptureSource.Application(
            id = CaptureSourceId(id),
            displayName = displayName,
        )
    }

fun CaptureSource.toCaptureSourceSettings(): CaptureSourceSettings = when (this) {
    is CaptureSource.Screen -> CaptureSourceSettings(
        type = CaptureSourceType.Screen,
        id = id.value,
        displayName = displayName,
    )
    is CaptureSource.Monitor -> CaptureSourceSettings(
        type = CaptureSourceType.Monitor,
        id = id.value,
        displayName = displayName,
        monitorIndex = index,
    )
    is CaptureSource.Region -> CaptureSourceSettings(
        type = CaptureSourceType.Region,
        id = id.value,
        displayName = displayName,
        region = CaptureRegionSettings(
            x = region.x,
            y = region.y,
            width = region.width,
            height = region.height,
            monitorId = region.monitorId?.value,
            scaleFactor = region.scaleFactor,
        ),
    )
    is CaptureSource.Window -> CaptureSourceSettings(
        type = CaptureSourceType.Window,
        id = id.value,
        displayName = displayName,
    )
    is CaptureSource.Application -> CaptureSourceSettings(
        type = CaptureSourceType.Application,
        id = id.value,
        displayName = displayName,
    )
}

private fun AudioSourceSettings.toAudioSource(): AudioSource =
    when (type) {
        AudioSourceType.Microphone -> AudioSource.Microphone(
            id = AudioSourceId(id),
            displayName = displayName,
            sampleRate = sampleRate,
            channelCount = channelCount,
            gain = gain,
        )
        AudioSourceType.SystemLoopback -> AudioSource.SystemLoopback(
            id = AudioSourceId(id),
            displayName = displayName,
            sampleRate = sampleRate,
            channelCount = channelCount,
            gain = gain,
        )
    }

fun AudioSource.toAudioSourceSettings(enabled: Boolean = true): AudioSourceSettings = when (this) {
    is AudioSource.Microphone -> AudioSourceSettings(
        type = AudioSourceType.Microphone,
        id = id.value,
        displayName = displayName,
        sampleRate = sampleRate,
        channelCount = channelCount,
        enabled = enabled,
        gain = gain,
    )
    is AudioSource.SystemLoopback -> AudioSourceSettings(
        type = AudioSourceType.SystemLoopback,
        id = id.value,
        displayName = displayName,
        sampleRate = sampleRate,
        channelCount = channelCount,
        enabled = enabled,
        gain = gain,
    )
}

fun EncoderProfileSettings.toEncoderSettings(): EncoderSettings =
    EncoderSettings(
        container = when (container) {
            ContainerFormatSetting.Mp4 -> ContainerFormat.Mp4
            ContainerFormatSetting.WebM -> ContainerFormat.WebM
            ContainerFormatSetting.Matroska -> ContainerFormat.Matroska
        },
        videoCodec = when (videoCodec) {
            VideoCodecSetting.H264 -> VideoCodec.H264
            VideoCodecSetting.H265 -> VideoCodec.H265
            VideoCodecSetting.Vp9 -> VideoCodec.Vp9
            VideoCodecSetting.Av1 -> VideoCodec.Av1
        },
        audioCodec = when (audioCodec) {
            AudioCodecSetting.Aac -> AudioCodec.Aac
            AudioCodecSetting.Opus -> AudioCodec.Opus
            AudioCodecSetting.Pcm -> AudioCodec.Pcm
        },
        videoBitrateBitsPerSecond = videoBitrateBitsPerSecond,
        audioBitrateBitsPerSecond = audioBitrateBitsPerSecond,
        keyframeIntervalSeconds = keyframeIntervalSeconds,
        hardwareAcceleration = when (hardwareAcceleration) {
            HardwareAccelerationSetting.Auto -> HardwareAccelerationMode.Auto
            HardwareAccelerationSetting.Disabled -> HardwareAccelerationMode.Disabled
            HardwareAccelerationSetting.Required -> HardwareAccelerationMode.Required
        },
        pixelFormat = when (pixelFormat) {
            PixelFormatSetting.Bgra8888 -> PixelFormat.Bgra8888
            PixelFormatSetting.Rgba8888 -> PixelFormat.Rgba8888
            PixelFormatSetting.Nv12 -> PixelFormat.Nv12
        },
    )

fun EncoderSettings.toEncoderProfileSettings(): EncoderProfileSettings = EncoderProfileSettings(
    container = when (container) {
        ContainerFormat.Mp4 -> ContainerFormatSetting.Mp4
        ContainerFormat.WebM -> ContainerFormatSetting.WebM
        ContainerFormat.Matroska -> ContainerFormatSetting.Matroska
    },
    videoCodec = when (videoCodec) {
        VideoCodec.H264 -> VideoCodecSetting.H264
        VideoCodec.H265 -> VideoCodecSetting.H265
        VideoCodec.Vp9 -> VideoCodecSetting.Vp9
        VideoCodec.Av1 -> VideoCodecSetting.Av1
    },
    audioCodec = when (audioCodec) {
        AudioCodec.Aac -> AudioCodecSetting.Aac
        AudioCodec.Opus -> AudioCodecSetting.Opus
        AudioCodec.Pcm -> AudioCodecSetting.Pcm
    },
    videoBitrateBitsPerSecond = videoBitrateBitsPerSecond,
    audioBitrateBitsPerSecond = audioBitrateBitsPerSecond,
    keyframeIntervalSeconds = keyframeIntervalSeconds,
    hardwareAcceleration = when (hardwareAcceleration) {
        HardwareAccelerationMode.Auto -> HardwareAccelerationSetting.Auto
        HardwareAccelerationMode.Disabled -> HardwareAccelerationSetting.Disabled
        HardwareAccelerationMode.Required -> HardwareAccelerationSetting.Required
    },
    pixelFormat = when (pixelFormat) {
        PixelFormat.Bgra8888 -> PixelFormatSetting.Bgra8888
        PixelFormat.Rgba8888 -> PixelFormatSetting.Rgba8888
        PixelFormat.Nv12 -> PixelFormatSetting.Nv12
    },
)
