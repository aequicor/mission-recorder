package io.aequicor.media.desktop.ffmpeg

import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.AudioCodec
import io.aequicor.capture.core.AudioSampleFormat
import io.aequicor.capture.core.ContainerFormat
import io.aequicor.capture.core.HardwareAccelerationMode
import io.aequicor.capture.core.PixelFormat
import io.aequicor.capture.core.RecordingError
import io.aequicor.capture.core.RecordingException
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.VideoFrame
import io.aequicor.capture.core.VideoCodec
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

internal class RgbaFrameBuffer(
    private val width: Int,
    private val height: Int,
    private val pixelFormat: PixelFormat,
) : AutoCloseable {
    private val frame = Frame(width, height, Frame.DEPTH_UBYTE, RGBA_BYTES_PER_PIXEL)
    private val pixels = (frame.image[0] as ByteBuffer).duplicate()
    private var sourceWidth = 0
    private var sourceHeight = 0

    init {
        require(width > 0 && height > 0)
        require(frame.imageStride >= width * RGBA_BYTES_PER_PIXEL)
        fillOpaqueBlack()
    }

    fun copyFrom(source: VideoFrame): Frame {
        source.validateVideoFrame()
        require(source.pixelFormat == pixelFormat) {
            "Pixel format changed while reusing the FFmpeg input buffer."
        }
        require(source.width <= width && source.height <= height)
        if (sourceWidth == 0 && sourceHeight == 0) {
            sourceWidth = source.width
            sourceHeight = source.height
        } else {
            require(source.width == sourceWidth && source.height == sourceHeight) {
                "RGBA frame dimensions changed while reusing the FFmpeg input buffer."
            }
        }
        val sourcePixels = requireNotNull(source.pixelData)
        val target = pixels.duplicate()
        repeat(source.height) { y ->
            target.position(y * frame.imageStride)
            target.put(sourcePixels, y * source.strideBytes, source.width * RGBA_BYTES_PER_PIXEL)
        }
        return frame
    }

    override fun close() {
        frame.close()
    }

    private fun fillOpaqueBlack() {
        repeat(height) { y ->
            repeat(width) { x ->
                val offset = y * frame.imageStride + x * RGBA_BYTES_PER_PIXEL
                pixels.put(offset, 0)
                pixels.put(offset + 1, 0)
                pixels.put(offset + 2, 0)
                pixels.put(offset + 3, 0xff.toByte())
            }
        }
    }
}

internal fun Frame.bgraToBufferedImage(): BufferedImage {
    require(imageDepth == Frame.DEPTH_UBYTE && imageChannels == RGBA_BYTES_PER_PIXEL) {
        "Decoded frame must use 8-bit BGRA pixels."
    }
    val source = (image[0] as ByteBuffer).duplicate()
    val result = BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB)
    repeat(imageHeight) { y ->
        repeat(imageWidth) { x ->
            val offset = y * imageStride + x * RGBA_BYTES_PER_PIXEL
            val blue = source.get(offset).toInt() and 0xff
            val green = source.get(offset + 1).toInt() and 0xff
            val red = source.get(offset + 2).toInt() and 0xff
            val alpha = source.get(offset + 3).toInt() and 0xff
            result.setRGB(x, y, (alpha shl 24) or (red shl 16) or (green shl 8) or blue)
        }
    }
    return result
}

internal fun VideoFrame.validateVideoFrame() {
    if (pixelFormat != PixelFormat.Rgba8888 && pixelFormat != PixelFormat.Bgra8888) {
        throw encoderFailed("Desktop FFmpeg encoder expects RGBA8888 or BGRA8888 video frames.")
    }
    if (width <= 0 || height <= 0 || strideBytes < width * RGBA_BYTES_PER_PIXEL) {
        throw encoderFailed("Video frame dimensions or stride are invalid.")
    }
    val requiredBytes = (height - 1).toLong() * strideBytes + width * RGBA_BYTES_PER_PIXEL
    val pixels = pixelData
    if (pixels == null || pixels.size.toLong() < requiredBytes) {
        throw encoderFailed("Video frame does not contain enough RGBA pixel data.")
    }
}

internal fun PixelFormat.toFfmpegPixelFormat(): Int = when (this) {
    PixelFormat.Rgba8888 -> AV_PIX_FMT_RGBA
    PixelFormat.Bgra8888 -> AV_PIX_FMT_BGRA
    PixelFormat.Nv12 -> throw encoderFailed("Desktop FFmpeg encoder does not accept NV12 input frames.")
}

internal fun validateDesktopFfmpegSettings(settings: RecordingSettings) {
    if (settings.encoder.container != ContainerFormat.Mp4 || settings.encoder.videoCodec != VideoCodec.H264) {
        throw encoderFailed("Desktop FFmpeg media path currently supports MP4 with H.264 video only.")
    }
    if (settings.encoder.audioCodec != AudioCodec.Aac) {
        throw encoderFailed("Desktop FFmpeg media path currently supports AAC audio only.")
    }
}

internal fun h264EncoderCandidates(
    mode: HardwareAccelerationMode,
    osName: String = System.getProperty("os.name").orEmpty(),
): List<String> {
    val hardwareEncoders = when {
        osName.startsWith("Windows", ignoreCase = true) ->
            listOf("h264_nvenc", "h264_qsv", "h264_mf")
        osName.startsWith("Mac", ignoreCase = true) ->
            listOf("h264_videotoolbox")
        else -> listOf("h264_nvenc", "h264_qsv")
    }
    return when (mode) {
        HardwareAccelerationMode.Auto -> hardwareEncoders + SOFTWARE_H264_ENCODER
        HardwareAccelerationMode.Disabled -> listOf(SOFTWARE_H264_ENCODER)
        HardwareAccelerationMode.Required -> hardwareEncoders
    }
}

internal fun FFmpegFrameRecorder.configureOpenH264BitrateControl() {
    videoCodecName = SOFTWARE_H264_ENCODER
    setVideoOption("rc_mode", "bitrate")
    setVideoOption("allow_skip_frames", "false")
}

internal fun validateAudioFrame(frame: AudioFrame) {
    if (frame.sampleRate <= 0 || frame.channelCount <= 0 || frame.sampleCount <= 0) {
        throw encoderFailed("Audio frame sample rate, channel count, and sample count must be positive.")
    }
    val audioData = frame.audioData ?: throw encoderFailed("Audio frame does not contain PCM data.")
    val expectedBytes = frame.sampleCount.toLong() * frame.channelCount * frame.sampleFormat.bytesPerSample
    if (expectedBytes > Int.MAX_VALUE || audioData.size != expectedBytes.toInt()) {
        throw encoderFailed("Audio PCM payload size does not match frame metadata.")
    }
}

internal fun FFmpegFrameRecorder.recordPcmFrame(frame: AudioFrame, timestampMicros: Long) {
    validateAudioFrame(frame)
    if (audioChannels != frame.channelCount) {
        throw encoderFailed("Audio channel count changed during recording.")
    }
    val bytes = ByteBuffer.wrap(requireNotNull(frame.audioData)).order(ByteOrder.LITTLE_ENDIAN)
    timestamp = timestampMicros.coerceAtLeast(0)
    when (frame.sampleFormat) {
        AudioSampleFormat.PcmS16Le -> {
            val source = bytes.asShortBuffer()
            val samples = ShortArray(source.remaining())
            source.get(samples)
            recordSamples(frame.sampleRate, frame.channelCount, ShortBuffer.wrap(samples))
        }
        AudioSampleFormat.PcmFloat32Le -> {
            val source = bytes.asFloatBuffer()
            val samples = FloatArray(source.remaining())
            source.get(samples)
            recordSamples(frame.sampleRate, frame.channelCount, FloatBuffer.wrap(samples))
        }
    }
}

internal fun FFmpegFrameRecorder.flushPcmFrames() {
    while (FfmpegRecorderSupport.flushAudio(this)) {
        // FFmpeg may emit more than one delayed AAC packet.
    }
}

internal fun encoderFailed(message: String): RecordingException =
    RecordingException(RecordingError.EncoderFailed(message))

internal fun Int.roundUpToEven(): Int = this + (this and 1)

private const val RGBA_BYTES_PER_PIXEL = 4
private const val SOFTWARE_H264_ENCODER = "libopenh264"
