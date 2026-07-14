package io.aequicor.media.desktop.ffmpeg

import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.InputEventFrameMarker
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.audioOutputFormat
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_GLOBAL_HEADER
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_from_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avformat_free_context
import org.bytedeco.ffmpeg.global.avformat.avformat_new_stream
import org.bytedeco.ffmpeg.global.avutil.av_buffer_ref
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NV12
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_hwframe_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_hwframe_transfer_data
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.nio.file.Path

internal class D3d11NvencRecorder(
    private val output: Path,
    private val settings: RecordingSettings,
    firstFrame: AVFrame,
    private val width: Int,
    private val height: Int,
) {
    private val codecContext: AVCodecContext
    private val inputFormatContext: AVFormatContext
    private val packet: AVPacket
    private val muxer: FFmpegFrameRecorder
    private var lastVideoPts: Long? = null
    private var closed = false

    init {
        val codec = avcodec_find_encoder_by_name(NVENC_ENCODER)
            ?: throw encoderFailed("NVENC is unavailable for D3D11 zero-copy encoding.")
        codecContext = avcodec_alloc_context3(codec)
            ?: throw encoderFailed("FFmpeg could not allocate the NVENC context.")
        inputFormatContext = avformat_alloc_context()
            ?: run {
                avcodec_free_context(codecContext)
                throw encoderFailed("FFmpeg could not allocate the MP4 input context.")
            }
        packet = av_packet_alloc()
            ?: run {
                avformat_free_context(inputFormatContext)
                avcodec_free_context(codecContext)
                throw encoderFailed("FFmpeg could not allocate an encoded packet.")
            }
        var createdMuxer: FFmpegFrameRecorder? = null
        try {
            openCodec(codecContext, firstFrame)
            val stream = avformat_new_stream(inputFormatContext, null)
                ?: throw encoderFailed("FFmpeg could not create the zero-copy video stream.")
            checkFfmpeg(avcodec_parameters_from_context(stream.codecpar(), codecContext), "copy NVENC stream parameters")
            stream.time_base().num(1).den(settings.frameRate)
            stream.avg_frame_rate().num(settings.frameRate).den(1)
            stream.r_frame_rate().num(settings.frameRate).den(1)

            createdMuxer = FFmpegFrameRecorder(
                output.toFile(),
                width,
                height,
                settings.audioOutputFormat()?.channelCount ?: 0,
            ).apply {
                format = "mp4"
                frameRate = settings.frameRate.toDouble()
                videoBitrate = settings.encoder.videoBitrateBitsPerSecond
                setInterleaved(true)
                settings.audioOutputFormat()?.let { audioFormat ->
                    audioCodec = org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
                    audioBitrate = settings.encoder.audioBitrateBitsPerSecond
                    sampleRate = audioFormat.sampleRate
                    audioChannels = audioFormat.channelCount
                }
            }
            val startedMuxer = requireNotNull(createdMuxer)
            startedMuxer.start(inputFormatContext)
            muxer = startedMuxer
            createdMuxer = null
        } catch (throwable: Throwable) {
            runCatching { createdMuxer?.release() }
            releaseNativeState()
            throw throwable
        }
    }

    fun writeVideoFrame(
        frame: AVFrame,
        timestampNanoseconds: Long,
        importantFrame: Boolean = false,
    ) {
        check(!closed) { "Zero-copy recorder is closed." }
        if (frame.width() != width || frame.height() != height || frame.format() != codecContext.pix_fmt()) {
            throw encoderFailed("Native D3D11 video frame format changed during recording.")
        }
        val requestedPts = timestampNanoseconds.coerceAtLeast(0) * settings.frameRate / NANOS_PER_SECOND
        val pts = maxOf(requestedPts, (lastVideoPts ?: -1L) + 1L)
        val encodedFrame = if (importantFrame) frame.withImportantFrameMarker() else frame
        encodedFrame.pts(pts)
        try {
            checkFfmpeg(avcodec_send_frame(codecContext, encodedFrame), "submit a D3D11 frame to NVENC")
        } finally {
            if (encodedFrame !== frame) av_frame_free(encodedFrame)
        }
        drainPackets()
        lastVideoPts = pts
    }

    fun writeAudioFrame(frame: AudioFrame) {
        muxer.recordPcmFrame(
            frame = frame,
            timestampMicros = frame.timestamp.nanoseconds / NANOS_PER_MICROSECOND,
        )
    }

    fun finish() {
        if (closed) return
        try {
            checkFfmpeg(avcodec_send_frame(codecContext, null), "flush NVENC")
            drainPackets()
            if (settings.audioOutputFormat() != null) muxer.flushPcmFrames()
            muxer.stop()
        } finally {
            closed = true
            runCatching { muxer.release() }
            releaseNativeState()
        }
    }

    fun cancel() {
        if (closed) return
        closed = true
        runCatching { muxer.release() }
        releaseNativeState()
    }

    private fun openCodec(context: AVCodecContext, firstFrame: AVFrame) {
        val hardwareFrames = firstFrame.hw_frames_ctx()
        if (hardwareFrames == null || hardwareFrames.isNull) {
            throw encoderFailed("Desktop Duplication returned a D3D11 frame without a hardware context.")
        }
        context.width(width)
        context.height(height)
        context.pix_fmt(firstFrame.format())
        context.time_base().num(1).den(settings.frameRate)
        context.framerate().num(settings.frameRate).den(1)
        context.bit_rate(settings.encoder.videoBitrateBitsPerSecond.toLong())
        context.gop_size(settings.frameRate * settings.encoder.keyframeIntervalSeconds)
        context.flags(context.flags() or AV_CODEC_FLAG_GLOBAL_HEADER)
        context.hw_frames_ctx(av_buffer_ref(hardwareFrames))

        val options = AVDictionary(null)
        try {
            av_dict_set(options, "preset", "p5", 0)
            av_dict_set(options, "tune", "hq", 0)
            av_dict_set(options, "rc", "vbr", 0)
            av_dict_set(options, "cq", "14", 0)
            av_dict_set(options, "profile", "high", 0)
            checkFfmpeg(avcodec_open2(context, context.codec(), options), "open NVENC")
        } finally {
            av_dict_free(options)
        }
    }

    private fun drainPackets() {
        while (avcodec_receive_packet(codecContext, packet) >= 0) {
            try {
                packet.stream_index(VIDEO_STREAM_INDEX)
                muxer.recordPacket(packet)
            } finally {
                av_packet_unref(packet)
            }
        }
    }

    private fun releaseNativeState() {
        av_packet_free(packet)
        avformat_free_context(inputFormatContext)
        avcodec_free_context(codecContext)
    }

    private fun AVFrame.withImportantFrameMarker(): AVFrame {
        val softwareFrame = av_frame_alloc() ?: throw encoderFailed("FFmpeg could not allocate an NV12 marker frame.")
        val markedFrame = av_frame_alloc() ?: run {
            av_frame_free(softwareFrame)
            throw encoderFailed("FFmpeg could not allocate a marked D3D11 frame.")
        }
        try {
            softwareFrame.format(AV_PIX_FMT_NV12)
            softwareFrame.width(width)
            softwareFrame.height(height)
            checkFfmpeg(av_frame_get_buffer(softwareFrame, FRAME_BUFFER_ALIGNMENT), "allocate an NV12 marker buffer")
            checkFfmpeg(av_hwframe_transfer_data(softwareFrame, this, 0), "download an important D3D11 frame")
            softwareFrame.paintImportantFrameMarkerNv12()
            checkFfmpeg(av_hwframe_get_buffer(hw_frames_ctx(), markedFrame, 0), "allocate a marked D3D11 frame")
            checkFfmpeg(av_hwframe_transfer_data(markedFrame, softwareFrame, 0), "upload an important D3D11 frame")
            return markedFrame
        } catch (throwable: Throwable) {
            av_frame_free(markedFrame)
            throw throwable
        } finally {
            av_frame_free(softwareFrame)
        }
    }

    private fun AVFrame.paintImportantFrameMarkerNv12() {
        val luminance = data(0) ?: throw encoderFailed("NV12 marker frame has no luminance plane.")
        val chroma = data(1) ?: throw encoderFailed("NV12 marker frame has no chroma plane.")
        val yStride = linesize(0)
        val uvStride = linesize(1)
        repeat(InputEventFrameMarker.ROW_COUNT) { row ->
            repeat(InputEventFrameMarker.COLUMN_COUNT) { column ->
                val color = if (InputEventFrameMarker.isAccentCell(row, column)) MARKER_ACCENT else MARKER_BACKGROUND
                val originX = InputEventFrameMarker.MARGIN_PIXELS + column * InputEventFrameMarker.CELL_SIZE_PIXELS
                val originY = InputEventFrameMarker.MARGIN_PIXELS + row * InputEventFrameMarker.CELL_SIZE_PIXELS
                repeat(InputEventFrameMarker.CELL_SIZE_PIXELS) { y ->
                    repeat(InputEventFrameMarker.CELL_SIZE_PIXELS) { x ->
                        luminance.put((originY + y).toLong() * yStride + originX + x, color.y.toByte())
                    }
                }
                repeat(InputEventFrameMarker.CELL_SIZE_PIXELS / 2) { y ->
                    repeat(InputEventFrameMarker.CELL_SIZE_PIXELS / 2) { x ->
                        val offset = (originY / 2 + y).toLong() * uvStride + originX + x * 2
                        chroma.put(offset, color.u.toByte())
                        chroma.put(offset + 1, color.v.toByte())
                    }
                }
            }
        }
    }
}

private data class Nv12Color(val y: Int, val u: Int, val v: Int)

private fun checkFfmpeg(result: Int, operation: String) {
    if (result < 0) throw encoderFailed("FFmpeg failed to $operation (error $result).")
}

internal fun isNvencDeviceAvailable(): Boolean {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return false
    val codec = avcodec_find_encoder_by_name(NVENC_ENCODER) ?: return false
    val context = avcodec_alloc_context3(codec) ?: return false
    return try {
        context.width(NVENC_PROBE_SIZE)
        context.height(NVENC_PROBE_SIZE)
        context.pix_fmt(AV_PIX_FMT_NV12)
        context.time_base().num(1).den(30)
        context.framerate().num(30).den(1)
        avcodec_open2(context, codec, null as AVDictionary?) >= 0
    } finally {
        avcodec_free_context(context)
    }
}

private const val NVENC_ENCODER = "h264_nvenc"
private const val VIDEO_STREAM_INDEX = 0
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val NANOS_PER_MICROSECOND = 1_000L
private const val NVENC_PROBE_SIZE = 256
private const val FRAME_BUFFER_ALIGNMENT = 32
private val MARKER_ACCENT = Nv12Color(y = 113, u = 116, v = 208)
private val MARKER_BACKGROUND = Nv12Color(y = 29, u = 131, v = 126)
