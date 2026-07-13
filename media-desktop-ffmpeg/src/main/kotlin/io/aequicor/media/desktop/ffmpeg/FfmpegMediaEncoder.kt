package io.aequicor.media.desktop.ffmpeg

import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.MediaEncoder
import io.aequicor.capture.core.RecordingException
import io.aequicor.capture.core.RecordingMetrics
import io.aequicor.capture.core.RecordingOutput
import io.aequicor.capture.core.RecordingSession
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.VideoFrame
import io.aequicor.capture.core.audioOutputFormat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.name

class FfmpegMediaEncoder : MediaEncoder {
    private val mutex = Mutex()
    private var context: EncoderContext? = null

    override suspend fun open(session: RecordingSession, settings: RecordingSettings) = mutex.withLock {
        if (context != null) {
            throw encoderFailed("Encoder is already open.")
        }
        validateDesktopFfmpegSettings(settings)
        val output = Path.of(settings.outputPath).toAbsolutePath().normalize()
        if (output.extension.lowercase() != "mp4") {
            throw encoderFailed("Desktop FFmpeg encoder currently writes MP4 files only.")
        }
        if (output.exists() && !settings.overwriteOutput) {
            throw encoderFailed("Output path already exists: $output")
        }
        output.parent?.createDirectories()
        val temporary = output.resolveSibling("${output.name}.tmp-${session.id.value}.mp4")
        if (temporary.exists()) {
            throw encoderFailed("Temporary output path already exists: $temporary")
        }
        context = EncoderContext(
            output = output,
            temporary = temporary,
            settings = settings,
        )
    }

    override suspend fun writeVideoFrame(frame: VideoFrame) = mutex.withLock {
        val current = context ?: throw encoderFailed("Encoder is not open.")
        try {
            val recorder = current.recorder ?: startRecorder(current, frame).also { started ->
                current.recorder = started
                current.pendingAudioFrames.sortedBy { it.timestamp }.forEach { pending ->
                    started.recordPcmFrame(
                        frame = pending,
                        timestampMicros = pending.timestamp.nanoseconds / NANOS_PER_MICROSECOND,
                    )
                }
                current.pendingAudioFrames.clear()
            }
            if (frame.width != current.width || frame.height != current.height) {
                throw encoderFailed("Video frame dimensions changed during recording.")
            }
            val timestampMicros = monotonicVideoTimestampMicros(
                requestedTimestampMicros = frame.timestamp.nanoseconds / NANOS_PER_MICROSECOND,
                previousTimestampMicros = current.lastVideoTimestampMicros,
                frameRate = current.settings.frameRate,
            )
            recorder.timestamp = timestampMicros
            recorder.record(
                requireNotNull(current.rgbaFrameBuffer).copyFrom(frame),
                frame.pixelFormat.toFfmpegPixelFormat(),
            )
            current.lastVideoTimestampMicros = timestampMicros
        } catch (recording: RecordingException) {
            throw recording
        } catch (throwable: Throwable) {
            throw encoderFailed(throwable.message ?: "FFmpeg failed to encode a video frame.")
        }
    }

    override suspend fun writeAudioFrame(frame: AudioFrame) = mutex.withLock {
        val current = context ?: throw encoderFailed("Encoder is not open.")
        validateAudioFrame(frame)
        try {
            val recorder = current.recorder
            if (recorder == null) {
                current.pendingAudioFrames += frame.copy(audioData = frame.audioData?.copyOf())
            } else {
                recorder.recordPcmFrame(
                    frame = frame,
                    timestampMicros = frame.timestamp.nanoseconds / NANOS_PER_MICROSECOND,
                )
            }
        } catch (recording: RecordingException) {
            throw recording
        } catch (throwable: Throwable) {
            throw encoderFailed(throwable.message ?: "FFmpeg failed to encode an audio frame.")
        }
    }

    override suspend fun finish(session: RecordingSession, metrics: RecordingMetrics): RecordingOutput = mutex.withLock {
        val current = context ?: throw encoderFailed("Encoder is not open.")
        val recorder = current.recorder ?: run {
            cleanup(current)
            context = null
            throw encoderFailed("Recording does not contain video frames.")
        }
        try {
            recorder.stop()
            recorder.release()
            current.rgbaFrameBuffer?.close()
            current.rgbaFrameBuffer = null
            moveOutput(current.temporary, current.output, current.settings.overwriteOutput)
            context = null
            RecordingOutput(current.output.toString())
        } catch (throwable: Throwable) {
            runCatching { recorder.release() }
            runCatching { current.rgbaFrameBuffer?.close() }
            current.rgbaFrameBuffer = null
            current.temporary.toFile().delete()
            context = null
            throw encoderFailed(throwable.message ?: "FFmpeg failed to finish the MP4 file.")
        }
    }

    override suspend fun cancel(session: RecordingSession?) = mutex.withLock {
        context?.let(::cleanup)
        context = null
    }

    private fun startRecorder(context: EncoderContext, firstFrame: VideoFrame): FFmpegFrameRecorder {
        firstFrame.validateVideoFrame()
        context.width = firstFrame.width
        context.height = firstFrame.height
        context.encodedWidth = firstFrame.width.roundUpToEven()
        context.encodedHeight = firstFrame.height.roundUpToEven()
        context.rgbaFrameBuffer = RgbaFrameBuffer(
            context.encodedWidth,
            context.encodedHeight,
            firstFrame.pixelFormat,
        )
        val audioFormat = context.settings.audioOutputFormat()
        val failures = mutableListOf<String>()
        h264EncoderCandidates(context.settings.encoder.hardwareAcceleration).forEach { encoderName ->
            val recorder = createRecorder(context, audioFormat?.channelCount ?: 0, encoderName)
            try {
                recorder.start()
                return recorder
            } catch (failure: Throwable) {
                failures += "$encoderName: ${failure.message ?: "unavailable"}"
                runCatching { recorder.release() }
                runCatching { Files.deleteIfExists(context.temporary) }
            }
        }
        throw encoderFailed("No H.264 encoder could be started. ${failures.joinToString("; ")}")
    }

    private fun createRecorder(context: EncoderContext, audioChannelsCount: Int, encoderName: String) =
        FFmpegFrameRecorder(
            context.temporary.toFile(),
            context.encodedWidth,
            context.encodedHeight,
            audioChannelsCount,
        ).apply {
            format = "mp4"
            videoCodecName = encoderName
            pixelFormat = h264OutputPixelFormat()
            frameRate = context.settings.frameRate.toDouble()
            videoBitrate = context.settings.encoder.videoBitrateBitsPerSecond
            gopSize = context.settings.frameRate * context.settings.encoder.keyframeIntervalSeconds
            configureH264Options(encoderName)
            setInterleaved(true)
            context.settings.audioOutputFormat()?.let { audioFormat ->
                audioCodec = AV_CODEC_ID_AAC
                audioBitrate = context.settings.encoder.audioBitrateBitsPerSecond
                sampleRate = audioFormat.sampleRate
                audioChannels = audioFormat.channelCount
            }
        }

    private fun FFmpegFrameRecorder.configureH264Options(encoderName: String) {
        when (encoderName) {
            "h264_nvenc" -> {
                setVideoOption("preset", "p5")
                setVideoOption("tune", "hq")
                setVideoOption("rc", "vbr")
                setVideoOption("cq", "14")
                setVideoOption("profile", "high")
            }
            "h264_qsv" -> {
                setVideoOption("preset", "medium")
            }
            "h264_mf" -> {
                setVideoOption("rate_control", "pc_vbr")
                setVideoOption("scenario", "display_remoting")
                setVideoOption("quality", "90")
                setVideoOption("hw_encoding", "true")
            }
            "h264_videotoolbox" -> setVideoOption("realtime", "false")
            "libopenh264" -> {
                setVideoOption("rc_mode", "bitrate")
                setVideoOption("allow_skip_frames", "false")
            }
        }
    }

    private fun cleanup(context: EncoderContext) {
        context.recorder?.let { recorder ->
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        runCatching { context.rgbaFrameBuffer?.close() }
        context.rgbaFrameBuffer = null
        runCatching { Files.deleteIfExists(context.temporary) }
    }

    private fun moveOutput(source: Path, target: Path, overwrite: Boolean) {
        val options = if (overwrite) {
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        try {
            Files.move(source, target, *options)
        } catch (_: AtomicMoveNotSupportedException) {
            if (overwrite) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.move(source, target)
            }
        }
    }

    private data class EncoderContext(
        val output: Path,
        val temporary: Path,
        val settings: RecordingSettings,
        val pendingAudioFrames: MutableList<AudioFrame> = mutableListOf(),
        var recorder: FFmpegFrameRecorder? = null,
        var rgbaFrameBuffer: RgbaFrameBuffer? = null,
        var width: Int = 0,
        var height: Int = 0,
        var encodedWidth: Int = 0,
        var encodedHeight: Int = 0,
        var lastVideoTimestampMicros: Long? = null,
    )
}

internal fun h264OutputPixelFormat(): Int = AV_PIX_FMT_YUV420P

internal fun monotonicVideoTimestampMicros(
    requestedTimestampMicros: Long,
    previousTimestampMicros: Long?,
    frameRate: Int,
): Long {
    val requested = requestedTimestampMicros.coerceAtLeast(0)
    val previous = previousTimestampMicros ?: return requested
    val minimumFrameInterval = (MICROSECONDS_PER_SECOND + frameRate - 1) / frameRate.coerceAtLeast(1)
    return maxOf(requested, previous + minimumFrameInterval)
}

private const val NANOS_PER_MICROSECOND = 1_000L
private const val MICROSECONDS_PER_SECOND = 1_000_000L
