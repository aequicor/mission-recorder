package io.aequicor.capture.desktop.awt

import io.aequicor.capture.core.CaptureRegion
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.RecordingSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AwtVideoCaptureAdapterTest {
    @Test
    fun convertsAwtArgbToRgbaWithoutSwappingColorChannels() = runBlocking {
        val adapter = adapter(pointer = Point(10, 10), color = Color(12, 34, 56))

        val frame = adapter.frames(settings(captureCursor = false)).first()

        assertContentEquals(byteArrayOf(12, 34, 56, 0xff.toByte()), requireNotNull(frame.pixelData).copyOfRange(0, 4))
    }

    @Test
    fun drawsCursorAtPointerLocationWhenEnabled() = runBlocking {
        val adapter = adapter(pointer = Point(105, 206))

        val frame = adapter.frames(settings(captureCursor = true)).first()

        assertTrue(requireNotNull(frame.pixelData).containsPixelOtherThanWhite())
    }

    @Test
    fun drawsHighlightAroundPointerHotspot() = runBlocking {
        val adapter = adapter(pointer = Point(116, 216))

        val frame = adapter.frames(settings(captureCursor = true)).first()

        assertFalse(requireNotNull(frame.pixelData).isWhitePixel(x = 6, y = 16, width = 32))
    }

    @Test
    fun leavesFrameUntouchedWhenCursorCaptureIsDisabled() = runBlocking {
        val adapter = adapter(pointer = Point(105, 206))

        val frame = adapter.frames(settings(captureCursor = false)).first()

        assertEquals(0, requireNotNull(frame.pixelData).countPixelsOtherThanWhite())
    }

    @Test
    fun leavesFrameUntouchedWhenPointerIsOutsideCaptureRegion() = runBlocking {
        val adapter = adapter(pointer = Point(10, 10))

        val frame = adapter.frames(settings(captureCursor = true)).first()

        assertEquals(0, requireNotNull(frame.pixelData).countPixelsOtherThanWhite())
    }

    @Test
    fun paintsMouseTrailIntoSubsequentVideoFrames() = runBlocking {
        val pointers = listOf(Point(105, 206), Point(120, 206))
        var pointerIndex = 0
        var currentTime = 0L
        val adapter = AwtVideoCaptureAdapter(
            screenGrabberFactory = {
                ScreenGrabber { rectangle -> solidImage(rectangle.width, rectangle.height, Color.WHITE) }
            },
            pointerLocationProvider = PointerLocationProvider {
                pointers.getOrElse(pointerIndex++) { pointers.last() }
            },
            isHeadless = { false },
            nanoTime = {
                currentTime += 100_000_000L
                currentTime
            },
        )

        val frames = adapter.frames(settings(captureCursor = false, showMouseTrail = true)).take(2).toList()

        assertFalse(requireNotNull(frames.last().pixelData).isWhitePixel(x = 12, y = 6, width = 32))
    }

    private fun adapter(pointer: Point, color: Color = Color.WHITE) = AwtVideoCaptureAdapter(
        screenGrabberFactory = {
            ScreenGrabber { rectangle -> solidImage(rectangle.width, rectangle.height, color) }
        },
        pointerLocationProvider = PointerLocationProvider { pointer },
        isHeadless = { false },
        nanoTime = { 1L },
    )

    private fun settings(captureCursor: Boolean, showMouseTrail: Boolean = false) = RecordingSettings(
        captureSource = CaptureSource.Region(
            id = CaptureSourceId("region:test"),
            displayName = "Test region",
            region = CaptureRegion(x = 100, y = 200, width = 32, height = 32),
        ),
        outputPath = "unused.mp4",
        frameRate = 30,
        captureCursor = captureCursor,
        showMouseTrail = showMouseTrail,
    )
}

private fun solidImage(width: Int, height: Int, color: Color): BufferedImage =
    BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { image ->
        image.createGraphics().use { graphics ->
            graphics.color = color
            graphics.fillRect(0, 0, width, height)
        }
    }

private fun ByteArray.containsPixelOtherThanWhite(): Boolean = countPixelsOtherThanWhite() > 0

private fun ByteArray.countPixelsOtherThanWhite(): Int =
    asList()
        .chunked(RGBA_CHANNEL_COUNT)
        .count { pixel -> pixel.any { channel -> channel.toInt() and 0xff != 0xff } }

private fun ByteArray.isWhitePixel(x: Int, y: Int, width: Int): Boolean {
    val offset = (y * width + x) * RGBA_CHANNEL_COUNT
    return copyOfRange(offset, offset + RGBA_CHANNEL_COUNT)
        .all { channel -> channel.toInt() and 0xff == 0xff }
}

private inline fun <T : java.awt.Graphics> T.use(block: (T) -> Unit) {
    try {
        block(this)
    } finally {
        dispose()
    }
}

private const val RGBA_CHANNEL_COUNT = 4
