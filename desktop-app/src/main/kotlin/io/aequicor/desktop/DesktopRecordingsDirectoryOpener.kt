package io.aequicor.desktop

import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path

internal fun interface DesktopRecordingsDirectoryOpener {
    suspend fun openForOutput(outputPath: String): DesktopDirectoryOpenResult
}

internal sealed interface DesktopDirectoryOpenResult {
    data class Opened(val directory: String) : DesktopDirectoryOpenResult
    data class Unavailable(val message: String) : DesktopDirectoryOpenResult
}

internal data object UnavailableDesktopRecordingsDirectoryOpener : DesktopRecordingsDirectoryOpener {
    override suspend fun openForOutput(outputPath: String): DesktopDirectoryOpenResult =
        DesktopDirectoryOpenResult.Unavailable("Opening the recordings folder is unavailable.")
}

internal class AwtDesktopRecordingsDirectoryOpener(
    private val isHeadless: () -> Boolean = GraphicsEnvironment::isHeadless,
    private val isDesktopSupported: () -> Boolean = Desktop::isDesktopSupported,
    private val isOpenSupported: () -> Boolean = { Desktop.getDesktop().isSupported(Desktop.Action.OPEN) },
    private val openDirectory: (Path) -> Unit = { directory -> Desktop.getDesktop().open(directory.toFile()) },
) : DesktopRecordingsDirectoryOpener {
    override suspend fun openForOutput(outputPath: String): DesktopDirectoryOpenResult {
        if (outputPath.isBlank()) {
            return DesktopDirectoryOpenResult.Unavailable("Output path is empty.")
        }
        if (isHeadless() || !isDesktopSupported()) {
            return DesktopDirectoryOpenResult.Unavailable("Opening folders is unavailable on this desktop.")
        }
        return runCatching {
            val output = Path.of(outputPath).toAbsolutePath().normalize()
            val directory = output.parent
                ?: return@runCatching DesktopDirectoryOpenResult.Unavailable(
                    "Output path does not have a parent directory: $output",
                )
            Files.createDirectories(directory)
            if (!isOpenSupported()) {
                return@runCatching DesktopDirectoryOpenResult.Unavailable(
                    "Opening folders is not supported on this desktop.",
                )
            }
            openDirectory(directory)
            DesktopDirectoryOpenResult.Opened(directory.toString())
        }.getOrElse { failure ->
            DesktopDirectoryOpenResult.Unavailable(
                failure.message ?: "Could not open the recordings folder.",
            )
        }
    }
}
