package io.aequicor.desktop

import io.aequicor.capture.core.PixelFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories

internal fun interface DesktopScreenshotSaver {
    suspend fun save(frame: DesktopPreviewFrame, outputPath: String): DesktopScreenshotSaveResult
}

internal sealed interface DesktopScreenshotSaveResult {
    data class Saved(val outputPath: String) : DesktopScreenshotSaveResult
    data class Failed(val message: String) : DesktopScreenshotSaveResult
}

internal data object UnavailableDesktopScreenshotSaver : DesktopScreenshotSaver {
    override suspend fun save(frame: DesktopPreviewFrame, outputPath: String): DesktopScreenshotSaveResult =
        DesktopScreenshotSaveResult.Failed("Screenshot saving is unavailable.")
}

internal class PngDesktopScreenshotSaver(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DesktopScreenshotSaver {
    override suspend fun save(frame: DesktopPreviewFrame, outputPath: String): DesktopScreenshotSaveResult =
        withContext(dispatcher) {
            try {
                val normalizedOutput = Path.of(outputPath).toAbsolutePath().normalize()
                normalizedOutput.parent?.createDirectories()
                if (ImageIO.write(frame.toBufferedImage(), PNG_FORMAT, normalizedOutput.toFile())) {
                    DesktopScreenshotSaveResult.Saved(normalizedOutput.toString())
                } else {
                    DesktopScreenshotSaveResult.Failed("PNG writer is unavailable.")
                }
            } catch (failure: InvalidPathException) {
                DesktopScreenshotSaveResult.Failed(failure.message ?: "Screenshot path is invalid.")
            } catch (failure: IOException) {
                DesktopScreenshotSaveResult.Failed(failure.message ?: "Could not save screenshot.")
            } catch (failure: SecurityException) {
                DesktopScreenshotSaveResult.Failed(failure.message ?: "Screenshot path is not accessible.")
            }
        }
}

private fun DesktopPreviewFrame.toBufferedImage(): BufferedImage {
    val rowPixelBytes = width.toLong() * SCREENSHOT_CHANNEL_COUNT
    val requiredBytes = (height - 1).toLong() * strideBytes + rowPixelBytes
    val pixelCount = width.toLong() * height
    require(width > 0 && height > 0) { "Screenshot dimensions must be positive." }
    require(strideBytes.toLong() >= rowPixelBytes) { "Screenshot stride is too small." }
    require(requiredBytes <= pixelData.size) { "Screenshot pixels are incomplete." }
    require(pixelCount <= Int.MAX_VALUE) { "Screenshot is too large." }

    val redOffset = when (pixelFormat) {
        PixelFormat.Rgba8888 -> 0
        PixelFormat.Bgra8888 -> 2
        PixelFormat.Nv12 -> error("Screenshot requires RGBA8888 or BGRA8888 pixels.")
    }
    val blueOffset = when (pixelFormat) {
        PixelFormat.Rgba8888 -> 2
        PixelFormat.Bgra8888 -> 0
        PixelFormat.Nv12 -> error("Screenshot requires RGBA8888 or BGRA8888 pixels.")
    }
    val argbPixels = IntArray(pixelCount.toInt())
    var targetIndex = 0
    repeat(height) { row ->
        var sourceIndex = row * strideBytes
        repeat(width) {
            val red = pixelData[sourceIndex + redOffset].toInt() and CHANNEL_MASK
            val green = pixelData[sourceIndex + GREEN_OFFSET].toInt() and CHANNEL_MASK
            val blue = pixelData[sourceIndex + blueOffset].toInt() and CHANNEL_MASK
            val alpha = pixelData[sourceIndex + ALPHA_OFFSET].toInt() and CHANNEL_MASK
            argbPixels[targetIndex] =
                (alpha shl ALPHA_SHIFT) or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue
            sourceIndex += SCREENSHOT_CHANNEL_COUNT
            targetIndex += 1
        }
    }
    return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
        setRGB(0, 0, width, height, argbPixels, 0, width)
    }
}

private const val PNG_FORMAT = "png"
private const val SCREENSHOT_CHANNEL_COUNT = 4
private const val GREEN_OFFSET = 1
private const val ALPHA_OFFSET = 3
private const val CHANNEL_MASK = 0xFF
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
