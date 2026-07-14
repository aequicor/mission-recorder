package io.aequicor.media.desktop.ffmpeg

import io.aequicor.capture.core.VideoFramePoint
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MouseTrailSidecarTest {
    @Test
    fun preservesTheFullPathAndReturnsRequestedRanges() {
        val recording = Files.createTempFile("mission-recorder-trail", ".mp4")
        try {
            val recorder = MouseTrailRecorder()
            recorder.record(timestampMicros = 2_000_000, position = VideoFramePoint(10, 20))
            recorder.record(timestampMicros = 3_000_000, position = VideoFramePoint(40, 20))
            recorder.record(timestampMicros = 4_000_000, position = VideoFramePoint(80, 20))

            assertTrue(recorder.write(recording))
            val trail = assertNotNull(MouseTrail.load(recording.toString()))

            assertEquals(
                listOf(
                    RecordedMousePoint(timestampMicros = 0, x = 10, y = 20),
                    RecordedMousePoint(timestampMicros = 1_000_000, x = 40, y = 20),
                ),
                trail.pointsBetween(startMicros = 0, endMicros = 1_500_000),
            )
            assertEquals(
                listOf(
                    RecordedMousePoint(timestampMicros = 1_000_000, x = 40, y = 20),
                    RecordedMousePoint(timestampMicros = 2_000_000, x = 80, y = 20),
                ),
                trail.pointsBetween(startMicros = 1_500_000, endMicros = 2_000_000),
            )
        } finally {
            Files.deleteIfExists(mouseTrailSidecarPath(recording))
            Files.deleteIfExists(recording)
        }
    }

    @Test
    fun returnsAnInterpolatedBoundaryForAStrictTimeWindow() {
        val recording = Files.createTempFile("mission-recorder-trail-window", ".mp4")
        try {
            val recorder = MouseTrailRecorder()
            recorder.record(timestampMicros = 0L, position = VideoFramePoint(0, 20))
            recorder.record(timestampMicros = 1_000_000L, position = VideoFramePoint(50, 20))
            recorder.record(timestampMicros = 2_000_000L, position = VideoFramePoint(100, 20))
            assertTrue(recorder.write(recording))

            val trail = assertNotNull(MouseTrail.load(recording.toString()))

            assertEquals(
                listOf(
                    RecordedMousePoint(timestampMicros = 500_000L, x = 25, y = 20),
                    RecordedMousePoint(timestampMicros = 1_000_000L, x = 50, y = 20),
                    RecordedMousePoint(timestampMicros = 2_000_000L, x = 100, y = 20),
                ),
                trail.pointsWithin(startMicros = 500_000L, endMicros = 2_000_000L),
            )
        } finally {
            Files.deleteIfExists(mouseTrailSidecarPath(recording))
            Files.deleteIfExists(recording)
        }
    }

    @Test
    fun keepsTimelineOriginWhenCursorEntersTheFrameLater() {
        val recording = Files.createTempFile("mission-recorder-trail-origin", ".mp4")
        try {
            val recorder = MouseTrailRecorder()
            recorder.record(timestampMicros = 2_000_000L, position = null)
            recorder.record(timestampMicros = 3_000_000L, position = VideoFramePoint(40, 20))
            assertTrue(recorder.write(recording))

            val trail = assertNotNull(MouseTrail.load(recording.toString()))

            assertEquals(
                listOf(RecordedMousePoint(timestampMicros = 1_000_000L, x = 40, y = 20)),
                trail.pointsBetween(startMicros = 0L, endMicros = 1_000_000L),
            )
        } finally {
            Files.deleteIfExists(mouseTrailSidecarPath(recording))
            Files.deleteIfExists(recording)
        }
    }

    @Test
    fun drawsStraightTrailWithOlderEndThinnerAndMoreTransparent() {
        val image = renderTrail(
            listOf(
                RecordedMousePoint(timestampMicros = 0, x = 10, y = 40),
                RecordedMousePoint(timestampMicros = 1_500_000, x = 110, y = 40),
            ),
        )

        val olderAlpha = image.alphaAt(20, 40)
        val newerAlpha = image.alphaAt(100, 40)
        assertTrue(olderAlpha in 1 until newerAlpha)
        assertEquals(0, image.alphaAt(20, 45))
        assertTrue(image.alphaAt(100, 47) > 0)
    }

    @Test
    fun doesNotFillTheInsideOfAUTurn() {
        val image = renderTrail(
            listOf(
                RecordedMousePoint(timestampMicros = 0, x = 10, y = 20),
                RecordedMousePoint(timestampMicros = 333_333, x = 110, y = 20),
                RecordedMousePoint(timestampMicros = 666_666, x = 110, y = 80),
                RecordedMousePoint(timestampMicros = 1_000_000, x = 10, y = 80),
            ),
            height = 100,
        )

        assertTrue(image.alphaAt(60, 20) > 0)
        assertTrue(image.alphaAt(110, 50) > 0)
        assertTrue(image.alphaAt(60, 80) > 0)
        assertEquals(0, image.alphaAt(60, 50))
    }

    @Test
    fun doesNotFillAreasEnclosedByASelfIntersectingPath() {
        val image = renderTrail(
            listOf(
                RecordedMousePoint(timestampMicros = 0, x = 10, y = 10),
                RecordedMousePoint(timestampMicros = 250_000, x = 110, y = 80),
                RecordedMousePoint(timestampMicros = 500_000, x = 10, y = 80),
                RecordedMousePoint(timestampMicros = 750_000, x = 110, y = 10),
                RecordedMousePoint(timestampMicros = 1_000_000, x = 10, y = 10),
            ),
            height = 100,
        )

        assertTrue(image.alphaAt(60, 45) > 0)
        assertEquals(0, image.alphaAt(60, 25))
        assertEquals(0, image.alphaAt(60, 65))
    }

    @Test
    fun usesPointOrderWhenAllTimestampsMatch() {
        val points = listOf(
            RecordedMousePoint(timestampMicros = 1_000_000, x = 10, y = 40),
            RecordedMousePoint(timestampMicros = 1_000_000, x = 60, y = 40),
            RecordedMousePoint(timestampMicros = 1_000_000, x = 110, y = 40),
        )

        val first = renderTrail(points)
        val second = renderTrail(points)

        assertContentEquals(first.pixels(), second.pixels())
        assertTrue(first.alphaAt(20, 40) < first.alphaAt(100, 40))
    }

    @Test
    fun doesNotDrawWithoutAVisibleSegment() {
        val invisibleTrails = listOf(
            emptyList(),
            listOf(RecordedMousePoint(timestampMicros = 0, x = 10, y = 40)),
            listOf(
                RecordedMousePoint(timestampMicros = 0, x = 10, y = 40),
                RecordedMousePoint(timestampMicros = 1_000_000, x = 10, y = 40),
            ),
        )

        invisibleTrails.forEach { points ->
            assertTrue(renderTrail(points).pixels().all { pixel -> pixel ushr 24 == 0 })
        }
    }

    @Test
    fun restoresGraphicsStateAfterDrawing() {
        val image = BufferedImage(120, 90, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        val originalColor = Color.MAGENTA
        val originalStroke = BasicStroke(7f)
        graphics.color = originalColor
        graphics.stroke = originalStroke
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        val originalHints = graphics.renderingHints
        try {
            graphics.drawMouseTrail(
                listOf(
                    RecordedMousePoint(timestampMicros = 0, x = 10, y = 40),
                    RecordedMousePoint(timestampMicros = 1_000_000, x = 110, y = 40),
                ),
            )

            assertEquals(originalColor, graphics.color)
            assertEquals(originalStroke, graphics.stroke)
            assertEquals(originalHints, graphics.renderingHints)
        } finally {
            graphics.dispose()
        }
    }

    private fun renderTrail(
        points: List<RecordedMousePoint>,
        width: Int = 120,
        height: Int = 90,
    ): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.drawMouseTrail(points)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun BufferedImage.alphaAt(x: Int, y: Int): Int = getRGB(x, y) ushr 24

    private fun BufferedImage.pixels(): IntArray = getRGB(0, 0, width, height, null, 0, width)
}
