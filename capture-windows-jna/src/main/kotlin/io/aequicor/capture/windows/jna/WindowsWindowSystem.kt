package io.aequicor.capture.windows.jna

internal data class WindowsPoint(
    val x: Int,
    val y: Int,
)

internal data class WindowsWindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val area: Long = width.toLong() * height

    fun contains(point: WindowsPoint): Boolean =
        point.x >= x && point.y >= y && point.x.toLong() < x.toLong() + width &&
            point.y.toLong() < y.toLong() + height
}

internal data class WindowsWindowDescriptor(
    val handle: Long,
    val processId: Long,
    val title: String,
    val processName: String?,
    val bounds: WindowsWindowBounds,
    val minimized: Boolean,
)

internal data class WindowsCapturedFrame(
    val bounds: WindowsWindowBounds,
    val bgraPixels: ByteArray,
)

internal interface WindowsWindowSystem {
    fun listWindows(): List<WindowsWindowDescriptor>

    fun findWindow(handle: Long): WindowsWindowDescriptor?

    fun captureWindow(handle: Long): WindowsCapturedFrame

    fun openScreenCapture(bounds: WindowsWindowBounds, frameRate: Int): WindowsScreenCapture

    fun cursorPosition(): WindowsPoint?

    fun pressedInputs(): List<String>
}

internal interface WindowsScreenCapture : AutoCloseable {
    fun capture(): WindowsCapturedFrame
}

internal object WindowsCaptureSourceIds {
    private const val WINDOW_PREFIX = "window:win32:"
    private const val APPLICATION_PREFIX = "application:win32:"

    fun window(handle: Long): String = WINDOW_PREFIX + java.lang.Long.toUnsignedString(handle, 16)

    fun application(processId: Long): String = "$APPLICATION_PREFIX$processId"

    fun parseWindow(value: String): Long? = value
        .takeIf { it.startsWith(WINDOW_PREFIX) }
        ?.removePrefix(WINDOW_PREFIX)
        ?.takeIf(String::isNotBlank)
        ?.let { encoded -> runCatching { java.lang.Long.parseUnsignedLong(encoded, 16) }.getOrNull() }

    fun parseApplication(value: String): Long? = value
        .takeIf { it.startsWith(APPLICATION_PREFIX) }
        ?.removePrefix(APPLICATION_PREFIX)
        ?.toLongOrNull()
        ?.takeIf { it > 0 }
}

internal class WindowsCaptureFailure(message: String) : RuntimeException(message)
