package io.aequicor.app

import io.aequicor.cli.CliCommand
import io.aequicor.cli.ExportFramesCommandResult
import io.aequicor.cli.ExportFramesOptions
import io.aequicor.export.FrameSequenceExporter
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FrameSequenceExportFramesCommandBackendTest {
    @Test
    fun exportsFramesFromFrameSequenceArtifact() = runTest {
        val recording = createRecording(frameRate = 4, frames = 4)
        val output = Files.createTempDirectory("mission-recorder-app-export")
        val backend = FrameSequenceExportFramesCommandBackend(FrameSequenceExporter())

        val result = backend.exportFrames(
            CliCommand.ExportFrames(
                ExportFramesOptions(
                    inputPath = recording.toString(),
                    outputDirectory = output.toString(),
                    fps = 2,
                ),
            ),
        )

        val completed = assertIs<ExportFramesCommandResult.Completed>(result)
        assertEquals(4, completed.sourceFrames)
        assertEquals(2, completed.exportedFrames)
        assertTrue(output.resolve("frame-000001.png").exists())
        assertTrue(output.resolve("frame-000002.png").exists())
    }

    private fun createRecording(frameRate: Int, frames: Int) =
        Files.createTempDirectory("mission-recorder-app-mrec").also { recording ->
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
