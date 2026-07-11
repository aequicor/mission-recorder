package io.aequicor.encoder

import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.MediaEncoder
import io.aequicor.capture.core.PixelFormat
import io.aequicor.capture.core.RecordingError
import io.aequicor.capture.core.RecordingException
import io.aequicor.capture.core.RecordingMetrics
import io.aequicor.capture.core.RecordingOutput
import io.aequicor.capture.core.RecordingSession
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.VideoFrame
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.image.BufferedImage
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.name

class FrameSequenceMediaEncoder(
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
    },
) : MediaEncoder {
    private var context: EncoderContext? = null

    override suspend fun open(session: RecordingSession, settings: RecordingSettings) {
        val output = Path.of(settings.outputPath).toAbsolutePath().normalize()
        if (output.exists() && !settings.overwriteOutput) {
            throw RecordingException(RecordingError.EncoderFailed("Output path already exists: $output"))
        }
        output.parent?.createDirectories()
        val temp = output.resolveSibling("${output.name}.tmp-${session.id.value}")
        if (temp.exists()) {
            throw RecordingException(RecordingError.EncoderFailed("Temporary output path already exists: $temp"))
        }
        val frames = temp.resolve("frames")
        frames.createDirectories()
        context = EncoderContext(
            output = output,
            temp = temp,
            frames = frames,
            audio = temp.resolve("audio"),
            overwriteOutput = settings.overwriteOutput,
        )
    }

    override suspend fun writeVideoFrame(frame: VideoFrame) {
        val current = context ?: throw RecordingException(RecordingError.EncoderFailed("Encoder is not open."))
        val pixelData = frame.pixelData
            ?: throw RecordingException(RecordingError.EncoderFailed("Video frame does not contain pixel data."))
        if (frame.pixelFormat != PixelFormat.Rgba8888) {
            throw RecordingException(
                RecordingError.EncoderFailed("Frame sequence encoder expects RGBA8888 frames."),
            )
        }
        val requiredBytes = (frame.height - 1) * frame.strideBytes + frame.width * 4
        if (pixelData.size < requiredBytes) {
            throw RecordingException(
                RecordingError.EncoderFailed("Video frame pixel data is shorter than required by dimensions and stride."),
            )
        }

        current.videoFrames += 1
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        var offset = 0
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val r = pixelData[offset].toInt() and 0xff
                val g = pixelData[offset + 1].toInt() and 0xff
                val b = pixelData[offset + 2].toInt() and 0xff
                val a = pixelData[offset + 3].toInt() and 0xff
                image.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
                offset += 4
            }
            offset = (y + 1) * frame.strideBytes
        }

        val framePath = current.frames.resolve("frame-${current.videoFrames.toString().padStart(6, '0')}.png")
        ImageIO.write(image, "png", framePath.toFile())
    }

    override suspend fun writeAudioFrame(frame: AudioFrame) {
        val current = context ?: throw RecordingException(RecordingError.EncoderFailed("Encoder is not open."))
        val audioData = frame.audioData
            ?: throw RecordingException(RecordingError.EncoderFailed("Audio frame does not contain PCM data."))
        if (frame.sampleRate <= 0 || frame.channelCount <= 0 || frame.sampleCount <= 0) {
            throw RecordingException(
                RecordingError.EncoderFailed("Audio frame sample rate, channel count, and sample count must be positive."),
            )
        }
        val requiredBytes = frame.sampleCount.toLong() * frame.channelCount * frame.sampleFormat.bytesPerSample
        if (requiredBytes > Int.MAX_VALUE || audioData.size != requiredBytes.toInt()) {
            throw RecordingException(
                RecordingError.EncoderFailed(
                    "Audio frame PCM data size does not match its sample count, channels, and sample format.",
                ),
            )
        }

        current.audio.createDirectories()
        val nextAudioFrame = current.audioFrames + 1
        val fileName = "audio-${nextAudioFrame.toString().padStart(6, '0')}.pcm"
        Files.write(current.audio.resolve(fileName), audioData)
        current.audioChunks += AudioChunkManifestEntry(
            file = "audio/$fileName",
            timestampNanoseconds = frame.timestamp.nanoseconds,
            sampleRate = frame.sampleRate,
            channelCount = frame.channelCount,
            sampleCount = frame.sampleCount,
            sampleFormat = frame.sampleFormat.name,
            sourceId = frame.sourceId.value,
            byteCount = audioData.size,
        )
        current.audioFrames = nextAudioFrame
    }

    override suspend fun finish(session: RecordingSession, metrics: RecordingMetrics): RecordingOutput {
        val current = context ?: throw RecordingException(RecordingError.EncoderFailed("Encoder is not open."))
        val manifest = current.temp.resolve("recording.json")
        Files.writeString(manifest, json.encodeToString(buildManifest(session, metrics, current)) + "\n")
        replaceOutput(current)
        context = null
        return RecordingOutput(current.output.toString())
    }

    override suspend fun cancel(session: RecordingSession?) {
        context?.let { deleteRecursively(it.temp) }
        context = null
    }

    private fun buildManifest(
        session: RecordingSession,
        metrics: RecordingMetrics,
        context: EncoderContext,
    ): FrameSequenceManifest = FrameSequenceManifest(
        sessionId = session.id.value,
        sourceId = session.settings.captureSource.id.value,
        frameRate = session.settings.frameRate,
        videoFrames = context.videoFrames,
        audioFrames = context.audioFrames,
        metricsVideoFrames = metrics.videoFrames,
        metricsAudioFrames = metrics.audioFrames,
        durationNanoseconds = metrics.duration.inWholeNanoseconds,
        audioChunks = context.audioChunks.toList(),
    )

    private fun replaceOutput(context: EncoderContext) {
        if (!context.overwriteOutput || !context.output.exists()) {
            moveOutput(context.temp, context.output)
            return
        }
        val backup = context.output.resolveSibling("${context.output.name}.backup-${System.nanoTime()}")
        moveOutput(context.output, backup)
        try {
            moveOutput(context.temp, context.output)
        } catch (failure: Throwable) {
            runCatching { deleteRecursively(context.output) }
            runCatching { moveOutput(backup, context.output) }
            throw failure
        }
        runCatching { deleteRecursively(backup) }
    }

    private fun moveOutput(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!path.exists()) {
            return
        }
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
        }
    }

    private data class EncoderContext(
        val output: Path,
        val temp: Path,
        val frames: Path,
        val audio: Path,
        val overwriteOutput: Boolean,
        val audioChunks: MutableList<AudioChunkManifestEntry> = mutableListOf(),
        var videoFrames: Long = 0,
        var audioFrames: Long = 0,
    )
}

@Serializable
private data class FrameSequenceManifest(
    val format: String = "mission-recorder-frame-sequence",
    val manifestVersion: Int = 1,
    val sessionId: String,
    val sourceId: String,
    val frameRate: Int,
    val videoFrames: Long,
    val audioFrames: Long,
    val metricsVideoFrames: Long,
    val metricsAudioFrames: Long,
    val durationNanoseconds: Long,
    val audioChunks: List<AudioChunkManifestEntry>,
)

@Serializable
private data class AudioChunkManifestEntry(
    val file: String,
    val timestampNanoseconds: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val sampleCount: Int,
    val sampleFormat: String,
    val sourceId: String,
    val byteCount: Int,
)
