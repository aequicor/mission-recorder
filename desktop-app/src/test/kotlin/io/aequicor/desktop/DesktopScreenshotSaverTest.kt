package io.aequicor.desktop

import io.aequicor.capture.core.PixelFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.awt.Color
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopScreenshotSaverTest {
    @Test
    fun writesRgbaAndBgraFramesAsPng() = runTest {
        val directory = createTempDirectory("mission-recorder-screenshot-test")
        try {
            val saver = PngDesktopScreenshotSaver(UnconfinedTestDispatcher(testScheduler))
            val cases = listOf(
                PixelFormat.Rgba8888 to byteArrayOf(10, 20, 30, 255.toByte()),
                PixelFormat.Bgra8888 to byteArrayOf(30, 20, 10, 255.toByte()),
            )

            cases.forEach { (pixelFormat, pixels) ->
                val output = directory.resolve("nested").resolve("$pixelFormat.png")
                val result = saver.save(
                    frame = DesktopPreviewFrame(
                        width = 1,
                        height = 1,
                        pixelFormat = pixelFormat,
                        strideBytes = 4,
                        pixelData = pixels,
                    ),
                    outputPath = output.toString(),
                )

                assertEquals(output.toAbsolutePath().normalize().toString(), assertIs<DesktopScreenshotSaveResult.Saved>(result).outputPath)
                val color = Color(assertNotNull(ImageIO.read(output.toFile())).getRGB(0, 0), true)
                assertEquals(10, color.red)
                assertEquals(20, color.green)
                assertEquals(30, color.blue)
                assertEquals(255, color.alpha)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
