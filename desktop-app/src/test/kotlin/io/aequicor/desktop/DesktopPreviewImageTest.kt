package io.aequicor.desktop

import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopPreviewImageTest {
    @Test
    fun convertsRgbaPreviewPixelsWithoutSwappingRedAndBlue() {
        val bitmap = DesktopPreviewFrame(
            width = 2,
            height = 1,
            rgbaPixels = byteArrayOf(
                255.toByte(), 0, 0, 255.toByte(),
                0, 0, 255.toByte(), 255.toByte(),
            ),
        ).toImageBitmap()

        val pixels = bitmap.toPixelMap()
        assertTrue(pixels[0, 0].red > 0.9f && pixels[0, 0].blue < 0.1f)
        assertTrue(pixels[1, 0].blue > 0.9f && pixels[1, 0].red < 0.1f)
    }
}
