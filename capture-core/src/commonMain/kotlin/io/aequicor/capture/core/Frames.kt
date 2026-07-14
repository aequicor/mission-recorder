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
    val nativeFrame: Any? = null,
    val importantFrame: Boolean = false,
    /** Cursor hotspot in this frame's pixel coordinate space, when the platform can provide it. */
    val cursorPosition: VideoFramePoint? = null,
    val lease: VideoFrameLease? = null,
)

/** A cursor position expressed in pixels relative to a [VideoFrame]. */
data class VideoFramePoint(
    val x: Int,
    val y: Int,
)

/**
 * Owns reusable storage backing a [VideoFrame]. The frame consumer must call [release]
 * after it has finished reading pixel data. Copies of a frame share the same lease.
 */
class VideoFrameLease(
    private val releaseAction: () -> Unit,
) {
    private var released: Boolean = false

    /** Returns the backing storage to its owner. Repeated calls have no effect. */
    fun release() {
        if (!released) {
            released = true
            releaseAction()
        }
    }
}

/** Releases reusable storage backing this frame, if any. */
fun VideoFrame.release() {
    lease?.release()
}

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
