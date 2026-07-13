package io.aequicor.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Window

internal fun setWindowVisibleInCapture(
    window: Window,
    visible: Boolean,
    osName: String = System.getProperty("os.name").orEmpty(),
): Boolean {
    val affinity = captureAffinity(osName, visible) ?: return false
    return runCatching {
        val pointer = Native.getComponentPointer(window)
        CaptureExclusionUser32.INSTANCE.SetWindowDisplayAffinity(HWND(pointer), affinity)
    }.getOrDefault(false)
}

internal fun captureAffinity(osName: String, visible: Boolean): Int? =
    (if (visible) WDA_NONE else WDA_EXCLUDEFROMCAPTURE)
        .takeIf { osName.startsWith("Windows", ignoreCase = true) }

private interface CaptureExclusionUser32 : StdCallLibrary {
    fun SetWindowDisplayAffinity(window: HWND, affinity: Int): Boolean

    companion object {
        val INSTANCE: CaptureExclusionUser32 = Native.load(
            "user32",
            CaptureExclusionUser32::class.java,
            W32APIOptions.DEFAULT_OPTIONS,
        )
    }
}

private const val WDA_EXCLUDEFROMCAPTURE = 0x00000011
private const val WDA_NONE = 0x00000000
