package io.aequicor.capture.core

/**
 * Pixel marker embedded into a frame selected for unconditional storyboard retention.
 *
 * Capture adapters add it for newly pressed desktop inputs, while recording controllers
 * add the same marker for explicit important-frame actions. The marker is small and short-lived.
 */
public object InputEventFrameMarker {
    public const val MARGIN_PIXELS: Int = 4
    public const val CELL_SIZE_PIXELS: Int = 4
    public const val ROW_COUNT: Int = 5
    public const val COLUMN_COUNT: Int = 7

    public const val ACCENT_RED: Int = 238
    public const val ACCENT_GREEN: Int = 54
    public const val ACCENT_BLUE: Int = 88

    public const val BACKGROUND_RED: Int = 12
    public const val BACKGROUND_GREEN: Int = 16
    public const val BACKGROUND_BLUE: Int = 22

    /** Returns whether the marker cell at [row] and [column] uses the accent color. */
    public fun isAccentCell(row: Int, column: Int): Boolean {
        require(row in 0 until ROW_COUNT && column in 0 until COLUMN_COUNT) {
            "Input event marker cell is outside its bounds."
        }
        return PATTERN[row][column] == '1'
    }

    private val PATTERN: List<String> = listOf(
        "1110111",
        "1010101",
        "1110111",
        "0011100",
        "1010101",
    )
}

/** Paints the storyboard input-event marker over a complete RGBA8888 or BGRA8888 frame. */
public fun paintInputEventFrameMarker(
    pixels: ByteArray,
    frameWidth: Int,
    frameHeight: Int,
    strideBytes: Int,
    pixelFormat: PixelFormat,
): Boolean {
    require(pixelFormat == PixelFormat.Rgba8888 || pixelFormat == PixelFormat.Bgra8888) {
        "Input event marker requires an RGBA8888 or BGRA8888 frame."
    }
    val requiredBytes = (frameHeight - 1).toLong() * strideBytes + frameWidth * MARKER_CHANNEL_COUNT
    require(frameWidth > 0 && frameHeight > 0 && strideBytes >= frameWidth * MARKER_CHANNEL_COUNT)
    require(requiredBytes <= Int.MAX_VALUE && pixels.size >= requiredBytes.toInt()) {
        "Input event marker requires a complete ${frameWidth}x$frameHeight frame."
    }
    val markerWidth = InputEventFrameMarker.COLUMN_COUNT * InputEventFrameMarker.CELL_SIZE_PIXELS
    val markerHeight = InputEventFrameMarker.ROW_COUNT * InputEventFrameMarker.CELL_SIZE_PIXELS
    val originX = InputEventFrameMarker.MARGIN_PIXELS
    val originY = InputEventFrameMarker.MARGIN_PIXELS
    if (originX + markerWidth > frameWidth || originY + markerHeight > frameHeight) {
        return false
    }
    repeat(InputEventFrameMarker.ROW_COUNT) { row ->
        repeat(InputEventFrameMarker.COLUMN_COUNT) { column ->
            val accent = InputEventFrameMarker.isAccentCell(row, column)
            val red = if (accent) InputEventFrameMarker.ACCENT_RED else InputEventFrameMarker.BACKGROUND_RED
            val green = if (accent) InputEventFrameMarker.ACCENT_GREEN else InputEventFrameMarker.BACKGROUND_GREEN
            val blue = if (accent) InputEventFrameMarker.ACCENT_BLUE else InputEventFrameMarker.BACKGROUND_BLUE
            repeat(InputEventFrameMarker.CELL_SIZE_PIXELS) { cellY ->
                repeat(InputEventFrameMarker.CELL_SIZE_PIXELS) { cellX ->
                    val x = originX + column * InputEventFrameMarker.CELL_SIZE_PIXELS + cellX
                    val y = originY + row * InputEventFrameMarker.CELL_SIZE_PIXELS + cellY
                    val offset = y * strideBytes + x * MARKER_CHANNEL_COUNT
                    pixels[offset] = (if (pixelFormat == PixelFormat.Bgra8888) blue else red).toByte()
                    pixels[offset + 1] = green.toByte()
                    pixels[offset + 2] = (if (pixelFormat == PixelFormat.Bgra8888) red else blue).toByte()
                    pixels[offset + 3] = 0xff.toByte()
                }
            }
        }
    }
    return true
}

/** Returns an independent copy of this frame marked for unconditional storyboard retention. */
public fun VideoFrame.withInputEventFrameMarker(): VideoFrame {
    val markedPixels = requireNotNull(pixelData) { "Important video frame does not contain pixels." }.copyOf()
    require(
        paintInputEventFrameMarker(
            pixels = markedPixels,
            frameWidth = width,
            frameHeight = height,
            strideBytes = strideBytes,
            pixelFormat = pixelFormat,
        ),
    ) { "Video frame is too small for the important-frame marker." }
    return copy(pixelData = markedPixels)
}

private const val MARKER_CHANNEL_COUNT = 4
