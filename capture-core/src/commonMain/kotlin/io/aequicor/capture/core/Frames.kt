package io.aequicor.capture.core

@JvmInline
value class MediaTimestamp(val nanoseconds: Long) : Comparable<MediaTimestamp> {
    init {
        require(nanoseconds >= 0) { "Media timestamp must not be negative." }
    }

    override fun compareTo(other: MediaTimestamp): Int = nanoseconds.compareTo(other.nanoseconds)
}

data class VideoFrame(
    val timestamp: MediaTimestamp,
    val width: Int,
    val height: Int,
    val pixelFormat: PixelFormat,
    val strideBytes: Int,
    val sourceId: CaptureSourceId,
    val scaleFactor: Double = 1.0,
    val pixelData: ByteArray? = null,
)

data class AudioFrame(
    val timestamp: MediaTimestamp,
    val sampleRate: Int,
    val channelCount: Int,
    val sampleCount: Int,
    val sourceId: AudioSourceId,
    val sampleFormat: AudioSampleFormat = AudioSampleFormat.PcmS16Le,
    val audioData: ByteArray? = null,
)

enum class AudioSampleFormat(val bytesPerSample: Int) {
    PcmS16Le(bytesPerSample = 2),
    PcmFloat32Le(bytesPerSample = 4),
}
