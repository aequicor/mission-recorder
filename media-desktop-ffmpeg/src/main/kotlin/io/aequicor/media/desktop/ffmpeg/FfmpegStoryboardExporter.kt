package io.aequicor.media.desktop.ffmpeg

import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.math.ceil
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class StoryboardLayout {
    SeparatePngFiles,
    ContactSheet,
}

data class StoryboardExportSettings(
    val inputVideo: Path,
    val outputPath: Path,
    val layout: StoryboardLayout,
    val interval: Duration = 1.seconds,
    val columns: Int = 4,
    val maxFrames: Int = 120,
    val thumbnailWidth: Int = DEFAULT_CONTACT_SHEET_FRAME_WIDTH,
)

data class StoryboardExportResult(
    val layout: StoryboardLayout,
    val frameCount: Int,
    val outputPaths: List<Path>,
)

class StoryboardExportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

fun interface StoryboardExporter {
    suspend fun export(settings: StoryboardExportSettings): StoryboardExportResult
}

class FfmpegStoryboardExporter : StoryboardExporter {
    override suspend fun export(settings: StoryboardExportSettings): StoryboardExportResult {
        validate(settings)
        return when (settings.layout) {
            StoryboardLayout.SeparatePngFiles -> exportSeparateFrames(settings)
            StoryboardLayout.ContactSheet -> exportContactSheet(settings)
        }
    }

    private fun validate(settings: StoryboardExportSettings) {
        if (!settings.inputVideo.exists() || settings.inputVideo.isDirectory()) {
            throw StoryboardExportException("Input video does not exist: ${settings.inputVideo}")
        }
        if (!settings.interval.isPositive()) {
            throw StoryboardExportException("Storyboard interval must be positive.")
        }
        if (settings.columns <= 0 || settings.maxFrames <= 0 || settings.thumbnailWidth <= 0) {
            throw StoryboardExportException("Storyboard dimensions and frame limit must be positive.")
        }
        if (settings.outputPath.exists()) {
            throw StoryboardExportException("Storyboard output already exists: ${settings.outputPath}")
        }
        if (settings.outputPath.fileName == null) {
            throw StoryboardExportException("Storyboard output must have a file or directory name.")
        }
    }

    private fun exportSeparateFrames(settings: StoryboardExportSettings): StoryboardExportResult {
        settings.outputPath.parent?.createDirectories()
        val temporaryDirectory = temporarySibling(settings.outputPath)
        Files.createDirectory(temporaryDirectory)
        return try {
            val names = mutableListOf<String>()
            decodeSelectedFrames(settings) { index, image ->
                val name = "frame-${(index + 1).toString().padStart(6, '0')}.png"
                if (!ImageIO.write(image, "png", temporaryDirectory.resolve(name).toFile())) {
                    throw StoryboardExportException("PNG writer is not available.")
                }
                names += name
            }
            if (names.isEmpty()) {
                throw StoryboardExportException("Video does not contain decodable image frames.")
            }
            moveOutput(temporaryDirectory, settings.outputPath)
            StoryboardExportResult(
                layout = StoryboardLayout.SeparatePngFiles,
                frameCount = names.size,
                outputPaths = names.map(settings.outputPath::resolve),
            )
        } catch (throwable: Throwable) {
            deleteRecursively(temporaryDirectory)
            throw throwable.asStoryboardException()
        }
    }

    private fun exportContactSheet(settings: StoryboardExportSettings): StoryboardExportResult {
        val frames = mutableListOf<BufferedImage>()
        decodeSelectedFrames(settings) { _, image ->
            frames += image.scaledCopy(settings.thumbnailWidth)
        }
        if (frames.isEmpty()) {
            throw StoryboardExportException("Video does not contain decodable image frames.")
        }
        settings.outputPath.parent?.createDirectories()
        val temporaryFile = temporarySibling(settings.outputPath)
        return try {
            writeContactSheet(settings, frames, temporaryFile)
            moveOutput(temporaryFile, settings.outputPath)
            StoryboardExportResult(
                layout = StoryboardLayout.ContactSheet,
                frameCount = frames.size,
                outputPaths = listOf(settings.outputPath),
            )
        } catch (throwable: Throwable) {
            Files.deleteIfExists(temporaryFile)
            throw throwable.asStoryboardException()
        }
    }

    private fun decodeSelectedFrames(
        settings: StoryboardExportSettings,
        consume: (index: Int, image: BufferedImage) -> Unit,
    ) {
        val grabber = FFmpegFrameGrabber(settings.inputVideo.toFile()).apply {
            pixelFormat = AV_PIX_FMT_BGRA
        }
        val intervalMicros = settings.interval.inWholeMicroseconds
        var nextTimestampMicros = 0L
        var selectedFrameCount = 0
        try {
            grabber.start()
            while (selectedFrameCount < settings.maxFrames) {
                val frame = grabber.grabImage() ?: break
                val timestamp = grabber.timestamp.coerceAtLeast(0)
                if (timestamp >= nextTimestampMicros) {
                    val image = frame.bgraToBufferedImage()
                    consume(selectedFrameCount, image)
                    selectedFrameCount += 1
                    nextTimestampMicros = timestamp + intervalMicros
                }
            }
        } catch (throwable: Throwable) {
            throw throwable.asStoryboardException("FFmpeg failed to decode the video.")
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    private fun writeContactSheet(
        settings: StoryboardExportSettings,
        frames: List<BufferedImage>,
        outputPath: Path,
    ) {
        val columns = min(settings.columns, frames.size)
        val rows = ceil(frames.size.toDouble() / columns).toInt()
        val first = frames.first()
        val cellWidth = first.width
        val cellHeight = first.height
        val width = columns * cellWidth + (columns - 1) * CELL_GAP
        val height = rows * cellHeight + (rows - 1) * CELL_GAP
        val sheet = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = sheet.createGraphics()
        try {
            graphics.color = Color(24, 27, 31)
            graphics.fillRect(0, 0, width, height)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            frames.forEachIndexed { index, image ->
                val x = (index % columns) * (cellWidth + CELL_GAP)
                val y = (index / columns) * (cellHeight + CELL_GAP)
                graphics.drawImage(image, x, y, cellWidth, cellHeight, null)
            }
        } finally {
            graphics.dispose()
        }
        if (!ImageIO.write(sheet, "png", outputPath.toFile())) {
            throw StoryboardExportException("PNG writer is not available.")
        }
    }
}

private fun BufferedImage.scaledCopy(maxWidth: Int): BufferedImage {
    val targetWidth = min(maxWidth, width)
    val targetHeight = (height.toDouble() / width * targetWidth).toInt().coerceAtLeast(1)
    val copy = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
    val graphics = copy.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.drawImage(this, 0, 0, targetWidth, targetHeight, null)
    } finally {
        graphics.dispose()
    }
    return copy
}

private fun temporarySibling(output: Path): Path {
    repeat(MAX_TEMPORARY_PATH_ATTEMPTS) { attempt ->
        val candidate = output.resolveSibling(".${output.name}.tmp-${System.nanoTime()}-$attempt")
        if (!candidate.exists()) {
            return candidate
        }
    }
    throw StoryboardExportException("Could not allocate a temporary storyboard path next to $output")
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
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}

private fun Throwable.asStoryboardException(
    fallbackMessage: String = "Storyboard export failed.",
): StoryboardExportException =
    this as? StoryboardExportException
        ?: StoryboardExportException(message ?: fallbackMessage, this)

private const val CELL_GAP = 8
private const val DEFAULT_CONTACT_SHEET_FRAME_WIDTH = 640
private const val MAX_TEMPORARY_PATH_ATTEMPTS = 10
