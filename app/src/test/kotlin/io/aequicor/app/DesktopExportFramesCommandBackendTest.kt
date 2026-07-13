package io.aequicor.app

import io.aequicor.cli.CliCommand
import io.aequicor.cli.ExportFramesCommandBackend
import io.aequicor.cli.ExportFramesCommandResult
import io.aequicor.cli.ExportFramesOptions
import io.aequicor.cli.FrameExportLayout
import io.aequicor.media.desktop.ffmpeg.StoryboardExportResult
import io.aequicor.media.desktop.ffmpeg.StoryboardExportSettings
import io.aequicor.media.desktop.ffmpeg.StoryboardExporter
import io.aequicor.media.desktop.ffmpeg.StoryboardLayout
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class DesktopExportFramesCommandBackendTest {
    @Test
    fun mapsContactSheetOptionToSinglePngExport() = runTest {
        var received: StoryboardExportSettings? = null
        val backend = DesktopExportFramesCommandBackend(
            legacyFrameSequenceBackend = RejectingLegacyBackend,
            storyboardExporter = StoryboardExporter { settings ->
                received = settings
                StoryboardExportResult(
                    layout = settings.layout,
                    frameCount = 5,
                    outputPaths = listOf(settings.outputPath),
                    sourceFrameCount = 8,
                )
            },
        )
        val command = CliCommand.ExportFrames(
            ExportFramesOptions(
                inputPath = "recording.mp4",
                outputDirectory = "storyboard.png",
                interval = "2s",
                layout = FrameExportLayout.ContactSheet,
            ),
        )

        val result = backend.exportFrames(command)

        val completed = assertIs<ExportFramesCommandResult.Completed>(result)
        assertEquals(8, completed.sourceFrames)
        assertEquals(5, completed.exportedFrames)
        val settings = requireNotNull(received)
        assertEquals(StoryboardLayout.ContactSheet, settings.layout)
        assertEquals(2.seconds, settings.interval)
        assertEquals(
            Path.of("storyboard.png").toAbsolutePath().normalize(),
            settings.outputPath,
        )
    }
}

private data object RejectingLegacyBackend : ExportFramesCommandBackend {
    override suspend fun exportFrames(command: CliCommand.ExportFrames): ExportFramesCommandResult =
        error("Legacy backend must not handle MP4 input.")
}
