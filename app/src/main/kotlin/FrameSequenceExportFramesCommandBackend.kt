package io.aequicor.app

import io.aequicor.cli.CliCommand
import io.aequicor.cli.ExportFramesCommandBackend
import io.aequicor.cli.ExportFramesCommandResult
import io.aequicor.export.ExportFramesException
import io.aequicor.export.ExportFramesFailureKind
import io.aequicor.export.ExportFramesSettings
import io.aequicor.export.ExportImageFormat
import io.aequicor.export.FrameSequenceExporter
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FrameSequenceExportFramesCommandBackend(
    private val exporter: FrameSequenceExporter,
) : ExportFramesCommandBackend {
    override suspend fun exportFrames(command: CliCommand.ExportFrames): ExportFramesCommandResult {
        val format = when (command.options.imageFormat.lowercase()) {
            "png" -> ExportImageFormat.Png
            "jpg", "jpeg" -> ExportImageFormat.Jpeg
            else -> return ExportFramesCommandResult.Unsupported(
                "Unsupported image format: ${command.options.imageFormat}. Supported formats: png, jpg.",
            )
        }
        val interval = command.options.interval?.parseExportInterval()
            ?: if (command.options.interval != null) {
                return ExportFramesCommandResult.Rejected("Invalid interval: ${command.options.interval}. Use values like 500ms or 1s.")
            } else {
                null
            }

        return try {
            val result = exporter.export(
                ExportFramesSettings(
                    inputPath = Path.of(command.options.inputPath).toAbsolutePath().normalize(),
                    outputDirectory = Path.of(command.options.outputDirectory).toAbsolutePath().normalize(),
                    targetFps = command.options.fps,
                    interval = interval,
                    imageFormat = format,
                    overwrite = command.options.overwrite,
                ),
            )
            ExportFramesCommandResult.Completed(
                outputDirectory = result.outputDirectory.toString(),
                sourceFrames = result.sourceFrames,
                exportedFrames = result.exportedFrames,
                imageFormat = result.imageFormat.extension,
            )
        } catch (exception: ExportFramesException) {
            when (exception.kind) {
                ExportFramesFailureKind.UnsupportedFormat -> ExportFramesCommandResult.Unsupported(exception.message)
                ExportFramesFailureKind.InvalidInput,
                ExportFramesFailureKind.InvalidSettings,
                ExportFramesFailureKind.OutputConflict,
                -> ExportFramesCommandResult.Rejected(exception.message)
            }
        } catch (exception: Throwable) {
            ExportFramesCommandResult.Failed(exception.message ?: exception::class.simpleName ?: "Frame export failed.")
        }
    }
}

private fun String.parseExportInterval(): Duration? {
    val trimmed = trim().lowercase()
    return when {
        trimmed.endsWith("ms") -> trimmed.removeSuffix("ms").toLongOrNull()?.milliseconds
        trimmed.endsWith("s") -> trimmed.removeSuffix("s").toLongOrNull()?.seconds
        else -> null
    }
}
