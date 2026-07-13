package io.aequicor.capture.linux.x11

internal data class X11Point(val x: Int, val y: Int)

internal data class X11WindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val area: Long = width.toLong() * height

    fun contains(point: X11Point): Boolean =
        point.x >= x && point.y >= y &&
            point.x.toLong() < x.toLong() + width && point.y.toLong() < y.toLong() + height
}

internal data class X11WindowDescriptor(
    val windowId: Long,
    val processId: Long?,
    val title: String,
    val processName: String?,
    val bounds: X11WindowBounds,
    val minimized: Boolean,
)

internal data class X11CapturedFrame(
    val bounds: X11WindowBounds,
    val rgbaPixels: ByteArray,
)

internal interface X11WindowSystem {
    fun listWindows(): List<X11WindowDescriptor>
    fun findWindow(windowId: Long): X11WindowDescriptor?
    fun captureWindow(windowId: Long): X11CapturedFrame
    fun cursorPosition(): X11Point?
    fun pressedInputs(): List<String>
}

internal object X11CaptureSourceIds {
    private const val WINDOW_PREFIX = "window:x11:"
    private const val APPLICATION_PREFIX = "application:x11:"

    fun window(windowId: Long): String = WINDOW_PREFIX + java.lang.Long.toUnsignedString(windowId, 16)
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

internal class X11CaptureFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
