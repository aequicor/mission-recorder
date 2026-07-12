package io.aequicor.capture.desktop.awt

import io.aequicor.capture.core.CaptureRegion
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.MediaTimestamp
import io.aequicor.capture.core.PixelFormat
import io.aequicor.capture.core.RecordingError
import io.aequicor.capture.core.RecordingException
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.VideoCaptureAdapter
import io.aequicor.capture.core.VideoFrame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.Transparency
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.awt.image.SinglePixelPackedSampleModel
import kotlin.math.max
import kotlin.time.Duration.Companion.nanoseconds

class AwtVideoCaptureAdapter internal constructor(
    private val screenGrabberFactory: () -> ScreenGrabber,
    private val pointerLocationProvider: PointerLocationProvider,
    private val isHeadless: () -> Boolean,
    private val nanoTime: () -> Long,
) : VideoCaptureAdapter {
    constructor() : this(
        screenGrabberFactory = { RobotScreenGrabber(Robot()) },
        pointerLocationProvider = AwtPointerLocationProvider,
        isHeadless = GraphicsEnvironment::isHeadless,
        nanoTime = System::nanoTime,
    )

    override fun frames(settings: RecordingSettings): Flow<VideoFrame> = flow {
        if (isHeadless()) {
            throw RecordingException(RecordingError.SourceUnavailable("Desktop capture is unavailable in headless mode."))
        }

        val rectangle = captureRectangle(settings.captureSource)
        val screenGrabber = screenGrabberFactory()
        val intervalNanoseconds = NANOS_PER_SECOND / max(settings.frameRate, 1)
        val startedAt = nanoTime()
        var nextFrameDeadline = startedAt

        while (currentCoroutineContext().isActive) {
            val image = screenGrabber.capture(rectangle)
            if (settings.captureCursor) {
                pointerLocationProvider.location()?.let { location ->
                    image.drawCursor(location, rectangle)
                }
            }
            emit(
                VideoFrame(
                    timestamp = MediaTimestamp((nanoTime() - startedAt).coerceAtLeast(0)),
                    width = image.width,
                    height = image.height,
                    pixelFormat = PixelFormat.Rgba8888,
                    strideBytes = image.width * 4,
                    sourceId = settings.captureSource.id,
                    pixelData = image.toRgbaBytes(),
                ),
            )
            nextFrameDeadline += intervalNanoseconds
            val remainingNanoseconds = nextFrameDeadline - nanoTime()
            if (remainingNanoseconds > 0) {
                delay(remainingNanoseconds.nanoseconds)
            } else if (remainingNanoseconds < -intervalNanoseconds) {
                val missedIntervals = (-remainingNanoseconds) / intervalNanoseconds
                nextFrameDeadline += missedIntervals * intervalNanoseconds
            }
        }
    }

    private fun captureRectangle(source: CaptureSource): Rectangle =
        when (source) {
            is CaptureSource.Screen -> allScreensRectangle()
            is CaptureSource.Monitor -> monitorRectangle(source.index)
            is CaptureSource.Region -> source.region.toRectangle()
            is CaptureSource.Application,
            is CaptureSource.Window,
            -> throw RecordingException(
                RecordingError.SourceUnavailable("AWT desktop capture supports screen, monitor, and region only."),
            )
        }

    private fun allScreensRectangle(): Rectangle {
        val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        if (devices.isEmpty()) {
            throw RecordingException(RecordingError.SourceUnavailable("No screens are available."))
        }
        return devices
            .map { it.defaultConfiguration.bounds }
            .reduce { acc, rectangle -> acc.union(rectangle) }
    }

    private fun monitorRectangle(index: Int): Rectangle {
        val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        val device = devices.getOrNull(index)
            ?: throw RecordingException(RecordingError.SourceUnavailable("Monitor is unavailable: $index"))
        return device.defaultConfiguration.bounds
    }

    private fun CaptureRegion.toRectangle(): Rectangle {
        if (width <= 0 || height <= 0) {
            throw RecordingException(RecordingError.SourceUnavailable("Capture region must have positive size."))
        }
        return Rectangle(x, y, width, height)
    }
}

internal fun interface ScreenGrabber {
    fun capture(rectangle: Rectangle): BufferedImage
}

internal fun interface PointerLocationProvider {
    fun location(): Point?
}

private class RobotScreenGrabber(
    private val robot: Robot,
) : ScreenGrabber {
    override fun capture(rectangle: Rectangle): BufferedImage = robot.createScreenCapture(rectangle)
}

private data object AwtPointerLocationProvider : PointerLocationProvider {
    override fun location(): Point? = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
}

private fun BufferedImage.drawCursor(screenLocation: Point, captureRectangle: Rectangle) {
    if (!captureRectangle.contains(screenLocation)) {
        return
    }
    val x = (screenLocation.x - captureRectangle.x).toDouble()
    val y = (screenLocation.y - captureRectangle.y).toDouble()
    val cursor = Path2D.Double().apply {
        moveTo(x, y)
        lineTo(x, y + 22.0)
        lineTo(x + 5.5, y + 16.5)
        lineTo(x + 10.0, y + 25.0)
        lineTo(x + 14.0, y + 23.0)
        lineTo(x + 9.5, y + 15.0)
        lineTo(x + 17.0, y + 15.0)
        closePath()
    }
    createGraphics().use { graphics ->
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.color = Color.WHITE
        graphics.fill(cursor)
        graphics.color = Color.BLACK
        graphics.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        graphics.draw(cursor)
    }
}

private inline fun <T : java.awt.Graphics> T.use(block: (T) -> Unit) {
    try {
        block(this)
    } finally {
        dispose()
    }
}

private fun BufferedImage.toRgbaBytes(): ByteArray {
    val bytes = ByteArray(width * height * 4)
    val packedPixels = packedArgbPixels()
    if (packedPixels != null) {
        copyArgbToRgba(packedPixels, bytes, colorModel.transparency == Transparency.OPAQUE)
        return bytes
    }
    copyArgbToRgba(getRGB(0, 0, width, height, null, 0, width), bytes, forceOpaque = false)
    return bytes
}

private fun BufferedImage.packedArgbPixels(): IntArray? {
    val buffer = raster.dataBuffer as? DataBufferInt ?: return null
    val sampleModel = raster.sampleModel as? SinglePixelPackedSampleModel ?: return null
    if (buffer.numBanks != 1 || buffer.offset != 0 || sampleModel.scanlineStride != width) {
        return null
    }
    if (raster.sampleModelTranslateX != 0 || raster.sampleModelTranslateY != 0) {
        return null
    }
    return buffer.data.takeIf { pixels -> pixels.size >= width * height }
}

private fun copyArgbToRgba(argbPixels: IntArray, bytes: ByteArray, forceOpaque: Boolean) {
    var offset = 0
    argbPixels.forEach { argb ->
        bytes[offset] = ((argb shr 16) and 0xff).toByte()
        bytes[offset + 1] = ((argb shr 8) and 0xff).toByte()
        bytes[offset + 2] = (argb and 0xff).toByte()
        bytes[offset + 3] = if (forceOpaque) 0xff.toByte() else ((argb shr 24) and 0xff).toByte()
        offset += 4
    }
}

private const val NANOS_PER_SECOND = 1_000_000_000L
