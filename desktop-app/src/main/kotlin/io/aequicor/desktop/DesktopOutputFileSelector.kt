package io.aequicor.desktop

import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.io.FilenameFilter
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

internal fun interface DesktopOutputFileSelector {
    suspend fun chooseOutputFile(currentPath: String): DesktopOutputFileSelection
}

internal sealed interface DesktopOutputFileSelection {
    data class Selected(val path: String) : DesktopOutputFileSelection
    data object Cancelled : DesktopOutputFileSelection
    data class Unavailable(val message: String) : DesktopOutputFileSelection
}

internal data object UnavailableDesktopOutputFileSelector : DesktopOutputFileSelector {
    override suspend fun chooseOutputFile(currentPath: String): DesktopOutputFileSelection =
        DesktopOutputFileSelection.Unavailable("Output file selection is unavailable.")
}

internal class AwtDesktopOutputFileSelector : DesktopOutputFileSelector {
    override suspend fun chooseOutputFile(currentPath: String): DesktopOutputFileSelection =
        suspendCancellableCoroutine { continuation ->
            if (GraphicsEnvironment.isHeadless()) {
                continuation.resume(
                    DesktopOutputFileSelection.Unavailable("Output file selection is unavailable in headless mode."),
                )
                return@suspendCancellableCoroutine
            }

            val dialogReference = AtomicReference<FileDialog?>()
            val finished = AtomicBoolean(false)
            fun finish(result: DesktopOutputFileSelection) {
                if (!finished.compareAndSet(false, true)) {
                    return
                }
                dialogReference.getAndSet(null)?.dispose()
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            continuation.invokeOnCancellation {
                EventQueue.invokeLater {
                    if (finished.compareAndSet(false, true)) {
                        dialogReference.getAndSet(null)?.dispose()
                    }
                }
            }
            EventQueue.invokeLater {
                if (!continuation.isActive || finished.get()) {
                    return@invokeLater
                }
                runCatching {
                    val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow as? Frame
                    FileDialog(owner, "Mission Recorder", FileDialog.SAVE).also { dialog ->
                        dialogReference.set(dialog)
                        dialog.filenameFilter = FilenameFilter { _, name -> name.endsWith(".mp4", ignoreCase = true) }
                        currentPath.toInitialOutputPath()?.let { initial ->
                            dialog.directory = initial.parent?.toString()
                            dialog.file = initial.fileName?.toString()
                        }
                        dialog.isVisible = true
                    }
                }.onSuccess { dialog ->
                    val directory = dialog.directory
                    val fileName = dialog.file
                    if (directory == null || fileName.isNullOrBlank()) {
                        finish(DesktopOutputFileSelection.Cancelled)
                    } else {
                        finish(DesktopOutputFileSelection.Selected(normalizeMp4OutputPath(directory, fileName)))
                    }
                }.onFailure { failure ->
                    finish(
                        DesktopOutputFileSelection.Unavailable(
                            failure.message ?: "Unable to open the output file dialog.",
                        ),
                    )
                }
            }
        }
}

internal fun normalizeMp4OutputPath(directory: String, fileName: String): String {
    val mp4FileName = fileName.takeIf { it.endsWith(".mp4", ignoreCase = true) } ?: "$fileName.mp4"
    return Path.of(directory).resolve(mp4FileName).toAbsolutePath().normalize().toString()
}

private fun String.toInitialOutputPath(): Path? = takeIf(String::isNotBlank)
    ?.let { value -> runCatching { Path.of(value).toAbsolutePath().normalize() }.getOrNull() }
