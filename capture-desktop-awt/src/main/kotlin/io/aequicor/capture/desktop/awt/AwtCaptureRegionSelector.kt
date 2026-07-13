package io.aequicor.capture.desktop.awt

import io.aequicor.capture.core.CaptureRegion
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.CoordinateSpace
import io.aequicor.capture.platform.CaptureRegionSelection
import io.aequicor.capture.platform.CaptureRegionSelector
import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.EventQueue
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Area
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JWindow
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.coroutines.resume

class AwtCaptureRegionSelector : CaptureRegionSelector {
    override suspend fun selectRegion(): CaptureRegionSelection = suspendCancellableCoroutine { continuation ->
        if (GraphicsEnvironment.isHeadless()) {
            continuation.resume(
                CaptureRegionSelection.Unavailable("Region selection is unavailable in headless mode."),
            )
            return@suspendCancellableCoroutine
        }

        val overlayReference = AtomicReference<RegionSelectionWindow?>()
        val finished = AtomicBoolean(false)
        fun finish(result: CaptureRegionSelection) {
            if (!finished.compareAndSet(false, true)) {
                return
            }
            overlayReference.getAndSet(null)?.dispose()
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }

        continuation.invokeOnCancellation {
            EventQueue.invokeLater {
                if (finished.compareAndSet(false, true)) {
                    overlayReference.getAndSet(null)?.dispose()
                }
            }
        }
        EventQueue.invokeLater {
            if (!continuation.isActive || finished.get()) {
                return@invokeLater
            }
            runCatching {
                val desktop = AwtDesktopGeometry.current()
                RegionSelectionWindow(
                    virtualBounds = desktop.virtualBounds,
                    onSelected = { selection -> finish(CaptureRegionSelection.Selected(desktop.toCaptureRegion(selection))) },
                    onCancelled = { finish(CaptureRegionSelection.Cancelled) },
                ).also { overlay ->
                    overlayReference.set(overlay)
                    overlay.showSelector()
                }
            }.onFailure { failure ->
                finish(
                    CaptureRegionSelection.Unavailable(
                        failure.message ?: "Unable to open the region selector.",
                    ),
                )
            }
        }
    }
}

internal data class AwtMonitorGeometry(
    val index: Int,
    val bounds: Rectangle,
    val scaleFactor: Double,
)

internal data class AwtDesktopGeometry(
    val virtualBounds: Rectangle,
    val monitors: List<AwtMonitorGeometry>,
) {
    fun toCaptureRegion(selection: Rectangle): CaptureRegion {
        val normalized = selection.intersection(virtualBounds)
        require(normalized.width > 0 && normalized.height > 0) { "Selected region is empty." }
        val monitor = monitors.singleOrNull { candidate -> candidate.bounds.contains(normalized) }
        return CaptureRegion(
            x = normalized.x,
            y = normalized.y,
            width = normalized.width,
            height = normalized.height,
            monitorId = monitor?.let { CaptureSourceId("monitor:${it.index}") },
            scaleFactor = monitor?.scaleFactor ?: 1.0,
            coordinateSpace = CoordinateSpace.LogicalPixels,
        )
    }

    companion object {
        fun current(): AwtDesktopGeometry {
            val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            require(devices.isNotEmpty()) { "No screens are available for region selection." }
            val monitors = devices.mapIndexed { index, device ->
                val configuration = device.defaultConfiguration
                AwtMonitorGeometry(
                    index = index,
                    bounds = Rectangle(configuration.bounds),
                    scaleFactor = configuration.defaultTransform.scaleX,
                )
            }
            val virtualBounds = monitors
                .map(AwtMonitorGeometry::bounds)
                .reduce(Rectangle::union)
            return AwtDesktopGeometry(virtualBounds = virtualBounds, monitors = monitors)
        }
    }
}

internal fun normalizedSelection(start: Point, end: Point, bounds: Rectangle): Rectangle? {
    val left = minOf(start.x, end.x).coerceIn(bounds.x, bounds.x + bounds.width)
    val top = minOf(start.y, end.y).coerceIn(bounds.y, bounds.y + bounds.height)
    val right = maxOf(start.x, end.x).coerceIn(bounds.x, bounds.x + bounds.width)
    val bottom = maxOf(start.y, end.y).coerceIn(bounds.y, bounds.y + bounds.height)
    val selection = Rectangle(left, top, right - left, bottom - top)
    return selection.takeIf { it.width >= MIN_SELECTION_SIZE && it.height >= MIN_SELECTION_SIZE }
}

private class RegionSelectionWindow(
    private val virtualBounds: Rectangle,
    onSelected: (Rectangle) -> Unit,
    onCancelled: () -> Unit,
) : JWindow() {
    private val perPixelTranslucent = graphicsConfiguration.device.isWindowTranslucencySupported(PERPIXEL_TRANSLUCENT)
    private val surface = RegionSelectionSurface(
        virtualBounds = virtualBounds,
        perPixelTranslucent = perPixelTranslucent,
        onSelected = onSelected,
        onCancelled = onCancelled,
    )

    init {
        name = "Mission Recorder region selector"
        bounds = Rectangle(virtualBounds)
        isAlwaysOnTop = true
        focusableWindowState = true
        cursor = createRegionSelectionCursor()
        contentPane = surface
        if (perPixelTranslucent) {
            background = Color(0, 0, 0, 0)
        } else {
            background = Color.BLACK
            runCatching { opacity = FALLBACK_WINDOW_OPACITY }
        }
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            CANCEL_ACTION,
        )
        rootPane.actionMap.put(
            CANCEL_ACTION,
            object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent?) = onCancelled()
            },
        )
    }

    fun showSelector() {
        isVisible = true
        toFront()
        requestFocus()
    }
}

private fun createRegionSelectionCursor(): Cursor {
    val fallback = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
    return runCatching {
        val toolkit = Toolkit.getDefaultToolkit()
        val size = toolkit.getBestCursorSize(CURSOR_SIZE, CURSOR_SIZE)
        if (size.width <= 0 || size.height <= 0) {
            return@runCatching fallback
        }
        val image = createHighContrastCursorImage(width = size.width, height = size.height)
        toolkit.createCustomCursor(
            image,
            Point(size.width / 2, size.height / 2),
            "Mission Recorder region selection cursor",
        )
    }.getOrDefault(fallback)
}

internal fun createHighContrastCursorImage(width: Int, height: Int): BufferedImage {
    require(width > 0 && height > 0) { "Cursor dimensions must be positive." }
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val centerX = width / 2
        val centerY = height / 2
        graphics.color = Color.BLACK
        graphics.stroke = BasicStroke(CURSOR_OUTLINE_STROKE)
        graphics.drawLine(centerX, 0, centerX, height - 1)
        graphics.drawLine(0, centerY, width - 1, centerY)
        graphics.color = Color.WHITE
        graphics.stroke = BasicStroke(CURSOR_FOREGROUND_STROKE)
        graphics.drawLine(centerX, 0, centerX, height - 1)
        graphics.drawLine(0, centerY, width - 1, centerY)
    } finally {
        graphics.dispose()
    }
    return image
}

private class RegionSelectionSurface(
    private val virtualBounds: Rectangle,
    private val perPixelTranslucent: Boolean,
    private val onSelected: (Rectangle) -> Unit,
    private val onCancelled: () -> Unit,
) : JComponent() {
    private var anchor: Point? = null
    private var pointer: Point? = null

    init {
        isOpaque = !perPixelTranslucent
        val listener = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    onCancelled()
                    return
                }
                anchor = event.toScreenPoint()
                pointer = anchor
                repaint()
            }

            override fun mouseDragged(event: MouseEvent) {
                pointer = event.toScreenPoint()
                repaint()
            }

            override fun mouseReleased(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    return
                }
                val start = anchor ?: return
                val end = event.toScreenPoint()
                normalizedSelection(start, end, virtualBounds)?.let(onSelected)
                    ?: onCancelled()
            }
        }
        addMouseListener(listener)
        addMouseMotionListener(listener)
    }

    override fun paintComponent(graphics: Graphics) {
        val graphics2D = graphics.create() as Graphics2D
        try {
            graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val selection = currentSelection()?.toLocal()
            graphics2D.color = OVERLAY_COLOR
            if (perPixelTranslucent && selection != null) {
                val outside = Area(Rectangle(0, 0, width, height)).apply { subtract(Area(selection)) }
                graphics2D.fill(outside)
            } else {
                graphics2D.fillRect(0, 0, width, height)
            }
            if (selection != null) {
                paintSelection(graphics2D, selection)
            }
        } finally {
            graphics2D.dispose()
        }
    }

    private fun paintSelection(graphics: Graphics2D, selection: Rectangle) {
        graphics.color = SELECTION_COLOR
        graphics.stroke = BasicStroke(SELECTION_STROKE)
        graphics.drawRect(selection.x, selection.y, selection.width, selection.height)
        val label = "${selection.width} x ${selection.height}"
        val metrics = graphics.fontMetrics
        val labelBounds = labelBounds(selection, metrics)
        graphics.color = LABEL_BACKGROUND
        graphics.fillRect(labelBounds.x, labelBounds.y, labelBounds.width, labelBounds.height)
        graphics.color = Color.WHITE
        graphics.drawString(label, labelBounds.x + LABEL_PADDING, labelBounds.y + metrics.ascent + LABEL_PADDING)
    }

    private fun labelBounds(selection: Rectangle, metrics: FontMetrics): Rectangle {
        val labelWidth = metrics.stringWidth("${selection.width} x ${selection.height}") + LABEL_PADDING * 2
        val labelHeight = metrics.height + LABEL_PADDING * 2
        val x = selection.x.coerceIn(0, (width - labelWidth).coerceAtLeast(0))
        val preferredY = selection.y - labelHeight - LABEL_GAP
        val y = if (preferredY >= 0) preferredY else (selection.y + LABEL_GAP).coerceAtMost(height - labelHeight)
        return Rectangle(x, y.coerceAtLeast(0), labelWidth, labelHeight)
    }

    private fun currentSelection(): Rectangle? {
        val start = anchor ?: return null
        val end = pointer ?: return null
        return normalizedSelection(start, end, virtualBounds)
    }

    private fun Rectangle.toLocal(): Rectangle = Rectangle(
        x - virtualBounds.x,
        y - virtualBounds.y,
        width,
        height,
    )

    private fun MouseEvent.toScreenPoint(): Point = Point(x + virtualBounds.x, y + virtualBounds.y)
}

private const val MIN_SELECTION_SIZE = 4
private const val CANCEL_ACTION = "cancel-region-selection"
private const val CURSOR_SIZE = 32
private const val CURSOR_OUTLINE_STROKE = 5.0f
private const val CURSOR_FOREGROUND_STROKE = 2.0f
private const val FALLBACK_WINDOW_OPACITY = 0.35f
private const val SELECTION_STROKE = 2.0f
private const val LABEL_PADDING = 5
private const val LABEL_GAP = 6
private val OVERLAY_COLOR = Color(0, 0, 0, 128)
private val SELECTION_COLOR = Color(0x26, 0x61, 0x9c)
private val LABEL_BACKGROUND = Color(20, 24, 29, 224)
