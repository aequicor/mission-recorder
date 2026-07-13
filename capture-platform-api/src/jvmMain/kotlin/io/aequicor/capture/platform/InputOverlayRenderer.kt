package io.aequicor.capture.platform

import io.aequicor.capture.core.PixelFormat
import io.aequicor.capture.core.paintInputEventFrameMarker
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.max

/**
 * Tracks newly pressed desktop inputs and paints a short-lived label next to the cursor.
 *
 * A renderer instance owns one capture session's transient state and is not thread-safe.
 */
public class InputOverlayRenderer(
    private val visibleDurationNanoseconds: Long = DEFAULT_VISIBLE_DURATION_NANOSECONDS,
) {
    private var previousInputs: Set<String> = emptySet()
    private var visibleText: String? = null
    private var visibleUntilNanoseconds: Long = Long.MIN_VALUE
    private var cachedLabel: RenderedLabel? = null
    private var eventMarkerPending: Boolean = false

    init {
        require(visibleDurationNanoseconds > 0) { "Input overlay duration must be positive." }
    }

    /** Updates pressed inputs and returns the label that should be visible at [timestampNanoseconds]. */
    public fun update(pressedInputs: List<String>, timestampNanoseconds: Long): String? {
        val currentInputs = pressedInputs.filter(String::isNotBlank).toCollection(linkedSetOf())
        if (currentInputs.any { input -> input !in previousInputs }) {
            visibleText = currentInputs.toDisplayText()
            visibleUntilNanoseconds = timestampNanoseconds + visibleDurationNanoseconds
            eventMarkerPending = true
        }
        previousInputs = currentInputs
        return visibleText?.takeIf { timestampNanoseconds <= visibleUntilNanoseconds }
    }

    /** Paints [text] over an RGBA8888 frame, positioning it near the cursor hotspot. */
    public fun drawRgba(
        pixels: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        hotspotX: Int,
        hotspotY: Int,
        text: String,
    ): Unit = draw(pixels, frameWidth, frameHeight, hotspotX, hotspotY, text, blueFirst = false)

    /** Paints [text] over a BGRA8888 frame, positioning it near the cursor hotspot. */
    public fun drawBgra(
        pixels: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        hotspotX: Int,
        hotspotY: Int,
        text: String,
    ): Unit = draw(pixels, frameWidth, frameHeight, hotspotX, hotspotY, text, blueFirst = true)

    /** Paints and consumes a pending input-event marker over an RGBA8888 frame. */
    public fun drawPendingEventMarkerRgba(pixels: ByteArray, frameWidth: Int, frameHeight: Int) {
        drawPendingEventMarker(pixels, frameWidth, frameHeight, blueFirst = false)
    }

    /** Paints and consumes a pending input-event marker over a BGRA8888 frame. */
    public fun drawPendingEventMarkerBgra(pixels: ByteArray, frameWidth: Int, frameHeight: Int) {
        drawPendingEventMarker(pixels, frameWidth, frameHeight, blueFirst = true)
    }

    private fun draw(
        pixels: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        hotspotX: Int,
        hotspotY: Int,
        text: String,
        blueFirst: Boolean,
    ) {
        if (frameWidth <= 0 || frameHeight <= 0 || text.isBlank()) return
        val expectedBytes = frameWidth.toLong() * frameHeight * RGBA_CHANNEL_COUNT
        require(expectedBytes <= Int.MAX_VALUE && pixels.size >= expectedBytes.toInt()) {
            "Input overlay requires a complete ${frameWidth}x$frameHeight frame."
        }
        val label = cachedLabel?.takeIf { rendered -> rendered.text == text } ?: render(text).also {
            cachedLabel = it
        }
        val x = labelX(hotspotX, label.width, frameWidth)
        val y = labelY(hotspotY, label.height, frameHeight)
        blendLabel(pixels, frameWidth, frameHeight, x, y, label, blueFirst)
    }

    private fun drawPendingEventMarker(
        destination: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        blueFirst: Boolean,
    ) {
        val painted = eventMarkerPending && paintInputEventFrameMarker(
            pixels = destination,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            strideBytes = frameWidth * RGBA_CHANNEL_COUNT,
            pixelFormat = if (blueFirst) PixelFormat.Bgra8888 else PixelFormat.Rgba8888,
        )
        if (painted) {
            eventMarkerPending = false
        }
    }

    private fun render(text: String): RenderedLabel {
        val metricsImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val metrics = metricsImage.createGraphics().use { graphics ->
            graphics.font = LABEL_FONT
            graphics.fontMetrics
        }
        val width = metrics.stringWidth(text) + HORIZONTAL_PADDING * 2
        val height = metrics.height + VERTICAL_PADDING * 2
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().use { graphics ->
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.color = Color(25, 31, 40, 218)
            graphics.fillRoundRect(1, 1, width - 2, height - 2, CORNER_RADIUS, CORNER_RADIUS)
            graphics.color = Color(255, 255, 255, 220)
            graphics.stroke = BasicStroke(1.5f)
            graphics.drawRoundRect(1, 1, width - 3, height - 3, CORNER_RADIUS, CORNER_RADIUS)
            graphics.font = LABEL_FONT
            graphics.color = Color.WHITE
            graphics.drawString(text, HORIZONTAL_PADDING, VERTICAL_PADDING + metrics.ascent)
        }
        return RenderedLabel(text, width, height, image.getRGB(0, 0, width, height, null, 0, width))
    }

    private fun labelX(hotspotX: Int, labelWidth: Int, frameWidth: Int): Int {
        val right = hotspotX + CURSOR_GAP
        val preferred = if (right + labelWidth <= frameWidth) right else hotspotX - CURSOR_GAP - labelWidth
        return preferred.coerceIn(0, max(frameWidth - labelWidth, 0))
    }

    private fun labelY(hotspotY: Int, labelHeight: Int, frameHeight: Int): Int {
        val below = hotspotY + CURSOR_GAP
        val preferred = if (below + labelHeight <= frameHeight) below else hotspotY - CURSOR_GAP - labelHeight
        return preferred.coerceIn(0, max(frameHeight - labelHeight, 0))
    }

    private fun blendLabel(
        destination: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        targetX: Int,
        targetY: Int,
        label: RenderedLabel,
        blueFirst: Boolean,
    ) {
        for (labelY in 0 until label.height) {
            val frameY = targetY + labelY
            if (frameY !in 0 until frameHeight) continue
            for (labelX in 0 until label.width) {
                val frameX = targetX + labelX
                if (frameX !in 0 until frameWidth) continue
                val source = label.argb[labelY * label.width + labelX]
                val alpha = source ushr 24 and 0xff
                if (alpha == 0) continue
                val offset = (frameY * frameWidth + frameX) * RGBA_CHANNEL_COUNT
                val redIndex = if (blueFirst) offset + 2 else offset
                val blueIndex = if (blueFirst) offset else offset + 2
                destination[redIndex] = blend(source ushr 16 and 0xff, destination[redIndex], alpha)
                destination[offset + 1] = blend(source ushr 8 and 0xff, destination[offset + 1], alpha)
                destination[blueIndex] = blend(source and 0xff, destination[blueIndex], alpha)
                destination[offset + 3] = 0xff.toByte()
            }
        }
    }

    private fun Set<String>.toDisplayText(): String {
        val displayed = take(MAX_DISPLAYED_INPUTS)
        return buildString {
            append(displayed.joinToString(" + "))
            if (size > displayed.size) append(" + …")
        }
    }

    private fun blend(source: Int, destination: Byte, alpha: Int): Byte {
        val destinationValue = destination.toInt() and 0xff
        return ((source * alpha + destinationValue * (0xff - alpha)) / 0xff).toByte()
    }

    private data class RenderedLabel(
        val text: String,
        val width: Int,
        val height: Int,
        val argb: IntArray,
    )

    public companion object {
        /** Default time a released input remains visible in the recorded frame. */
        public const val DEFAULT_VISIBLE_DURATION_NANOSECONDS: Long = 900_000_000L

        private const val RGBA_CHANNEL_COUNT = 4
        private const val MAX_DISPLAYED_INPUTS = 4
        private const val CURSOR_GAP = 22
        private const val HORIZONTAL_PADDING = 9
        private const val VERTICAL_PADDING = 5
        private const val CORNER_RADIUS = 12
        private val LABEL_FONT = Font("SansSerif", Font.BOLD, 16)
    }
}

private inline fun <T : java.awt.Graphics, R> T.use(block: (T) -> R): R = try {
    block(this)
} finally {
    dispose()
}
