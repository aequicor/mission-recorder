package io.aequicor.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Window

internal fun excludeWindowFromCapture(
    window: Window,
    osName: String = System.getProperty("os.name").orEmpty(),
): Boolean {
    val affinity = captureExclusionAffinity(osName) ?: return false
    return runCatching {
        val pointer = Native.getComponentPointer(window)
        CaptureExclusionUser32.INSTANCE.SetWindowDisplayAffinity(HWND(pointer), affinity)
    }.getOrDefault(false)
}

internal fun captureExclusionAffinity(osName: String): Int? =
    WDA_EXCLUDEFROMCAPTURE.takeIf { osName.startsWith("Windows", ignoreCase = true) }

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
