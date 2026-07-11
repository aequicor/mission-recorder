package io.aequicor.export

import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class FrameSequenceExporterTest {
    @Test
    fun exportsAllFramesByDefault() = runTest {
        val recording = createRecording(frameRate = 30, frames = 3)
        val output = Files.createTempDirectory("mission-recorder-export-out")

        val result = FrameSequenceExporter().export(
            ExportFramesSettings(inputPath = recording, outputDirectory = output),
        )

        assertEquals(3, result.sourceFrames)
        assertEquals(3, result.exportedFrames)
        assertTrue(output.resolve("frame-000001.png").exists())
        assertTrue(output.resolve("frame-000003.png").exists())
    }

    @Test
    fun exportsSelectedFramesByTargetFps() = runTest {
        val recording = createRecording(frameRate = 4, frames = 4)
        val output = Files.createTempDirectory("mission-recorder-export-fps")

        val result = FrameSequenceExporter().export(
            ExportFramesSettings(
                inputPath = recording,
                outputDirectory = output,
                targetFps = 2,
            ),
        )

        assertEquals(4, result.sourceFrames)
        assertEquals(2, result.exportedFrames)
        assertTrue(output.resolve("frame-000001.png").exists())
        assertTrue(output.resolve("frame-000002.png").exists())
    }

    @Test
    fun exportsSelectedFramesByInterval() = runTest {
        val recording = createRecording(frameRate = 10, frames = 5)
        val output = Files.createTempDirectory("mission-recorder-export-interval")

        val result = FrameSequenceExporter().export(
            ExportFramesSettings(
                inputPath = recording,
                outputDirectory = output,
                interval = 200.milliseconds,
            ),
        )

        assertEquals(3, result.exportedFrames)
    }

    @Test
    fun rejectsExistingOutputWithoutOverwrite() = runTest {
        val recording = createRecording(frameRate = 30, frames = 1)
        val output = Files.createTempDirectory("mission-recorder-export-conflict")
        Files.writeString(output.resolve("frame-000001.png"), "existing")

        val exception = assertFailsWith<ExportFramesException> {
            FrameSequenceExporter().export(
                ExportFramesSettings(inputPath = recording, outputDirectory = output),
            )
        }

        assertEquals(ExportFramesFailureKind.OutputConflict, exception.kind)
    }

    private fun createRecording(frameRate: Int, frames: Int) =
        Files.createTempDirectory("mission-recorder-mrec").also { recording ->
            val framesDirectory = recording.resolve("frames")
            Files.createDirectories(framesDirectory)
            Files.writeString(
                recording.resolve("recording.json"),
                """
                {
                  "format": "mission-recorder-frame-sequence",
                  "frameRate": $frameRate,
                  "videoFrames": $frames
                }
                """.trimIndent(),
            )
            repeat(frames) { index ->
                val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
                image.setRGB(0, 0, 0xff000000.toInt() or index)
                ImageIO.write(image, "png", framesDirectory.resolve("frame-${(index + 1).toString().padStart(6, '0')}.png").toFile())
            }
        }
}
