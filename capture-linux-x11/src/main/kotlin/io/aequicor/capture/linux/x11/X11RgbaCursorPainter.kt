package io.aequicor.capture.linux.x11

internal object X11RgbaCursorPainter {
    fun draw(
        rgbaPixels: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        hotspotX: Int,
        hotspotY: Int,
    ) {
        drawHighlight(rgbaPixels, frameWidth, frameHeight, hotspotX, hotspotY)
        val points = listOf(0 to 0, 0 to 20, 5 to 15, 10 to 24, 14 to 22, 9 to 14, 17 to 14)
        val polygon = points.map { (x, y) -> hotspotX + x to hotspotY + y }
        for (y in hotspotY until hotspotY + 25) {
            for (x in hotspotX until hotspotX + 18) {
                if (pointInPolygon(x, y, polygon)) {
                    setPixel(rgbaPixels, frameWidth, frameHeight, x, y, 255, 255, 255)
                }
            }
        }
        polygon.indices.forEach { index ->
            val start = polygon[index]
            val end = polygon[(index + 1) % polygon.size]
            drawLine(rgbaPixels, frameWidth, frameHeight, start, end)
        }
    }

    private fun drawHighlight(pixels: ByteArray, width: Int, height: Int, centerX: Int, centerY: Int) {
        for (offsetY in -HIGHLIGHT_RADIUS..HIGHLIGHT_RADIUS) {
            for (offsetX in -HIGHLIGHT_RADIUS..HIGHLIGHT_RADIUS) {
                val distanceSquared = offsetX * offsetX + offsetY * offsetY
                if (distanceSquared <= HIGHLIGHT_RADIUS * HIGHLIGHT_RADIUS) {
                    val isBorder = distanceSquared >= HIGHLIGHT_INNER_RADIUS * HIGHLIGHT_INNER_RADIUS
                    blendPixel(
                        pixels = pixels,
                        width = width,
                        height = height,
                        x = centerX + offsetX,
                        y = centerY + offsetY,
                        red = if (isBorder) 255 else 38,
                        green = if (isBorder) 255 else 97,
                        blue = if (isBorder) 255 else 156,
                        alpha = if (isBorder) 210 else 96,
                    )
                }
            }
        }
    }

    private fun pointInPolygon(x: Int, y: Int, polygon: List<Pair<Int, Int>>): Boolean {
        var inside = false
        var previous = polygon.last()
        polygon.forEach { current ->
            if ((current.second > y) != (previous.second > y)) {
                val boundary = (previous.first - current.first).toDouble() * (y - current.second) /
                    (previous.second - current.second) + current.first
                if (x < boundary) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun drawLine(
        pixels: ByteArray,
        width: Int,
        height: Int,
        start: Pair<Int, Int>,
        end: Pair<Int, Int>,
    ) {
        val steps = maxOf(kotlin.math.abs(end.first - start.first), kotlin.math.abs(end.second - start.second), 1)
        repeat(steps + 1) { step ->
            val x = start.first + (end.first - start.first) * step / steps
            val y = start.second + (end.second - start.second) * step / steps
            setPixel(pixels, width, height, x, y, 0, 0, 0)
        }
    }

    private fun setPixel(
        pixels: ByteArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        red: Int,
        green: Int,
        blue: Int,
    ) {
        if (x !in 0 until width || y !in 0 until height) return
        val offset = (y * width + x) * 4
        pixels[offset] = red.toByte()
        pixels[offset + 1] = green.toByte()
        pixels[offset + 2] = blue.toByte()
        pixels[offset + 3] = 0xff.toByte()
    }

    private fun blendPixel(
        pixels: ByteArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int,
    ) {
        if (x !in 0 until width || y !in 0 until height) return
        val offset = (y * width + x) * 4
        pixels[offset] = blend(red, pixels[offset], alpha)
        pixels[offset + 1] = blend(green, pixels[offset + 1], alpha)
        pixels[offset + 2] = blend(blue, pixels[offset + 2], alpha)
        pixels[offset + 3] = 0xff.toByte()
    }

    private fun blend(source: Int, destination: Byte, alpha: Int): Byte {
        val destinationValue = destination.toInt() and 0xff
        return ((source * alpha + destinationValue * (255 - alpha)) / 255).toByte()
    }

    private const val HIGHLIGHT_RADIUS = 14
    private const val HIGHLIGHT_INNER_RADIUS = 12
}
