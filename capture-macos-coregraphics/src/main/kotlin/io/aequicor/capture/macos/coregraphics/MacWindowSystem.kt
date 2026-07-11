package io.aequicor.capture.macos.coregraphics

internal data class MacPoint(val x: Int, val y: Int)

internal data class MacWindowBounds(val x: Int, val y: Int, val width: Int, val height: Int) {
    val area: Long = width.toLong() * height
    fun contains(point: MacPoint): Boolean =
        point.x >= x && point.y >= y &&
            point.x.toLong() < x.toLong() + width && point.y.toLong() < y.toLong() + height
}

internal data class MacWindowDescriptor(
    val windowId: Long,
    val processId: Long,
    val title: String,
    val processName: String,
    val bounds: MacWindowBounds,
    val minimized: Boolean,
)

internal data class MacCapturedFrame(
    val logicalBounds: MacWindowBounds,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val rgbaPixels: ByteArray,
)

internal interface MacWindowSystem {
    fun listWindows(): List<MacWindowDescriptor>
    fun findWindow(windowId: Long): MacWindowDescriptor?
    fun captureWindow(windowId: Long): MacCapturedFrame
    fun cursorPosition(): MacPoint?
}

internal object MacCaptureSourceIds {
    private const val WINDOW_PREFIX = "window:macos:"
    private const val APPLICATION_PREFIX = "application:macos:"

    fun window(windowId: Long): String = "$WINDOW_PREFIX$windowId"
    fun application(processId: Long): String = "$APPLICATION_PREFIX$processId"
    fun parseWindow(value: String): Long? = value.removePrefix(WINDOW_PREFIX)
        .takeIf { value.startsWith(WINDOW_PREFIX) }
        ?.toLongOrNull()?.takeIf { it > 0 }
    fun parseApplication(value: String): Long? = value.removePrefix(APPLICATION_PREFIX)
        .takeIf { value.startsWith(APPLICATION_PREFIX) }
        ?.toLongOrNull()?.takeIf { it > 0 }
}

internal class MacCaptureFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
