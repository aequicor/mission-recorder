package io.aequicor.app

import io.aequicor.cli.CliCommand
import io.aequicor.cli.ExportFramesCommandBackend
import io.aequicor.cli.ExportFramesCommandResult
import io.aequicor.cli.FrameExportLayout
import io.aequicor.media.desktop.ffmpeg.StoryboardExportException
import io.aequicor.media.desktop.ffmpeg.StoryboardExportSettings
import io.aequicor.media.desktop.ffmpeg.StoryboardExporter
import io.aequicor.media.desktop.ffmpeg.StoryboardLayout
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DesktopExportFramesCommandBackend(
    private val legacyFrameSequenceBackend: ExportFramesCommandBackend,
    private val storyboardExporter: StoryboardExporter,
) : ExportFramesCommandBackend {
    override suspend fun exportFrames(command: CliCommand.ExportFrames): ExportFramesCommandResult {
        val input = Path.of(command.options.inputPath).toAbsolutePath().normalize()
        if (input.extension.equals("mrec", ignoreCase = true)) {
            return legacyFrameSequenceBackend.exportFrames(command)
        }
        if (!command.options.imageFormat.equals("png", ignoreCase = true)) {
            return ExportFramesCommandResult.Unsupported("Video storyboard export supports PNG only.")
        }
        if (command.options.overwrite) {
            return ExportFramesCommandResult.Unsupported(
                "--overwrite is not supported for video storyboard export; choose a new output path.",
            )
        }
        val interval = command.options.resolveInterval()
            ?: return ExportFramesCommandResult.Rejected(
                "Invalid frame interval. Use a positive --fps or an interval such as 500ms or 1s.",
            )
        val output = Path.of(command.options.outputDirectory).toAbsolutePath().normalize()
        return try {
            val result = storyboardExporter.export(
                StoryboardExportSettings(
                    inputVideo = input,
                    outputPath = output,
                    layout = when (command.options.layout) {
                        FrameExportLayout.SeparatePngFiles -> StoryboardLayout.SeparatePngFiles
                        FrameExportLayout.ContactSheet -> StoryboardLayout.ContactSheet
                    },
                    interval = interval,
                ),
            )
            ExportFramesCommandResult.Completed(
                outputDirectory = output.toString(),
                sourceFrames = result.sourceFrameCount,
                exportedFrames = result.frameCount,
                imageFormat = "png",
            )
        } catch (exception: StoryboardExportException) {
            ExportFramesCommandResult.Rejected(exception.message ?: "Storyboard export was rejected.")
        } catch (exception: Throwable) {
            ExportFramesCommandResult.Failed(
                exception.message ?: exception::class.simpleName ?: "Storyboard export failed.",
            )
        }
    }
}

private fun io.aequicor.cli.ExportFramesOptions.resolveInterval(): Duration? {
    fps?.let { framesPerSecond ->
        if (framesPerSecond <= 0) {
            return null
        }
        return 1.seconds / framesPerSecond
    }
    val value = interval ?: return 1.seconds
    val normalized = value.trim().lowercase()
    val duration = when {
        normalized.endsWith("ms") -> normalized.removeSuffix("ms").toLongOrNull()?.milliseconds
        normalized.endsWith("s") -> normalized.removeSuffix("s").toLongOrNull()?.seconds
        else -> null
    }
    return duration?.takeIf(Duration::isPositive)
}
