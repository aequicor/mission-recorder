package io.aequicor.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.time.Duration

class FrameSequenceExporter(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun export(settings: ExportFramesSettings): ExportFramesResult {
        validate(settings)
        val manifest = readManifest(settings.inputPath)
        val sourceFrames = listSourceFrames(settings.inputPath)
        if (sourceFrames.isEmpty()) {
            throw ExportFramesException(ExportFramesFailureKind.InvalidInput, "Input recording does not contain frames.")
        }

        val selectedFrames = selectFrames(
            frames = sourceFrames,
            sourceFrameRate = manifest.frameRate,
            targetFps = settings.targetFps,
            interval = settings.interval,
        )
        settings.outputDirectory.createDirectories()
        val targets = selectedFrames.mapIndexed { index, _ ->
            settings.outputDirectory.resolve("frame-${(index + 1).toString().padStart(6, '0')}.${settings.imageFormat.extension}")
        }
        if (!settings.overwrite) {
            val existing = targets.firstOrNull { it.exists() }
            if (existing != null) {
                throw ExportFramesException(
                    ExportFramesFailureKind.OutputConflict,
                    "Output frame already exists: $existing",
                )
            }
        }

        selectedFrames.zip(targets).forEach { (source, target) ->
            writeFrame(source, target, settings.imageFormat, settings.overwrite)
        }

        return ExportFramesResult(
            outputDirectory = settings.outputDirectory,
            sourceFrames = sourceFrames.size,
            exportedFrames = selectedFrames.size,
            imageFormat = settings.imageFormat,
        )
    }

    private fun validate(settings: ExportFramesSettings) {
        if (!settings.inputPath.exists() || !settings.inputPath.isDirectory()) {
            throw ExportFramesException(
                ExportFramesFailureKind.InvalidInput,
                "Input must be an existing .mrec directory: ${settings.inputPath}",
            )
        }
        if (settings.targetFps != null && settings.targetFps <= 0) {
            throw ExportFramesException(ExportFramesFailureKind.InvalidSettings, "Export fps must be positive.")
        }
        if (settings.interval != null && !settings.interval.isPositive()) {
            throw ExportFramesException(ExportFramesFailureKind.InvalidSettings, "Export interval must be positive.")
        }
        if (settings.targetFps != null && settings.interval != null) {
            throw ExportFramesException(
                ExportFramesFailureKind.InvalidSettings,
                "Use either fps or interval, not both.",
            )
        }
    }

    private fun readManifest(inputPath: Path): FrameSequenceManifest {
        val manifestPath = inputPath.resolve("recording.json")
        if (!manifestPath.exists()) {
            throw ExportFramesException(
                ExportFramesFailureKind.InvalidInput,
                "Input recording is missing recording.json.",
            )
        }
        val manifest = json.decodeFromString<FrameSequenceManifest>(Files.readString(manifestPath))
        if (manifest.format != "mission-recorder-frame-sequence") {
            throw ExportFramesException(
                ExportFramesFailureKind.UnsupportedFormat,
                "Unsupported recording format: ${manifest.format}",
            )
        }
        if (manifest.frameRate <= 0) {
            throw ExportFramesException(ExportFramesFailureKind.InvalidInput, "Recording frameRate must be positive.")
        }
        return manifest
    }

    private fun listSourceFrames(inputPath: Path): List<Path> {
        val framesDirectory = inputPath.resolve("frames")
        if (!framesDirectory.exists() || !framesDirectory.isDirectory()) {
            throw ExportFramesException(
                ExportFramesFailureKind.InvalidInput,
                "Input recording is missing frames directory.",
            )
        }
        Files.list(framesDirectory).use { stream ->
            return stream
                .filter { it.extension.lowercase() == "png" }
                .sorted()
                .toList()
        }
    }

    private fun selectFrames(
        frames: List<Path>,
        sourceFrameRate: Int,
        targetFps: Int?,
        interval: Duration?,
    ): List<Path> {
        if (targetFps == null && interval == null) {
            return frames
        }

        val sourceFrameIntervalNanos = 1_000_000_000L / sourceFrameRate
        val targetIntervalNanos = when {
            targetFps != null -> 1_000_000_000L / targetFps
            interval != null -> interval.inWholeNanoseconds
            else -> error("Selection interval is required.")
        }
        if (targetIntervalNanos <= sourceFrameIntervalNanos) {
            return frames
        }

        val selected = mutableListOf<Path>()
        var nextTimestamp = 0L
        frames.forEachIndexed { index, frame ->
            val timestamp = index * sourceFrameIntervalNanos
            if (timestamp >= nextTimestamp) {
                selected.add(frame)
                nextTimestamp += targetIntervalNanos
            }
        }
        return selected
    }

    private fun writeFrame(source: Path, target: Path, format: ExportImageFormat, overwrite: Boolean) {
        when (format) {
            ExportImageFormat.Png -> Files.copy(
                source,
                target,
                if (overwrite) StandardCopyOption.REPLACE_EXISTING else StandardCopyOption.COPY_ATTRIBUTES,
            )
            ExportImageFormat.Jpeg -> {
                val image = ImageIO.read(source.toFile())
                    ?: throw ExportFramesException(ExportFramesFailureKind.InvalidInput, "Cannot read source frame: $source")
                if (overwrite && target.exists()) {
                    Files.delete(target)
                }
                if (!ImageIO.write(image, "jpg", target.toFile())) {
                    throw ExportFramesException(
                        ExportFramesFailureKind.UnsupportedFormat,
                        "JPEG writer is not available in this runtime.",
                    )
                }
            }
        }
    }
}

data class ExportFramesSettings(
    val inputPath: Path,
    val outputDirectory: Path,
    val targetFps: Int? = null,
    val interval: Duration? = null,
    val imageFormat: ExportImageFormat = ExportImageFormat.Png,
    val overwrite: Boolean = false,
)

data class ExportFramesResult(
    val outputDirectory: Path,
    val sourceFrames: Int,
    val exportedFrames: Int,
    val imageFormat: ExportImageFormat,
)

enum class ExportImageFormat(val extension: String) {
    Png("png"),
    Jpeg("jpg"),
}

class ExportFramesException(
    val kind: ExportFramesFailureKind,
    override val message: String,
) : RuntimeException(message)

enum class ExportFramesFailureKind {
    InvalidInput,
    InvalidSettings,
    OutputConflict,
    UnsupportedFormat,
}

@Serializable
private data class FrameSequenceManifest(
    val format: String,
    val frameRate: Int,
)
