package io.aequicor.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import io.aequicor.capture.core.PixelFormat
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

internal class DesktopPreviewImage(
    val bitmap: ImageBitmap,
    private val skiaBitmap: Bitmap,
) : AutoCloseable {
    override fun close() {
        skiaBitmap.close()
    }
}

internal fun DesktopPreviewFrame.toDesktopPreviewImage(): DesktopPreviewImage {
    requireValidPixels()
    val skiaBitmap = Bitmap()
    try {
        check(skiaBitmap.installPixels(imageInfo(), pixelData, strideBytes)) {
            "Could not install preview bitmap pixels."
        }
        return DesktopPreviewImage(
            bitmap = skiaBitmap.asComposeImageBitmap(),
            skiaBitmap = skiaBitmap,
        )
    } catch (failure: Throwable) {
        skiaBitmap.close()
        throw failure
    }
}

private fun DesktopPreviewFrame.requireValidPixels() {
    val rowPixelBytes = width.toLong() * PREVIEW_CHANNEL_COUNT
    val requiredBytes = (height - 1).toLong() * strideBytes + rowPixelBytes
    require(width > 0 && height > 0 && strideBytes.toLong() >= rowPixelBytes)
    require(requiredBytes <= pixelData.size)
}

private fun DesktopPreviewFrame.imageInfo(): ImageInfo {
    val colorType = when (pixelFormat) {
        PixelFormat.Bgra8888 -> ColorType.BGRA_8888
        PixelFormat.Rgba8888 -> ColorType.RGBA_8888
        PixelFormat.Nv12 -> error("Preview requires RGBA8888 or BGRA8888 pixels.")
    }
    return ImageInfo(width, height, colorType, ColorAlphaType.OPAQUE)
}

private const val PREVIEW_CHANNEL_COUNT = 4
