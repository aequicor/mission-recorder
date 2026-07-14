package io.aequicor.capture.platform

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MouseTrailRendererTest {
    @Test
    fun keepsOnlyTheConfiguredTimeWindow() {
        val renderer = MouseTrailOverlayRenderer(durationMicros = 2_000_000L)

        renderer.update(0L, 10, 20, 200, 100)
        renderer.update(1_000_000L, 40, 20, 200, 100)
        renderer.update(3_100_000L, 90, 20, 200, 100)

        assertEquals(listOf(3_100_000L), renderer.snapshot().map(MouseTrailPoint::timestampMicros))
    }

    @Test
    fun keepsThePreviousTwoSecondsByDefault() {
        val renderer = MouseTrailOverlayRenderer()

        renderer.update(0L, 10, 20, 200, 100)
        renderer.update(1_900_000L, 40, 20, 200, 100)

        assertEquals(listOf(0L, 1_900_000L), renderer.snapshot().map(MouseTrailPoint::timestampMicros))

        renderer.update(2_000_001L, 90, 20, 200, 100)

        assertEquals(listOf(1_900_000L, 2_000_001L), renderer.snapshot().map(MouseTrailPoint::timestampMicros))
    }

    @Test
    fun resetsTheRouteWhenFrameDimensionsChange() {
        val renderer = MouseTrailOverlayRenderer()
        renderer.update(0L, 10, 20, 200, 100)
        renderer.update(500_000L, 40, 20, 200, 100)

        renderer.update(600_000L, 20, 20, 100, 100)

        assertEquals(listOf(20), renderer.snapshot().map(MouseTrailPoint::x))
    }

    @Test
    fun paintsRgbaAndBgraFramesWithTheSameBlueTrail() {
        val rgba = opaqueWhiteFrame(width = 120, height = 60)
        val bgra = opaqueWhiteFrame(width = 120, height = 60)
        val renderer = MouseTrailOverlayRenderer()
        renderer.update(0L, 10, 30, 120, 60)
        renderer.update(1_000_000L, 110, 30, 120, 60)

        renderer.drawRgba(rgba, 120, 60)
        renderer.drawBgra(bgra, 120, 60)

        assertTrue(rgba.redAt(90, 30, 120, blueFirst = false) < rgba.blueAt(90, 30, 120, blueFirst = false))
        assertTrue(bgra.redAt(90, 30, 120, blueFirst = true) < bgra.blueAt(90, 30, 120, blueFirst = true))
        assertEquals(0xff, rgba.alphaAt(90, 30, 120))
        assertEquals(0xff, bgra.alphaAt(90, 30, 120))
    }

    @Test
    fun bandBoundariesDoNotCreateRoundDots() {
        val image = BufferedImage(260, 90, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            MouseTrailPainter.draw(
                graphics,
                listOf(
                    MouseTrailPoint(timestampMicros = 0L, x = 10, y = 45),
                    MouseTrailPoint(timestampMicros = 2_000_000L, x = 250, y = 45),
                ),
            )
        } finally {
            graphics.dispose()
        }

        for (band in 1 until 23) {
            val boundaryX = 10 + band * 10
            val boundaryAlpha = image.alphaAt(boundaryX, 45)
            val neighboringAlpha = maxOf(image.alphaAt(boundaryX - 2, 45), image.alphaAt(boundaryX + 2, 45))
            assertTrue(boundaryAlpha <= neighboringAlpha + 8, "Unexpected opacity spike at band $band")
        }
    }

    @Test
    fun stationaryTrailFadesRelativeToTheCurrentFrame() {
        val points = listOf(
            MouseTrailPoint(timestampMicros = 0L, x = 10, y = 45),
            MouseTrailPoint(timestampMicros = 500_000L, x = 110, y = 45),
        )

        val fresh = render(points, referenceTimestampMicros = 500_000L)
        val faded = render(points, referenceTimestampMicros = 900_000L)
        val expired = render(points, referenceTimestampMicros = 2_500_000L)

        assertTrue(fresh.alphaAt(100, 45) > faded.alphaAt(100, 45))
        assertTrue(faded.alphaAt(100, 45) > 0)
        assertTrue(expired.pixels().all { pixel -> pixel ushr 24 == 0 })
    }

    @Test
    fun keepsAnOlderStationaryTrailReadableBeforeItExpires() {
        val image = render(
            points = listOf(
                MouseTrailPoint(timestampMicros = 0L, x = 10, y = 45),
                MouseTrailPoint(timestampMicros = 550_000L, x = 110, y = 45),
            ),
            referenceTimestampMicros = 1_500_000L,
        )

        assertTrue(image.alphaAt(100, 45) >= 60)
    }

    @Test
    fun normalizesVisualDirectionTowardTheNewestPoint() {
        val image = render(
            points = listOf(
                MouseTrailPoint(timestampMicros = 0L, x = 110, y = 45),
                MouseTrailPoint(timestampMicros = 50_000L, x = 10, y = 45),
            ),
            referenceTimestampMicros = 1_000_000L,
        )

        val oldEndAlpha = image.alphaAt(100, 45)
        val cursorEndAlpha = image.alphaAt(20, 45)
        assertTrue(cursorEndAlpha >= oldEndAlpha * 2)
        assertTrue(image.blueAt(20, 45) - image.redAt(20, 45) > image.blueAt(100, 45) - image.redAt(100, 45))
    }

    @Test
    fun normalizesVisualDirectionAcrossMovedDistanceAfterTheCursorStops() {
        val image = render(
            points = listOf(
                MouseTrailPoint(timestampMicros = 0L, x = 10, y = 45),
                MouseTrailPoint(timestampMicros = 100_000L, x = 110, y = 45),
                MouseTrailPoint(timestampMicros = 900_000L, x = 110, y = 45),
            ),
            referenceTimestampMicros = 900_000L,
        )

        val oldEndAlpha = image.alphaAt(20, 45)
        val cursorEndAlpha = image.alphaAt(100, 45)
        assertTrue(cursorEndAlpha >= oldEndAlpha * 2)
        assertTrue(image.blueAt(100, 45) - image.redAt(100, 45) > image.blueAt(20, 45) - image.redAt(20, 45))
    }

    @Test
    fun usesThreeTemporalFadePhasesAndDesaturatesTheOldTail() {
        val image = render(
            points = listOf(
                MouseTrailPoint(timestampMicros = 0L, x = 10, y = 45),
                MouseTrailPoint(timestampMicros = 2_000_000L, x = 210, y = 45),
            ),
            referenceTimestampMicros = 2_000_000L,
            width = 220,
        )

        val oldAlpha = image.alphaAt(50, 45)
        val mediumAlpha = image.alphaAt(155, 45)
        val recentAlpha = image.alphaAt(190, 45)
        assertTrue(oldAlpha in 1 until mediumAlpha)
        assertTrue(mediumAlpha < recentAlpha)
        assertTrue(
            image.blueAt(50, 45) - image.redAt(50, 45) <
                image.blueAt(190, 45) - image.redAt(190, 45),
        )
    }

    @Test
    fun limitsFastMovementByVisibleRouteLength() {
        val image = render(
            points = listOf(
                MouseTrailPoint(timestampMicros = 0L, x = 10, y = 50),
                MouseTrailPoint(timestampMicros = 1_000_000L, x = 290, y = 50),
            ),
            referenceTimestampMicros = 1_000_000L,
            maximumLengthPixels = 80.0,
            width = 300,
            height = 100,
        )

        assertEquals(0, image.alphaAt(195, 50))
        assertTrue(image.alphaAt(220, 50) > 0)
        assertTrue(image.alphaAt(240, 50) > 0)
    }

    @Test
    fun painterRestoresGraphicsState() {
        val image = BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        val originalColor = Color.MAGENTA
        val originalStroke = BasicStroke(7f)
        graphics.color = originalColor
        graphics.stroke = originalStroke
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        val originalHints = graphics.renderingHints
        try {
            MouseTrailPainter.draw(
                graphics,
                listOf(
                    MouseTrailPoint(0L, 10, 40),
                    MouseTrailPoint(1_000_000L, 110, 40),
                ),
            )

            assertEquals(originalColor, graphics.color)
            assertEquals(originalStroke, graphics.stroke)
            assertEquals(originalHints, graphics.renderingHints)
        } finally {
            graphics.dispose()
        }
    }

    private fun opaqueWhiteFrame(width: Int, height: Int): ByteArray =
        ByteArray(width * height * 4) { 0xff.toByte() }

    private fun render(
        points: List<MouseTrailPoint>,
        referenceTimestampMicros: Long,
        maximumLengthPixels: Double = Double.POSITIVE_INFINITY,
        width: Int = 120,
        height: Int = 90,
    ): BufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { image ->
        val graphics = image.createGraphics()
        try {
            MouseTrailPainter.draw(
                graphics = graphics,
                points = points,
                referenceTimestampMicros = referenceTimestampMicros,
                maximumLengthPixels = maximumLengthPixels,
            )
        } finally {
            graphics.dispose()
        }
    }

    private fun ByteArray.redAt(x: Int, y: Int, width: Int, blueFirst: Boolean): Int {
        val offset = (y * width + x) * 4
        return this[offset + if (blueFirst) 2 else 0].toInt() and 0xff
    }

    private fun ByteArray.blueAt(x: Int, y: Int, width: Int, blueFirst: Boolean): Int {
        val offset = (y * width + x) * 4
        return this[offset + if (blueFirst) 0 else 2].toInt() and 0xff
    }

    private fun ByteArray.alphaAt(x: Int, y: Int, width: Int): Int =
        this[(y * width + x) * 4 + 3].toInt() and 0xff

    private fun BufferedImage.alphaAt(x: Int, y: Int): Int = getRGB(x, y) ushr 24

    private fun BufferedImage.redAt(x: Int, y: Int): Int = getRGB(x, y) ushr 16 and 0xff

    private fun BufferedImage.blueAt(x: Int, y: Int): Int = getRGB(x, y) and 0xff

    private fun BufferedImage.pixels(): IntArray = getRGB(0, 0, width, height, null, 0, width)
}
