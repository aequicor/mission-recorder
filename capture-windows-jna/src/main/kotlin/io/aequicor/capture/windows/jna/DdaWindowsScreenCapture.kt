package io.aequicor.capture.windows.jna

import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import java.awt.GraphicsEnvironment
import java.nio.ByteBuffer

internal class DdaWindowsScreenCapture private constructor(
    private val bounds: WindowsWindowBounds,
    private val grabber: FFmpegFrameGrabber,
) : WindowsScreenCapture {
    override fun capture(): WindowsCapturedFrame = try {
        val frame = grabber.grabImage() ?: throw WindowsCaptureFailure(
            "Desktop Duplication stopped producing frames.",
        )
        if (frame.imageDepth != Frame.DEPTH_UBYTE || frame.imageChannels != BGRA_CHANNEL_COUNT ||
            frame.imageWidth != bounds.width || frame.imageHeight != bounds.height
        ) {
            throw WindowsCaptureFailure(
                "Desktop Duplication returned an invalid ${frame.imageWidth}x${frame.imageHeight} frame.",
            )
        }
        val source = (frame.image[0] as? ByteBuffer)?.duplicate()
            ?: throw WindowsCaptureFailure("Desktop Duplication returned no BGRA pixel buffer.")
        val rowBytes = bounds.width * BGRA_CHANNEL_COUNT
        val pixels = ByteArray(rowBytes * bounds.height)
        if (frame.imageStride == rowBytes) {
            source.position(0)
            source.get(pixels)
        } else {
            repeat(bounds.height) { y ->
                source.position(y * frame.imageStride)
                source.get(pixels, y * rowBytes, rowBytes)
            }
        }
        WindowsCapturedFrame(bounds, pixels)
    } catch (failure: FFmpegFrameGrabber.Exception) {
        throw WindowsCaptureFailure(
            "Desktop Duplication capture failed: ${failure.message ?: "FFmpeg error"}.",
        )
    }

    override fun close() {
        runCatching { grabber.stop() }
        runCatching { grabber.release() }
    }

    companion object {
        fun openOrNull(bounds: WindowsWindowBounds, frameRate: Int): DdaWindowsScreenCapture? {
            val output = desktopOutputFor(bounds) ?: return null
            val grabber = FFmpegFrameGrabber(ddaGrabFilter(output, bounds, frameRate)).apply {
                format = "lavfi"
                imageMode = FrameGrabber.ImageMode.RAW
                timeout = DDA_GRAB_TIMEOUT_MILLIS
            }
            return try {
                grabber.start(false)
                DdaWindowsScreenCapture(bounds, grabber)
            } catch (_: FFmpegFrameGrabber.Exception) {
                runCatching { grabber.release() }
                null
            }
        }
    }
}

internal data class WindowsDesktopOutput(
    val index: Int,
    val bounds: WindowsWindowBounds,
)

internal fun desktopOutputFor(bounds: WindowsWindowBounds): WindowsDesktopOutput? =
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .mapIndexed { index, device ->
            val screen = device.defaultConfiguration.bounds
            WindowsDesktopOutput(
                index = index,
                bounds = WindowsWindowBounds(screen.x, screen.y, screen.width, screen.height),
            )
        }
        .firstOrNull { output -> output.bounds.contains(bounds) }

internal fun ddaGrabFilter(
    output: WindowsDesktopOutput,
    captureBounds: WindowsWindowBounds,
    frameRate: Int,
): String = buildString {
    append("ddagrab=framerate=")
    append(frameRate.coerceAtLeast(1))
    append(":output_idx=")
    append(output.index)
    append(":draw_mouse=0:dup_frames=1,hwdownload,format=bgra")
    if (captureBounds != output.bounds) {
        append(",crop=")
        append(captureBounds.width)
        append(':')
        append(captureBounds.height)
        append(':')
        append(captureBounds.x - output.bounds.x)
        append(':')
        append(captureBounds.y - output.bounds.y)
    }
}

private fun WindowsWindowBounds.contains(other: WindowsWindowBounds): Boolean =
    other.x >= x && other.y >= y &&
        other.x.toLong() + other.width <= x.toLong() + width &&
        other.y.toLong() + other.height <= y.toLong() + height

private const val BGRA_CHANNEL_COUNT = 4
private const val DDA_GRAB_TIMEOUT_MILLIS = 1_000
