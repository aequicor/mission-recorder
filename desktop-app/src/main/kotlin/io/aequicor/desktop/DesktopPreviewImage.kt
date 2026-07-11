package io.aequicor.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

internal fun DesktopPreviewFrame.toImageBitmap(): ImageBitmap {
    require(rgbaPixels.size == width * height * PREVIEW_CHANNEL_COUNT)
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val argb = (image.raster.dataBuffer as DataBufferInt).data
    repeat(width * height) { pixel ->
        val offset = pixel * PREVIEW_CHANNEL_COUNT
        val red = rgbaPixels[offset].toInt() and 0xff
        val green = rgbaPixels[offset + 1].toInt() and 0xff
        val blue = rgbaPixels[offset + 2].toInt() and 0xff
        val alpha = rgbaPixels[offset + 3].toInt() and 0xff
        argb[pixel] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }
    return image.toComposeImageBitmap()
}

private const val PREVIEW_CHANNEL_COUNT = 4
