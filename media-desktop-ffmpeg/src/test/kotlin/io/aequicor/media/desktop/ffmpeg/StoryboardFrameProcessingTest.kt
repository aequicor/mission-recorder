package io.aequicor.media.desktop.ffmpeg

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoryboardFrameProcessingTest {
    @Test
    fun removesOnlySimilarNeighboursAndKeepsReturnToEarlierScene() {
        val firstScene = solidImage(Color(100, 120, 140))
        val codecNoise = solidImage(Color(102, 121, 139))
        val changedScene = firstScene.copy().apply {
            createGraphics().use { graphics ->
                graphics.color = Color.WHITE
                graphics.fillRect(120, 60, 80, 60)
            }
        }
        val deduplicator = StoryboardFrameDeduplicator()

        assertTrue(deduplicator.shouldRetain(firstScene))
        assertFalse(deduplicator.shouldRetain(codecNoise))
        assertTrue(deduplicator.shouldRetain(changedScene))
        assertTrue(deduplicator.shouldRetain(firstScene))
    }

    @Test
    fun appendsReadableTimestampStripWithoutChangingImageWidth() {
        val source = solidImage(Color(38, 97, 156))

        val timestamped = source.withStoryboardTimestamp(3_723_456_000L)

        assertEquals(source.width, timestamped.width)
        assertTrue(timestamped.height > source.height)
        assertEquals(source.getRGB(source.width / 2, source.height / 2), timestamped.getRGB(source.width / 2, source.height / 2))
        assertEquals(Color(24, 27, 31).rgb, timestamped.getRGB(0, source.height))
        assertTrue(
            (source.height until timestamped.height).any { y ->
                (0 until timestamped.width).any { x ->
                    val rgb = timestamped.getRGB(x, y)
                    (rgb ushr 16 and 0xff) > 200 && (rgb ushr 8 and 0xff) > 200 && (rgb and 0xff) > 200
                }
            },
        )
        assertEquals("01:02:03.456", formatStoryboardTimestamp(3_723_456_000L))
    }

    private fun solidImage(color: Color): BufferedImage =
        BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().use { graphics ->
                graphics.color = color
                graphics.fillRect(0, 0, width, height)
            }
        }

    private fun BufferedImage.copy(): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().use { graphics -> graphics.drawImage(this@copy, 0, 0, null) }
        }

    private inline fun <T : java.awt.Graphics> T.use(block: (T) -> Unit) {
        try {
            block(this)
        } finally {
            dispose()
        }
    }
}
