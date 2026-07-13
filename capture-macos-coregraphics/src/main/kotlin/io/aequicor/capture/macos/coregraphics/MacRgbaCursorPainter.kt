package io.aequicor.capture.macos.coregraphics

internal object MacRgbaCursorPainter {
    fun draw(pixels: ByteArray, width: Int, height: Int, hotspotX: Int, hotspotY: Int) {
        highlight(pixels, width, height, hotspotX, hotspotY)
        val polygon = listOf(0 to 0, 0 to 20, 5 to 15, 10 to 24, 14 to 22, 9 to 14, 17 to 14)
            .map { (x, y) -> hotspotX + x to hotspotY + y }
        for (y in hotspotY until hotspotY + 25) for (x in hotspotX until hotspotX + 18) {
            if (inside(x, y, polygon)) set(pixels, width, height, x, y, 255)
        }
        polygon.indices.forEach { index -> line(pixels, width, height, polygon[index], polygon[(index + 1) % polygon.size]) }
    }

    private fun highlight(pixels: ByteArray, width: Int, height: Int, centerX: Int, centerY: Int) {
        for (offsetY in -HIGHLIGHT_RADIUS..HIGHLIGHT_RADIUS) for (offsetX in -HIGHLIGHT_RADIUS..HIGHLIGHT_RADIUS) {
            val distanceSquared = offsetX * offsetX + offsetY * offsetY
            if (distanceSquared <= HIGHLIGHT_RADIUS * HIGHLIGHT_RADIUS) {
                val isBorder = distanceSquared >= HIGHLIGHT_INNER_RADIUS * HIGHLIGHT_INNER_RADIUS
                blend(
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

    private fun inside(x: Int, y: Int, points: List<Pair<Int, Int>>): Boolean {
        var result = false
        var previous = points.last()
        points.forEach { current ->
            if ((current.second > y) != (previous.second > y)) {
                val boundary = (previous.first - current.first).toDouble() * (y - current.second) /
                    (previous.second - current.second) + current.first
                if (x < boundary) result = !result
            }
            previous = current
        }
        return result
    }

    private fun line(pixels: ByteArray, width: Int, height: Int, start: Pair<Int, Int>, end: Pair<Int, Int>) {
        val steps = maxOf(kotlin.math.abs(end.first - start.first), kotlin.math.abs(end.second - start.second), 1)
        repeat(steps + 1) { step ->
            set(
                pixels,
                width,
                height,
                start.first + (end.first - start.first) * step / steps,
                start.second + (end.second - start.second) * step / steps,
                0,
            )
        }
    }

    private fun set(pixels: ByteArray, width: Int, height: Int, x: Int, y: Int, value: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        val offset = (y * width + x) * 4
        pixels[offset] = value.toByte()
        pixels[offset + 1] = value.toByte()
        pixels[offset + 2] = value.toByte()
        pixels[offset + 3] = 0xff.toByte()
    }

    private fun blend(
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
        pixels[offset] = blendChannel(red, pixels[offset], alpha)
        pixels[offset + 1] = blendChannel(green, pixels[offset + 1], alpha)
        pixels[offset + 2] = blendChannel(blue, pixels[offset + 2], alpha)
        pixels[offset + 3] = 0xff.toByte()
    }

    private fun blendChannel(source: Int, destination: Byte, alpha: Int): Byte {
        val destinationValue = destination.toInt() and 0xff
        return ((source * alpha + destinationValue * (255 - alpha)) / 255).toByte()
    }

    private const val HIGHLIGHT_RADIUS = 14
    private const val HIGHLIGHT_INNER_RADIUS = 12
}
