package io.aequicor.media.desktop.ffmpeg

import io.aequicor.capture.core.VideoFramePoint
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MouseTrailSidecarTest {
    @Test
    fun preservesTheFullPathAndSplitsItAtAnInsertedImportantFrame() {
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
    fun drawsOneSemiTransparentTaperedRibbon() {
        val image = BufferedImage(100, 80, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.drawMouseTrail(
                listOf(
                    RecordedMousePoint(timestampMicros = 0, x = 10, y = 40),
                    RecordedMousePoint(timestampMicros = 1_000_000, x = 90, y = 40),
                ),
            )
        } finally {
            graphics.dispose()
        }

        assertTrue(image.getRGB(50, 40) ushr 24 in 1..254)
        assertTrue(image.getRGB(89, 27) ushr 24 > 0)
        assertEquals(0, image.getRGB(10, 35) ushr 24)
    }
}
