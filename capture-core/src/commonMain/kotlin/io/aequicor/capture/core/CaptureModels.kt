package io.aequicor.capture.core

import kotlin.time.Duration

@JvmInline
value class CaptureSourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "Capture source id must not be blank." }
    }
}

@JvmInline
value class RecordingSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Recording session id must not be blank." }
    }
}

data class CaptureCapabilities(
    val supportsCursorCapture: Boolean = true,
    val supportsRegionCapture: Boolean = false,
    val supportsWindowCapture: Boolean = false,
    val supportsApplicationAudioFilter: Boolean = false,
)

sealed interface CaptureSource {
    val id: CaptureSourceId
    val displayName: String
    val capabilities: CaptureCapabilities

    data class Screen(
        override val id: CaptureSourceId,
        override val displayName: String,
        override val capabilities: CaptureCapabilities = CaptureCapabilities(supportsRegionCapture = true),
    ) : CaptureSource

    data class Monitor(
        override val id: CaptureSourceId,
        override val displayName: String,
        val index: Int,
        override val capabilities: CaptureCapabilities = CaptureCapabilities(supportsRegionCapture = true),
    ) : CaptureSource

    data class Region(
        override val id: CaptureSourceId,
        override val displayName: String,
        val region: CaptureRegion,
        override val capabilities: CaptureCapabilities = CaptureCapabilities(),
    ) : CaptureSource

    data class Window(
        override val id: CaptureSourceId,
        override val displayName: String,
        override val capabilities: CaptureCapabilities = CaptureCapabilities(supportsWindowCapture = true),
    ) : CaptureSource

    data class Application(
        override val id: CaptureSourceId,
        override val displayName: String,
        override val capabilities: CaptureCapabilities = CaptureCapabilities(
            supportsWindowCapture = true,
            supportsApplicationAudioFilter = true,
        ),
    ) : CaptureSource
}

data class CaptureRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val monitorId: CaptureSourceId? = null,
    val scaleFactor: Double = 1.0,
    val coordinateSpace: CoordinateSpace = CoordinateSpace.PhysicalPixels,
)

enum class CoordinateSpace {
    PhysicalPixels,
    LogicalPixels,
}

@JvmInline
value class AudioSourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "Audio source id must not be blank." }
    }
}

sealed interface AudioSource {
    val id: AudioSourceId
    val displayName: String
    val gain: Double

    data class Microphone(
        override val id: AudioSourceId,
        override val displayName: String,
        val sampleRate: Int,
        val channelCount: Int,
        override val gain: Double = 1.0,
    ) : AudioSource

    data class SystemLoopback(
        override val id: AudioSourceId,
        override val displayName: String,
        val sampleRate: Int,
        val channelCount: Int,
        override val gain: Double = 1.0,
    ) : AudioSource
}

const val MAX_AUDIO_SOURCE_GAIN: Double = 2.0

data class AudioOutputFormat(
    val sampleRate: Int,
    val channelCount: Int,
)

fun RecordingSettings.audioOutputFormat(): AudioOutputFormat? = when (audioSources.size) {
    0 -> null
    1 -> when (val source = audioSources.single()) {
        is AudioSource.Microphone -> AudioOutputFormat(source.sampleRate, source.channelCount)
        is AudioSource.SystemLoopback -> AudioOutputFormat(source.sampleRate, source.channelCount)
    }
    else -> AudioOutputFormat(
        sampleRate = DEFAULT_MIXED_AUDIO_SAMPLE_RATE,
        channelCount = DEFAULT_MIXED_AUDIO_CHANNEL_COUNT,
    )
}

data class RecordingSettings(
    val captureSource: CaptureSource,
    val audioSources: List<AudioSource> = emptyList(),
    val outputPath: String,
    val overwriteOutput: Boolean = false,
    val frameRate: Int = 30,
    val captureCursor: Boolean = true,
    val replayDuration: Duration? = null,
    val encoder: EncoderSettings = EncoderSettings(),
)

data class EncoderSettings(
    val container: ContainerFormat = ContainerFormat.Mp4,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val audioCodec: AudioCodec = AudioCodec.Aac,
    val videoBitrateBitsPerSecond: Int = 8_000_000,
    val audioBitrateBitsPerSecond: Int = 192_000,
    val keyframeIntervalSeconds: Int = 2,
    val hardwareAcceleration: HardwareAccelerationMode = HardwareAccelerationMode.Auto,
    val pixelFormat: PixelFormat = PixelFormat.Bgra8888,
)

enum class ContainerFormat {
    Mp4,
    WebM,
    Matroska,
}

enum class VideoCodec {
    H264,
    H265,
    Vp9,
    Av1,
}

enum class AudioCodec {
    Aac,
    Opus,
    Pcm,
}

enum class HardwareAccelerationMode {
    Auto,
    Disabled,
    Required,
}

enum class PixelFormat {
    Bgra8888,
    Rgba8888,
    Nv12,
}

const val DEFAULT_MIXED_AUDIO_SAMPLE_RATE: Int = 48_000
const val DEFAULT_MIXED_AUDIO_CHANNEL_COUNT: Int = 2
