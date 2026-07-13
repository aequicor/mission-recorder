package io.aequicor.capture.windows.jna

import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avutil.av_frame_clone
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import java.awt.GraphicsEnvironment
import java.nio.ByteBuffer

internal class DdaWindowsScreenCapture private constructor(
    private val bounds: WindowsWindowBounds,
    private val grabber: FFmpegFrameGrabber,
    private val nativeFrames: Boolean,
) : WindowsScreenCapture {
    private val frameBuffers = WindowsFrameBufferPool(DDA_FRAME_BUFFER_POOL_SIZE)

    override fun capture(): WindowsCapturedFrame = try {
        val frame = grabber.grabImage() ?: throw WindowsCaptureFailure(
            "Desktop Duplication stopped producing frames.",
        )
        if (nativeFrames) {
            val source = frame.opaque as? AVFrame
                ?: throw WindowsCaptureFailure("Desktop Duplication returned no D3D11 frame.")
            val retained = av_frame_clone(source)
                ?: throw WindowsCaptureFailure("Desktop Duplication could not retain a D3D11 frame.")
            return WindowsCapturedFrame(bounds = bounds, nativeFrame = retained) {
                av_frame_free(retained)
            }
        }
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
        val pixels = frameBuffers.acquire(rowBytes * bounds.height)
        try {
            if (frame.imageStride == rowBytes) {
                source.position(0)
                source.get(pixels)
            } else {
                repeat(bounds.height) { y ->
                    source.position(y * frame.imageStride)
                    source.get(pixels, y * rowBytes, rowBytes)
                }
            }
            WindowsCapturedFrame(bounds, pixels) { frameBuffers.release(pixels) }
        } catch (throwable: Throwable) {
            frameBuffers.release(pixels)
            throw throwable
        }
    } catch (failure: FFmpegFrameGrabber.Exception) {
        throw WindowsCaptureFailure(
            "Desktop Duplication capture failed: ${failure.message ?: "FFmpeg error"}.",
        )
    }

    override fun close() {
        runCatching { grabber.stop() }
        runCatching { grabber.release() }
        frameBuffers.clear()
    }

    companion object {
        fun openOrNull(
            bounds: WindowsWindowBounds,
            frameRate: Int,
            nativeFrames: Boolean = false,
            captureCursor: Boolean = false,
        ): DdaWindowsScreenCapture? {
            val output = desktopOutputFor(bounds) ?: return null
            if (nativeFrames && bounds != output.bounds) return null
            val grabber = FFmpegFrameGrabber(
                ddaGrabFilter(output, bounds, frameRate, nativeFrames, captureCursor),
            ).apply {
                format = "lavfi"
                imageMode = FrameGrabber.ImageMode.RAW
                timeout = DDA_GRAB_TIMEOUT_MILLIS
            }
            return try {
                grabber.start(false)
                DdaWindowsScreenCapture(bounds, grabber, nativeFrames)
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
    nativeFrames: Boolean = false,
    captureCursor: Boolean = false,
): String = buildString {
    append("ddagrab=framerate=")
    append(frameRate.coerceAtLeast(1))
    append(":output_idx=")
    append(output.index)
    append(":draw_mouse=")
    append(if (nativeFrames && captureCursor) 1 else 0)
    append(":dup_frames=1")
    if (nativeFrames) {
        append(",scale_d3d11=format=nv12")
    } else {
        append(",hwdownload,format=bgra")
    }
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
private const val DDA_FRAME_BUFFER_POOL_SIZE = 6
