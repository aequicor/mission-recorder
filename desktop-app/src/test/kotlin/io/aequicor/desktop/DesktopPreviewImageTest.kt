package io.aequicor.desktop

import androidx.compose.ui.graphics.toPixelMap
import io.aequicor.capture.core.PixelFormat
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopPreviewImageTest {
    @Test
    fun convertsBgraPreviewPixelsWithoutSwappingRedAndBlue() {
        DesktopPreviewFrame(
            width = 2,
            height = 2,
            pixelFormat = PixelFormat.Bgra8888,
            strideBytes = 12,
            pixelData = byteArrayOf(
                0, 0, 255.toByte(), 0,
                255.toByte(), 0, 0, 0,
                0, 0, 0, 0,
                0, 255.toByte(), 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
            ),
        ).toDesktopPreviewImage().use { image ->
            val pixels = image.bitmap.toPixelMap()
            assertTrue(pixels[0, 0].red > 0.9f && pixels[0, 0].blue < 0.1f)
            assertTrue(pixels[1, 0].blue > 0.9f && pixels[1, 0].red < 0.1f)
            assertTrue(pixels[0, 1].green > 0.9f && pixels[0, 1].red < 0.1f)
        }
    }

    @Test
    fun convertsRgbaPreviewPixelsWithoutSwappingRedAndBlue() {
        DesktopPreviewFrame(
            width = 2,
            height = 1,
            pixelFormat = PixelFormat.Rgba8888,
            strideBytes = 8,
            pixelData = byteArrayOf(
                255.toByte(), 0, 0, 0,
                0, 0, 255.toByte(), 0,
            ),
        ).toDesktopPreviewImage().use { image ->
            val pixels = image.bitmap.toPixelMap()
            assertTrue(pixels[0, 0].red > 0.9f && pixels[0, 0].blue < 0.1f)
            assertTrue(pixels[1, 0].blue > 0.9f && pixels[1, 0].red < 0.1f)
        }
    }

    @Test
    fun createsIndependentImmutablePreviewImages() {
        val firstFrame = solidBgraFrame(10)
        firstFrame.toDesktopPreviewImage().use { first ->
            firstFrame.pixelData[0] = 30
            solidBgraFrame(20).toDesktopPreviewImage().use { second ->
                assertTrue(first.bitmap !== second.bitmap)
                val firstPixel = first.bitmap.toPixelMap()[0, 0]
                val secondPixel = second.bitmap.toPixelMap()[0, 0]
                assertTrue(firstPixel.blue > 0.03f && firstPixel.blue < 0.05f)
                assertTrue(secondPixel.blue > 0.07f && secondPixel.blue < 0.09f)
            }
        }
    }
}

private fun solidBgraFrame(blue: Int) = DesktopPreviewFrame(
    width = 1,
    height = 1,
    pixelFormat = PixelFormat.Bgra8888,
    strideBytes = 4,
    pixelData = byteArrayOf(blue.toByte(), 0, 0, 0),
)
