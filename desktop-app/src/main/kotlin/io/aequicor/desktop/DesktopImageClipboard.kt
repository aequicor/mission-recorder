package io.aequicor.desktop

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface DesktopImageClipboard {
    suspend fun copyPng(path: String)
}

internal class AwtDesktopImageClipboard(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DesktopImageClipboard {
    override suspend fun copyPng(path: String) {
        val image = withContext(ioDispatcher) {
            requireNotNull(ImageIO.read(Path.of(path).toFile())) { "Storyboard image could not be read." }
        }
        suspendCancellableCoroutine { continuation ->
            EventQueue.invokeLater {
                if (!continuation.isActive) return@invokeLater
                runCatching {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageTransferable(image), null)
                }.onSuccess {
                    continuation.resume(Unit)
                }.onFailure(continuation::resumeWithException)
            }
        }
    }
}

private class ImageTransferable(private val image: Image) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor

    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return image
    }
}
