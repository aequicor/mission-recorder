package io.aequicor.capture.windows.jna

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage

internal object RgbaCursorPainter {
    private const val WIDTH = 19
    private const val HEIGHT = 27
    private const val CHANNELS = 4
    private val cursorPixels = createCursorPixels()

    fun draw(
        rgbaPixels: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        hotspotX: Int,
        hotspotY: Int,
    ) {
        for (cursorY in 0 until HEIGHT) {
            val frameY = hotspotY + cursorY
            if (frameY !in 0 until frameHeight) {
                continue
            }
            for (cursorX in 0 until WIDTH) {
                val frameX = hotspotX + cursorX
                if (frameX !in 0 until frameWidth) {
                    continue
                }
                val source = cursorPixels[cursorY * WIDTH + cursorX]
                val alpha = source ushr 24 and 0xff
                if (alpha == 0) {
                    continue
                }
                val destination = (frameY * frameWidth + frameX) * CHANNELS
                rgbaPixels[destination] = blend(source ushr 16 and 0xff, rgbaPixels[destination], alpha)
                rgbaPixels[destination + 1] = blend(source ushr 8 and 0xff, rgbaPixels[destination + 1], alpha)
                rgbaPixels[destination + 2] = blend(source and 0xff, rgbaPixels[destination + 2], alpha)
                rgbaPixels[destination + 3] = 0xff.toByte()
            }
        }
    }

    fun drawBgra(
        bgraPixels: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        hotspotX: Int,
        hotspotY: Int,
    ) {
        for (cursorY in 0 until HEIGHT) {
            val frameY = hotspotY + cursorY
            if (frameY !in 0 until frameHeight) continue
            for (cursorX in 0 until WIDTH) {
                val frameX = hotspotX + cursorX
                if (frameX !in 0 until frameWidth) continue
                val source = cursorPixels[cursorY * WIDTH + cursorX]
                val alpha = source ushr 24 and 0xff
                if (alpha == 0) continue
                val destination = (frameY * frameWidth + frameX) * CHANNELS
                bgraPixels[destination] = blend(source and 0xff, bgraPixels[destination], alpha)
                bgraPixels[destination + 1] = blend(source ushr 8 and 0xff, bgraPixels[destination + 1], alpha)
                bgraPixels[destination + 2] = blend(source ushr 16 and 0xff, bgraPixels[destination + 2], alpha)
                bgraPixels[destination + 3] = 0xff.toByte()
            }
        }
    }

    private fun blend(source: Int, destination: Byte, alpha: Int): Byte {
        val destinationValue = destination.toInt() and 0xff
        return ((source * alpha + destinationValue * (0xff - alpha)) / 0xff).toByte()
    }

    private fun createCursorPixels(): IntArray {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val cursor = Path2D.Double().apply {
            moveTo(1.0, 1.0)
            lineTo(1.0, 23.0)
            lineTo(6.5, 17.5)
            lineTo(11.0, 26.0)
            lineTo(15.0, 24.0)
            lineTo(10.5, 16.0)
            lineTo(18.0, 16.0)
            closePath()
        }
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.color = Color.WHITE
            graphics.fill(cursor)
            graphics.color = Color.BLACK
            graphics.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            graphics.draw(cursor)
        } finally {
            graphics.dispose()
        }
        return image.getRGB(0, 0, WIDTH, HEIGHT, null, 0, WIDTH)
    }
}
