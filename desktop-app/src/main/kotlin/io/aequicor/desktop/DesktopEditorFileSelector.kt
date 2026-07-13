package io.aequicor.desktop

import io.aequicor.editor.ImportantFrameLayout
import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

internal interface DesktopEditorFileSelector {
    suspend fun chooseMediaFiles(): List<String>
    suspend fun chooseReplacementFile(currentPath: String): String?
    suspend fun chooseVideoOutput(primaryMediaPath: String): String?
    suspend fun chooseFrameOutput(primaryMediaPath: String, layout: ImportantFrameLayout): String?
}

internal class AwtDesktopEditorFileSelector : DesktopEditorFileSelector {
    override suspend fun chooseMediaFiles(): List<String> = showDialog(
        title = "Add media",
        mode = FileDialog.LOAD,
        multiple = true,
    )?.files.orEmpty()

    override suspend fun chooseReplacementFile(currentPath: String): String? = showDialog(
        title = "Relink media",
        mode = FileDialog.LOAD,
        initialPath = currentPath,
    )?.files?.singleOrNull()

    override suspend fun chooseVideoOutput(primaryMediaPath: String): String? {
        val primary = Path.of(primaryMediaPath).toAbsolutePath().normalize()
        val suggested = primary.resolveSibling("${primary.fileName.toString().substringBeforeLast('.')}-edited.mp4")
        return showDialog("Export video", FileDialog.SAVE, initialPath = suggested.toString())
            ?.files?.singleOrNull()
            ?.let(::ensureMp4Extension)
    }

    override suspend fun chooseFrameOutput(primaryMediaPath: String, layout: ImportantFrameLayout): String? {
        val primary = Path.of(primaryMediaPath).toAbsolutePath().normalize()
        val stem = primary.fileName.toString().substringBeforeLast('.')
        val suggested = when (layout) {
            ImportantFrameLayout.SeparatePngFiles -> primary.resolveSibling("$stem-important-frames")
            ImportantFrameLayout.ContactSheet -> primary.resolveSibling("$stem-important-frames.png")
        }
        val selected = showDialog("Export important frames", FileDialog.SAVE, initialPath = suggested.toString())
            ?.files?.singleOrNull()
            ?: return null
        return if (layout == ImportantFrameLayout.ContactSheet) ensurePngExtension(selected) else selected
    }

    private suspend fun showDialog(
        title: String,
        mode: Int,
        multiple: Boolean = false,
        initialPath: String? = null,
    ): DialogResult? = suspendCancellableCoroutine { continuation ->
        if (GraphicsEnvironment.isHeadless()) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        val dialogReference = AtomicReference<FileDialog?>()
        val finished = AtomicBoolean(false)
        fun finish(result: DialogResult?) {
            if (!finished.compareAndSet(false, true)) return
            dialogReference.getAndSet(null)?.dispose()
            if (continuation.isActive) continuation.resume(result)
        }
        continuation.invokeOnCancellation {
            EventQueue.invokeLater {
                if (finished.compareAndSet(false, true)) dialogReference.getAndSet(null)?.dispose()
            }
        }
        EventQueue.invokeLater {
            if (!continuation.isActive || finished.get()) return@invokeLater
            runCatching {
                val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow as? Frame
                FileDialog(owner, title, mode).also { dialog ->
                    dialogReference.set(dialog)
                    dialog.isMultipleMode = multiple
                    initialPath?.toInitialPath()?.let { initial ->
                        dialog.directory = initial.parent?.toString()
                        dialog.file = initial.fileName?.toString()
                    }
                    dialog.isVisible = true
                }
            }.onSuccess { dialog ->
                val paths = if (multiple) {
                    dialog.files.map { it.toPath().toAbsolutePath().normalize().toString() }
                } else {
                    val directory = dialog.directory
                    val file = dialog.file
                    if (directory == null || file.isNullOrBlank()) emptyList()
                    else listOf(Path.of(directory).resolve(file).toAbsolutePath().normalize().toString())
                }
                finish(paths.takeIf(List<String>::isNotEmpty)?.let(::DialogResult))
            }.onFailure { finish(null) }
        }
    }
}

private data class DialogResult(val files: List<String>)

private fun String.toInitialPath(): Path? = runCatching { Path.of(this).toAbsolutePath().normalize() }.getOrNull()
private fun ensureMp4Extension(path: String): String = if (path.endsWith(".mp4", true)) path else "$path.mp4"
private fun ensurePngExtension(path: String): String = if (path.endsWith(".png", true)) path else "$path.png"
