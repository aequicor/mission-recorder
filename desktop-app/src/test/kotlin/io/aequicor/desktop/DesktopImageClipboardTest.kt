package io.aequicor.desktop

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopImageClipboardTest {
    @Test
    fun exposesImagesAsAFileListAndKeepsTheFirstImageFlavor() {
        val files = listOf(File("frame-000001.png"), File("frame-000002.png"))
        val firstImage = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val transferable = ImageFilesTransferable(files, firstImage)

        assertTrue(transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
        assertTrue(transferable.isDataFlavorSupported(DataFlavor.imageFlavor))
        assertEquals(files, transferable.getTransferData(DataFlavor.javaFileListFlavor))
        assertSame(firstImage, transferable.getTransferData(DataFlavor.imageFlavor))
        assertFailsWith<UnsupportedFlavorException> {
            transferable.getTransferData(DataFlavor.stringFlavor)
        }
    }
}
