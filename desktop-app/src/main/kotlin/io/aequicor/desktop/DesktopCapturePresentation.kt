package io.aequicor.desktop

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.platform.win32.WinUser.WNDENUMPROC
import com.sun.jna.ptr.IntByReference
import io.aequicor.capture.core.CaptureSource
import java.awt.Color
import java.awt.EventQueue
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.JWindow

internal class DesktopCapturePresentation(
    private val windowController: CaptureWindowController = createCaptureWindowController(),
    private val screenBounds: () -> List<Rectangle> = ::availableScreenBounds,
) : AutoCloseable {
    private val indicator = CaptureAreaIndicator()

    fun bounds(source: CaptureSource): Rectangle? = when (source) {
        is CaptureSource.Screen -> screenBounds().takeIf(List<Rectangle>::isNotEmpty)?.reduce(Rectangle::union)
        is CaptureSource.Monitor -> screenBounds().getOrNull(source.index)?.let(::Rectangle)
        is CaptureSource.Region -> source.region.run { Rectangle(x, y, width, height) }
            .takeIf { bounds -> bounds.width > 0 && bounds.height > 0 }
        is CaptureSource.Window,
        is CaptureSource.Application,
        -> windowController.bounds(source)
    }

    fun activate(source: CaptureSource): Boolean = when (source) {
        is CaptureSource.Application,
        is CaptureSource.Window,
        -> windowController.activate(source)
        is CaptureSource.Monitor,
        is CaptureSource.Region,
        is CaptureSource.Screen,
        -> false
    }

    fun show(bounds: Rectangle?) {
        if (bounds == null) {
            indicator.hide()
        } else {
            indicator.show(bounds)
        }
    }

    fun hide() = indicator.hide()

    override fun close() = indicator.close()
}

internal interface CaptureWindowController {
    fun bounds(source: CaptureSource): Rectangle?
    fun activate(source: CaptureSource): Boolean
}

private data object UnavailableCaptureWindowController : CaptureWindowController {
    override fun bounds(source: CaptureSource): Rectangle? = null
    override fun activate(source: CaptureSource): Boolean = false
}

private class WindowsCaptureWindowController(
    private val user32: User32 = User32.INSTANCE,
) : CaptureWindowController {
    override fun bounds(source: CaptureSource): Rectangle? = resolveWindow(source)?.bounds

    override fun activate(source: CaptureSource): Boolean {
        val window = resolveWindow(source) ?: return false
        user32.ShowWindow(window.handle, WinUser.SW_RESTORE)
        return user32.SetForegroundWindow(window.handle)
    }

    private fun resolveWindow(source: CaptureSource): WindowsWindow? = when (source) {
        is CaptureSource.Window -> parseWindowHandle(source.id.value)
            ?.let(::HWND)
            ?.takeIf(user32::IsWindow)
            ?.let(::describeWindow)
        is CaptureSource.Application -> parseApplicationProcessId(source.id.value)
            ?.let(::windowsForProcess)
            ?.maxByOrNull { window -> window.bounds.width.toLong() * window.bounds.height }
        is CaptureSource.Monitor,
        is CaptureSource.Region,
        is CaptureSource.Screen,
        -> null
    }

    private fun windowsForProcess(processId: Long): List<WindowsWindow> = buildList {
        val callback = WNDENUMPROC { handle, _ ->
            if (
                user32.IsWindowVisible(handle) &&
                user32.GetWindowTextLength(handle) > 0 &&
                processId(handle) == processId
            ) {
                describeWindow(handle)?.let(::add)
            }
            true
        }
        user32.EnumWindows(callback, null)
    }

    private fun describeWindow(handle: HWND): WindowsWindow? {
        val rectangle = RECT()
        if (!user32.GetWindowRect(handle, rectangle)) return null
        val width = rectangle.right.toLong() - rectangle.left
        val height = rectangle.bottom.toLong() - rectangle.top
        if (width <= 1 || height <= 1 || width > Int.MAX_VALUE || height > Int.MAX_VALUE) return null
        return WindowsWindow(
            handle = handle,
            bounds = Rectangle(rectangle.left, rectangle.top, width.toInt(), height.toInt()),
        )
    }

    private fun processId(handle: HWND): Long {
        val reference = IntByReference()
        user32.GetWindowThreadProcessId(handle, reference)
        return reference.value.toLong() and UINT32_MASK
    }
}

private data class WindowsWindow(
    val handle: HWND,
    val bounds: Rectangle,
)

private class CaptureAreaIndicator {
    private var windows: List<JWindow>? = null

    fun show(captureBounds: Rectangle) = onEventDispatchThread {
        val borderWindows = windows ?: IndicatorSide.entries.map { side ->
            JWindow().apply {
                name = "Mission Recorder capture boundary"
                runCatching { background = TRANSPARENT_COLOR }
                    .onFailure { background = INDICATOR_CORE_COLOR }
                contentPane = CaptureIndicatorSurface(side)
                isAlwaysOnTop = true
                focusableWindowState = false
                type = java.awt.Window.Type.UTILITY
                setWindowVisibleInCapture(this, visible = false)
            }
        }.also { windows = it }
        indicatorBounds(captureBounds).zip(borderWindows).forEach { (bounds, window) ->
            window.bounds = bounds
            window.isVisible = true
            setWindowVisibleInCapture(window, visible = false)
            window.toFront()
        }
    }

    fun hide() = onEventDispatchThread {
        windows?.forEach { window -> window.isVisible = false }
    }

    fun close() = onEventDispatchThread {
        windows?.forEach(JWindow::dispose)
        windows = null
    }
}

internal enum class IndicatorSide {
    Top,
    Bottom,
    Left,
    Right,
}

internal class CaptureIndicatorSurface(
    private val side: IndicatorSide,
) : JComponent() {
    init {
        isOpaque = false
    }

    override fun paintComponent(graphics: Graphics) {
        val graphics2D = graphics.create() as Graphics2D
        try {
            graphics2D.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics2D.paint = glowPaint(side, width, height)
            graphics2D.fillRect(0, 0, width, height)
            paintCore(graphics2D)
            paintCorners(graphics2D)
        } finally {
            graphics2D.dispose()
        }
    }

    private fun paintCore(graphics: Graphics2D) {
        graphics.color = INDICATOR_CORE_COLOR
        when (side) {
            IndicatorSide.Top -> graphics.fillRect(0, 0, width, INDICATOR_CORE_THICKNESS)
            IndicatorSide.Bottom -> graphics.fillRect(
                0,
                (height - INDICATOR_CORE_THICKNESS).coerceAtLeast(0),
                width,
                INDICATOR_CORE_THICKNESS,
            )
            IndicatorSide.Left -> graphics.fillRect(0, 0, INDICATOR_CORE_THICKNESS, height)
            IndicatorSide.Right -> graphics.fillRect(
                (width - INDICATOR_CORE_THICKNESS).coerceAtLeast(0),
                0,
                INDICATOR_CORE_THICKNESS,
                height,
            )
        }
    }

    private fun paintCorners(graphics: Graphics2D) {
        graphics.color = INDICATOR_CORNER_COLOR
        when (side) {
            IndicatorSide.Top -> paintHorizontalCorners(graphics, y = 0)
            IndicatorSide.Bottom -> paintHorizontalCorners(
                graphics,
                y = (height - INDICATOR_CORNER_THICKNESS).coerceAtLeast(0),
            )
            IndicatorSide.Left -> paintVerticalCorners(graphics, x = 0)
            IndicatorSide.Right -> paintVerticalCorners(
                graphics,
                x = (width - INDICATOR_CORNER_THICKNESS).coerceAtLeast(0),
            )
        }
    }

    private fun paintHorizontalCorners(graphics: Graphics2D, y: Int) {
        val length = INDICATOR_CORNER_LENGTH.coerceAtMost(width)
        graphics.fillRect(0, y, length, INDICATOR_CORNER_THICKNESS)
        graphics.fillRect((width - length).coerceAtLeast(0), y, length, INDICATOR_CORNER_THICKNESS)
    }

    private fun paintVerticalCorners(graphics: Graphics2D, x: Int) {
        val length = INDICATOR_CORNER_LENGTH.coerceAtMost(height)
        graphics.fillRect(x, 0, INDICATOR_CORNER_THICKNESS, length)
        graphics.fillRect(x, (height - length).coerceAtLeast(0), INDICATOR_CORNER_THICKNESS, length)
    }
}

private fun glowPaint(side: IndicatorSide, width: Int, height: Int): GradientPaint = when (side) {
    IndicatorSide.Top -> GradientPaint(0f, 0f, INDICATOR_GLOW_COLOR, 0f, height.toFloat(), TRANSPARENT_COLOR)
    IndicatorSide.Bottom -> GradientPaint(0f, 0f, TRANSPARENT_COLOR, 0f, height.toFloat(), INDICATOR_GLOW_COLOR)
    IndicatorSide.Left -> GradientPaint(0f, 0f, INDICATOR_GLOW_COLOR, width.toFloat(), 0f, TRANSPARENT_COLOR)
    IndicatorSide.Right -> GradientPaint(0f, 0f, TRANSPARENT_COLOR, width.toFloat(), 0f, INDICATOR_GLOW_COLOR)
}

internal fun indicatorBounds(captureBounds: Rectangle, thickness: Int = INDICATOR_THICKNESS): List<Rectangle> {
    require(thickness > 0) { "Indicator thickness must be positive." }
    if (captureBounds.width <= 0 || captureBounds.height <= 0) return emptyList()
    val horizontalThickness = thickness.coerceAtMost(captureBounds.height)
    val verticalThickness = thickness.coerceAtMost(captureBounds.width)
    return listOf(
        Rectangle(captureBounds.x, captureBounds.y, captureBounds.width, horizontalThickness),
        Rectangle(
            captureBounds.x,
            captureBounds.y + captureBounds.height - horizontalThickness,
            captureBounds.width,
            horizontalThickness,
        ),
        Rectangle(captureBounds.x, captureBounds.y, verticalThickness, captureBounds.height),
        Rectangle(
            captureBounds.x + captureBounds.width - verticalThickness,
            captureBounds.y,
            verticalThickness,
            captureBounds.height,
        ),
    )
}

private fun createCaptureWindowController(
    osName: String = System.getProperty("os.name").orEmpty(),
): CaptureWindowController = if (osName.startsWith("Windows", ignoreCase = true)) {
    WindowsCaptureWindowController()
} else {
    UnavailableCaptureWindowController
}

private fun parseWindowHandle(value: String): Pointer? = value
    .takeIf { id -> id.startsWith(WINDOW_ID_PREFIX) }
    ?.removePrefix(WINDOW_ID_PREFIX)
    ?.takeIf(String::isNotBlank)
    ?.let { encoded -> runCatching { java.lang.Long.parseUnsignedLong(encoded, 16) }.getOrNull() }
    ?.let(Pointer::createConstant)

private fun parseApplicationProcessId(value: String): Long? = value
    .takeIf { id -> id.startsWith(APPLICATION_ID_PREFIX) }
    ?.removePrefix(APPLICATION_ID_PREFIX)
    ?.toLongOrNull()
    ?.takeIf { processId -> processId > 0 }

private fun onEventDispatchThread(action: () -> Unit) {
    if (EventQueue.isDispatchThread()) {
        action()
    } else {
        EventQueue.invokeLater(action)
    }
}

private const val WINDOW_ID_PREFIX = "window:win32:"
private const val APPLICATION_ID_PREFIX = "application:win32:"
private const val INDICATOR_THICKNESS = 12
private const val INDICATOR_CORE_THICKNESS = 4
private const val INDICATOR_CORNER_THICKNESS = 7
private const val INDICATOR_CORNER_LENGTH = 52
private const val UINT32_MASK = 0xffff_ffffL
private val INDICATOR_CORE_COLOR = Color(0x38, 0x8b, 0xff)
private val INDICATOR_CORNER_COLOR = Color(0x8f, 0xc2, 0xff)
private val INDICATOR_GLOW_COLOR = Color(0x38, 0x8b, 0xff, 176)
private val TRANSPARENT_COLOR = Color(0x38, 0x8b, 0xff, 0)
