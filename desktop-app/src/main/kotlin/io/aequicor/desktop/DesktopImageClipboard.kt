package io.aequicor.desktop

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import javax.imageio.ImageIO
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.path.extension

internal interface DesktopImageClipboard {
    suspend fun copyImages(paths: List<String>)
}

internal class AwtDesktopImageClipboard(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DesktopImageClipboard {
    override suspend fun copyImages(paths: List<String>) {
        require(paths.isNotEmpty()) { "Select at least one image to copy." }
        val snapshot = withContext(ioDispatcher) { createClipboardSnapshot(paths) }
        suspendCancellableCoroutine { continuation ->
            EventQueue.invokeLater {
                if (!continuation.isActive) {
                    scheduleDelete(snapshot.directory)
                    return@invokeLater
                }
                runCatching {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        ImageFilesTransferable(snapshot.files, snapshot.firstImage),
                        ClipboardSnapshotOwner(snapshot.directory, ioDispatcher),
                    )
                }.onSuccess {
                    continuation.resume(Unit)
                }.onFailure { failure ->
                    scheduleDelete(snapshot.directory)
                    continuation.resumeWithException(failure)
                }
            }
        }
    }

    private fun createClipboardSnapshot(paths: List<String>): ClipboardSnapshot {
        val directory = Files.createTempDirectory("mission-recorder-clipboard-snapshot-")
        directory.toFile().deleteOnExit()
        var completed = false
        return try {
            val files = paths.mapIndexed { index, value ->
                val source = Path.of(value).toAbsolutePath().normalize()
                require(Files.isRegularFile(source)) { "Clipboard image does not exist: $source" }
                val extension = source.extension.takeIf(String::isNotBlank) ?: "png"
                val target = directory.resolve("frame-${(index + 1).toString().padStart(6, '0')}.$extension")
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING).toFile().also(File::deleteOnExit)
            }
            val firstImage = requireNotNull(ImageIO.read(files.first())) { "Clipboard image could not be read." }
            ClipboardSnapshot(directory, files, firstImage).also { completed = true }
        } finally {
            if (!completed) runCatching { deleteRecursively(directory) }
        }
    }

    private fun scheduleDelete(directory: Path) {
        ioDispatcher.dispatch(EmptyCoroutineContext, Runnable { runCatching { deleteRecursively(directory) } })
    }
}

internal class ImageFilesTransferable(
    private val files: List<File>,
    private val firstImage: Image,
) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(
        DataFlavor.javaFileListFlavor,
        DataFlavor.imageFlavor,
    )

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
        flavor == DataFlavor.javaFileListFlavor || flavor == DataFlavor.imageFlavor

    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return when (flavor) {
            DataFlavor.javaFileListFlavor -> files
            DataFlavor.imageFlavor -> firstImage
            else -> throw UnsupportedFlavorException(flavor)
        }
    }
}

private class ClipboardSnapshotOwner(
    private val directory: Path,
    private val ioDispatcher: CoroutineDispatcher,
) : ClipboardOwner {
    override fun lostOwnership(clipboard: Clipboard?, contents: Transferable?) {
        ioDispatcher.dispatch(EmptyCoroutineContext, Runnable { runCatching { deleteRecursively(directory) } })
    }
}

private data class ClipboardSnapshot(
    val directory: Path,
    val files: List<File>,
    val firstImage: Image,
)

private fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}
