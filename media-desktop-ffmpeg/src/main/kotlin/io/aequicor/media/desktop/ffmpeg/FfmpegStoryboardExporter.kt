package io.aequicor.media.desktop.ffmpeg

import io.aequicor.capture.core.InputEventFrameMarker
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class StoryboardLayout {
    SeparatePngFiles,
    ContactSheet,
}

/** Settings for exporting full-resolution video frames into PNG output. */
data class StoryboardExportSettings(
    val inputVideo: Path,
    val outputPath: Path,
    val layout: StoryboardLayout,
    val interval: Duration = 1.seconds,
    /** Maximum number of regularly sampled frames; marked input-event frames are always retained. */
    val maxFrames: Int = 120,
)

/** Result of exporting sampled video frames into a storyboard layout. */
data class StoryboardExportResult(
    val layout: StoryboardLayout,
    /** Number of frames written after visually similar neighbours were removed. */
    val frameCount: Int,
    val outputPaths: List<Path>,
    /** Number of interval-selected frames inspected before deduplication. */
    val sourceFrameCount: Int = frameCount,
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
        if (settings.maxFrames <= 0) {
            throw StoryboardExportException("Storyboard frame limit must be positive.")
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
            val decodeResult = decodeSelectedFrames(settings) { index, timestampMicros, image ->
                val name = "frame-${(index + 1).toString().padStart(6, '0')}.png"
                val timestampedImage = image.withStoryboardTimestamp(timestampMicros)
                if (!ImageIO.write(timestampedImage, "png", temporaryDirectory.resolve(name).toFile())) {
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
                frameCount = decodeResult.exportedFrameCount,
                outputPaths = names.map(settings.outputPath::resolve),
                sourceFrameCount = decodeResult.sourceFrameCount,
            )
        } catch (throwable: Throwable) {
            deleteRecursively(temporaryDirectory)
            throw throwable.asStoryboardException()
        }
    }

    private fun exportContactSheet(settings: StoryboardExportSettings): StoryboardExportResult {
        val frames = mutableListOf<BufferedImage>()
        val decodeResult = decodeSelectedFrames(settings) { _, timestampMicros, image ->
            frames += image.withStoryboardTimestamp(timestampMicros)
        }
        if (frames.isEmpty()) {
            throw StoryboardExportException("Video does not contain decodable image frames.")
        }
        settings.outputPath.parent?.createDirectories()
        val temporaryFile = temporarySibling(settings.outputPath)
        return try {
            writeContactSheet(frames, temporaryFile)
            moveOutput(temporaryFile, settings.outputPath)
            StoryboardExportResult(
                layout = StoryboardLayout.ContactSheet,
                frameCount = decodeResult.exportedFrameCount,
                outputPaths = listOf(settings.outputPath),
                sourceFrameCount = decodeResult.sourceFrameCount,
            )
        } catch (throwable: Throwable) {
            Files.deleteIfExists(temporaryFile)
            throw throwable.asStoryboardException()
        }
    }

    private fun decodeSelectedFrames(
        settings: StoryboardExportSettings,
        consume: (index: Int, timestampMicros: Long, image: BufferedImage) -> Unit,
    ): StoryboardDecodeResult {
        val grabber = FFmpegFrameGrabber(settings.inputVideo.toFile()).apply {
            pixelFormat = AV_PIX_FMT_BGRA
        }
        val intervalMicros = settings.interval.inWholeMicroseconds
        var nextTimestampMicros = 0L
        var sourceFrameCount = 0
        var exportedFrameCount = 0
        var regularFrameCount = 0
        val deduplicator = StoryboardFrameDeduplicator()
        try {
            grabber.start()
            while (true) {
                val frame = grabber.grabImage() ?: break
                val timestamp = grabber.timestamp.coerceAtLeast(0)
                val inputEvent = frame.hasInputEventMarker()
                val regularSample = regularFrameCount < settings.maxFrames && timestamp >= nextTimestampMicros
                if (regularSample || inputEvent) {
                    val image = frame.bgraToBufferedImage()
                    sourceFrameCount += 1
                    if (regularSample) {
                        regularFrameCount += 1
                        nextTimestampMicros = timestamp + intervalMicros
                    }
                    if (inputEvent || deduplicator.shouldRetain(image)) {
                        consume(exportedFrameCount, timestamp, image)
                        exportedFrameCount += 1
                    }
                }
            }
        } catch (throwable: Throwable) {
            throw throwable.asStoryboardException("FFmpeg failed to decode the video.")
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
        return StoryboardDecodeResult(
            sourceFrameCount = sourceFrameCount,
            exportedFrameCount = exportedFrameCount,
        )
    }

    private fun writeContactSheet(
        frames: List<BufferedImage>,
        outputPath: Path,
    ) {
        val width = frames.maxOf(BufferedImage::getWidth)
        val height = frames.sumOf(BufferedImage::getHeight) + (frames.size - 1) * CELL_GAP
        val sheet = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = sheet.createGraphics()
        try {
            graphics.color = Color(24, 27, 31)
            graphics.fillRect(0, 0, width, height)
            var y = 0
            frames.forEach { image ->
                graphics.drawImage(image, 0, y, null)
                y += image.height + CELL_GAP
            }
        } finally {
            graphics.dispose()
        }
        if (!ImageIO.write(sheet, "png", outputPath.toFile())) {
            throw StoryboardExportException("PNG writer is not available.")
        }
    }
}

internal fun Frame.hasInputEventMarker(): Boolean {
    if (imageDepth != Frame.DEPTH_UBYTE || imageChannels != MARKER_CHANNEL_COUNT || image.isNullOrEmpty()) {
        return false
    }
    val markerWidth = InputEventFrameMarker.COLUMN_COUNT * InputEventFrameMarker.CELL_SIZE_PIXELS
    val markerHeight = InputEventFrameMarker.ROW_COUNT * InputEventFrameMarker.CELL_SIZE_PIXELS
    if (
        imageWidth < InputEventFrameMarker.MARGIN_PIXELS + markerWidth ||
        imageHeight < InputEventFrameMarker.MARGIN_PIXELS + markerHeight
    ) {
        return false
    }
    val pixels = (image[0] as? ByteBuffer)?.duplicate() ?: return false
    var mismatches = 0
    repeat(InputEventFrameMarker.ROW_COUNT) { row ->
        repeat(InputEventFrameMarker.COLUMN_COUNT) { column ->
            val color = markerCellColor(pixels, row, column)
            val matches = if (InputEventFrameMarker.isAccentCell(row, column)) {
                color.isMarkerAccent()
            } else {
                color.isMarkerBackground()
            }
            if (!matches) {
                mismatches += 1
            }
        }
    }
    return mismatches <= MAX_MARKER_CELL_MISMATCHES
}

private fun Frame.markerCellColor(pixels: ByteBuffer, row: Int, column: Int): MarkerColor {
    val cellX = InputEventFrameMarker.MARGIN_PIXELS + column * InputEventFrameMarker.CELL_SIZE_PIXELS
    val cellY = InputEventFrameMarker.MARGIN_PIXELS + row * InputEventFrameMarker.CELL_SIZE_PIXELS
    val sampleOffset = (InputEventFrameMarker.CELL_SIZE_PIXELS - MARKER_SAMPLE_SIZE) / 2
    var red = 0
    var green = 0
    var blue = 0
    repeat(MARKER_SAMPLE_SIZE) { y ->
        repeat(MARKER_SAMPLE_SIZE) { x ->
            val offset = (cellY + sampleOffset + y) * imageStride +
                (cellX + sampleOffset + x) * MARKER_CHANNEL_COUNT
            blue += pixels.get(offset).toInt() and 0xff
            green += pixels.get(offset + 1).toInt() and 0xff
            red += pixels.get(offset + 2).toInt() and 0xff
        }
    }
    val sampleCount = MARKER_SAMPLE_SIZE * MARKER_SAMPLE_SIZE
    return MarkerColor(red / sampleCount, green / sampleCount, blue / sampleCount)
}

private data class MarkerColor(
    val red: Int,
    val green: Int,
    val blue: Int,
) {
    fun isMarkerAccent(): Boolean = red >= MIN_MARKER_ACCENT_RED &&
        red - green >= MIN_MARKER_ACCENT_RED_GREEN_DIFFERENCE &&
        red - blue >= MIN_MARKER_ACCENT_RED_BLUE_DIFFERENCE

    fun isMarkerBackground(): Boolean = max(red, max(green, blue)) <= MAX_MARKER_BACKGROUND_CHANNEL
}

private data class StoryboardDecodeResult(
    val sourceFrameCount: Int,
    val exportedFrameCount: Int,
)

internal class StoryboardFrameDeduplicator {
    private var lastRetainedFingerprint: IntArray? = null

    fun shouldRetain(image: BufferedImage): Boolean {
        val fingerprint = image.storyboardFingerprint()
        val previous = lastRetainedFingerprint
        if (previous != null && fingerprintsAreSimilar(previous, fingerprint)) {
            return false
        }
        lastRetainedFingerprint = fingerprint
        return true
    }
}

private fun BufferedImage.storyboardFingerprint(): IntArray {
    val fingerprint = BufferedImage(FINGERPRINT_WIDTH, FINGERPRINT_HEIGHT, BufferedImage.TYPE_INT_RGB)
    val graphics = fingerprint.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(this, 0, 0, FINGERPRINT_WIDTH, FINGERPRINT_HEIGHT, null)
    } finally {
        graphics.dispose()
    }
    return fingerprint.getRGB(
        0,
        0,
        FINGERPRINT_WIDTH,
        FINGERPRINT_HEIGHT,
        null,
        0,
        FINGERPRINT_WIDTH,
    )
}

private fun fingerprintsAreSimilar(first: IntArray, second: IntArray): Boolean {
    if (first.size != second.size) {
        return false
    }
    var totalDifference = 0L
    first.indices.forEach { index ->
        val firstPixel = first[index]
        val secondPixel = second[index]
        val redDifference = abs((firstPixel ushr 16 and 0xff) - (secondPixel ushr 16 and 0xff))
        val greenDifference = abs((firstPixel ushr 8 and 0xff) - (secondPixel ushr 8 and 0xff))
        val blueDifference = abs((firstPixel and 0xff) - (secondPixel and 0xff))
        if (max(redDifference, max(greenDifference, blueDifference)) > MAX_LOCAL_CHANNEL_DIFFERENCE) {
            return false
        }
        totalDifference += redDifference + greenDifference + blueDifference
    }
    return totalDifference <= first.size.toLong() * RGB_CHANNEL_COUNT * MAX_MEAN_CHANNEL_DIFFERENCE
}

internal fun BufferedImage.withStoryboardTimestamp(timestampMicros: Long): BufferedImage {
    val text = formatStoryboardTimestamp(timestampMicros)
    val measuringGraphics = createGraphics()
    val font: Font
    val padding: Int
    val stripHeight: Int
    try {
        var fontSize = (height * TIMESTAMP_FONT_HEIGHT_RATIO).roundToInt()
            .coerceIn(MIN_TIMESTAMP_FONT_SIZE, MAX_TIMESTAMP_FONT_SIZE)
        var candidateFont = Font(Font.SANS_SERIF, Font.BOLD, fontSize)
        var candidatePadding = max(MIN_TIMESTAMP_PADDING, fontSize / 3)
        var metrics = measuringGraphics.getFontMetrics(candidateFont)
        while (fontSize > MIN_FITTED_TIMESTAMP_FONT_SIZE && metrics.stringWidth(text) > width - candidatePadding * 2) {
            fontSize -= 1
            candidateFont = candidateFont.deriveFont(fontSize.toFloat())
            candidatePadding = max(MIN_TIMESTAMP_PADDING, fontSize / 3)
            metrics = measuringGraphics.getFontMetrics(candidateFont)
        }
        font = candidateFont
        padding = candidatePadding
        stripHeight = metrics.height + padding * 2
    } finally {
        measuringGraphics.dispose()
    }

    val result = BufferedImage(width, height + stripHeight, BufferedImage.TYPE_INT_RGB)
    val graphics = result.createGraphics()
    try {
        graphics.drawImage(this, 0, 0, null)
        graphics.color = TIMESTAMP_STRIP_COLOR
        graphics.fillRect(0, height, width, stripHeight)
        graphics.font = font
        graphics.color = Color.WHITE
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        val metrics = graphics.fontMetrics
        val x = ((width - metrics.stringWidth(text)) / 2).coerceAtLeast(0)
        val y = height + padding + metrics.ascent
        graphics.drawString(text, x, y)
    } finally {
        graphics.dispose()
    }
    return result
}

internal fun formatStoryboardTimestamp(timestampMicros: Long): String {
    val totalMilliseconds = timestampMicros.coerceAtLeast(0) / MICROS_PER_MILLISECOND
    val milliseconds = totalMilliseconds % MILLIS_PER_SECOND
    val totalSeconds = totalMilliseconds / MILLIS_PER_SECOND
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    val totalMinutes = totalSeconds / SECONDS_PER_MINUTE
    val minutes = totalMinutes % MINUTES_PER_HOUR
    val hours = totalMinutes / MINUTES_PER_HOUR
    return "${hours.toString().padStart(2, '0')}:" +
        "${minutes.toString().padStart(2, '0')}:" +
        "${seconds.toString().padStart(2, '0')}." +
        milliseconds.toString().padStart(3, '0')
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
private const val MARKER_CHANNEL_COUNT = 4
private const val MARKER_SAMPLE_SIZE = 2
private const val MAX_MARKER_CELL_MISMATCHES = 3
private const val MIN_MARKER_ACCENT_RED = 140
private const val MIN_MARKER_ACCENT_RED_GREEN_DIFFERENCE = 65
private const val MIN_MARKER_ACCENT_RED_BLUE_DIFFERENCE = 45
private const val MAX_MARKER_BACKGROUND_CHANNEL = 90
private const val MAX_TEMPORARY_PATH_ATTEMPTS = 10
private const val FINGERPRINT_WIDTH = 64
private const val FINGERPRINT_HEIGHT = 64
private const val RGB_CHANNEL_COUNT = 3
private const val MAX_MEAN_CHANNEL_DIFFERENCE = 2
private const val MAX_LOCAL_CHANNEL_DIFFERENCE = 12
private const val TIMESTAMP_FONT_HEIGHT_RATIO = 0.04
private const val MIN_TIMESTAMP_FONT_SIZE = 12
private const val MAX_TIMESTAMP_FONT_SIZE = 48
private const val MIN_FITTED_TIMESTAMP_FONT_SIZE = 8
private const val MIN_TIMESTAMP_PADDING = 4
private const val MICROS_PER_MILLISECOND = 1_000
private const val MILLIS_PER_SECOND = 1_000
private const val SECONDS_PER_MINUTE = 60
private const val MINUTES_PER_HOUR = 60
private val TIMESTAMP_STRIP_COLOR = Color(24, 27, 31)
