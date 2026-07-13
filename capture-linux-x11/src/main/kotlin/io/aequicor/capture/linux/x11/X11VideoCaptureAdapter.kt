package io.aequicor.capture.linux.x11

import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.MediaTimestamp
import io.aequicor.capture.core.PixelFormat
import io.aequicor.capture.core.RecordingError
import io.aequicor.capture.core.RecordingException
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.VideoCaptureAdapter
import io.aequicor.capture.core.VideoFrame
import io.aequicor.capture.platform.InputOverlayRenderer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.nanoseconds

internal class X11VideoCaptureAdapter(
    private val windowSystem: X11WindowSystem,
    private val dispatcher: CoroutineDispatcher,
    private val nanoTime: () -> Long = System::nanoTime,
) : VideoCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<VideoFrame> = flow {
        val selection = settings.captureSource.toX11Selection()
        val intervalNanoseconds = NANOS_PER_SECOND / max(settings.frameRate, 1)
        val startedAt = nanoTime()
        var nextFrameDeadline = startedAt
        var selectedWindow: X11WindowDescriptor? = null
        var outputWidth = 0
        var outputHeight = 0
        val inputOverlay = InputOverlayRenderer()

        while (currentCoroutineContext().isActive) {
            selectedWindow = resolveWindow(selection, selectedWindow)
            val captured = capture(selectedWindow, settings.captureSource.displayName)
            captured.validate()
            var pixels = captured.rgbaPixels
            val cursor = if (settings.captureCursor || settings.showInputOverlay) {
                windowSystem.cursorPosition()?.takeIf(captured.bounds::contains)
            } else {
                null
            }
            val hotspotX = cursor?.x?.minus(captured.bounds.x)
            val hotspotY = cursor?.y?.minus(captured.bounds.y)
            if (settings.captureCursor && hotspotX != null && hotspotY != null) {
                X11RgbaCursorPainter.draw(
                    rgbaPixels = pixels,
                    frameWidth = captured.bounds.width,
                    frameHeight = captured.bounds.height,
                    hotspotX = hotspotX,
                    hotspotY = hotspotY,
                )
            }
            if (settings.showInputOverlay) {
                val label = inputOverlay.update(windowSystem.pressedInputs(), nanoTime())
                if (label != null && hotspotX != null && hotspotY != null) {
                    inputOverlay.drawRgba(
                        pixels = pixels,
                        frameWidth = captured.bounds.width,
                        frameHeight = captured.bounds.height,
                        hotspotX = hotspotX,
                        hotspotY = hotspotY,
                        text = label,
                    )
                }
            }
            if (outputWidth == 0 || outputHeight == 0) {
                outputWidth = captured.bounds.width
                outputHeight = captured.bounds.height
            } else if (captured.bounds.width != outputWidth || captured.bounds.height != outputHeight) {
                pixels = pixels.fitInto(captured.bounds.width, captured.bounds.height, outputWidth, outputHeight)
            }
            emit(
                VideoFrame(
                    timestamp = MediaTimestamp((nanoTime() - startedAt).coerceAtLeast(0)),
                    width = outputWidth,
                    height = outputHeight,
                    pixelFormat = PixelFormat.Rgba8888,
                    strideBytes = outputWidth * RGBA_CHANNELS,
                    sourceId = settings.captureSource.id,
                    pixelData = pixels,
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
    }.flowOn(dispatcher)

    private fun resolveWindow(
        selection: X11Selection,
        previous: X11WindowDescriptor?,
    ): X11WindowDescriptor = when (selection) {
        is X11Selection.Window -> windowSystem.findWindow(selection.windowId)
        is X11Selection.Application -> previous
            ?.let { windowSystem.findWindow(it.windowId) }
            ?.takeIf { it.processId == selection.processId && !it.minimized }
            ?: windowSystem.listWindows()
                .filter { it.processId == selection.processId }
                .takeIf(List<X11WindowDescriptor>::isNotEmpty)
                ?.primaryWindow()
    } ?: throw sourceUnavailable("The selected X11 window or application is no longer available.")

    private fun capture(window: X11WindowDescriptor, displayName: String): X11CapturedFrame = try {
        windowSystem.captureWindow(window.windowId)
    } catch (failure: X11CaptureFailure) {
        throw sourceUnavailable("Unable to capture $displayName: ${failure.message}")
    } catch (failure: RuntimeException) {
        throw sourceUnavailable("Unable to capture $displayName: ${failure.message ?: "X11 capture failed"}.")
    }
}

private sealed interface X11Selection {
    data class Window(val windowId: Long) : X11Selection
    data class Application(val processId: Long) : X11Selection
}

private fun CaptureSource.toX11Selection(): X11Selection = when (this) {
    is CaptureSource.Window -> X11CaptureSourceIds.parseWindow(id.value)?.let(X11Selection::Window)
    is CaptureSource.Application -> X11CaptureSourceIds.parseApplication(id.value)?.let(X11Selection::Application)
    else -> null
} ?: throw sourceUnavailable("X11 capture requires an X11 window or application source id.")

private fun X11CapturedFrame.validate() {
    val expected = bounds.width.toLong() * bounds.height * RGBA_CHANNELS
    if (bounds.width <= 0 || bounds.height <= 0 || expected > Int.MAX_VALUE || rgbaPixels.size != expected.toInt()) {
        throw sourceUnavailable("X11 returned an invalid ${bounds.width}x${bounds.height} RGBA frame.")
    }
}

private fun ByteArray.fitInto(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): ByteArray {
    val result = ByteArray(targetWidth * targetHeight * RGBA_CHANNELS)
    for (offset in 3 until result.size step RGBA_CHANNELS) result[offset] = 0xff.toByte()
    val scale = min(targetWidth.toDouble() / sourceWidth, targetHeight.toDouble() / sourceHeight)
    val fittedWidth = max(1, (sourceWidth * scale).toInt()).coerceAtMost(targetWidth)
    val fittedHeight = max(1, (sourceHeight * scale).toInt()).coerceAtMost(targetHeight)
    val targetX = (targetWidth - fittedWidth) / 2
    val targetY = (targetHeight - fittedHeight) / 2
    for (y in 0 until fittedHeight) {
        val sourceY = (y.toLong() * sourceHeight / fittedHeight).toInt().coerceAtMost(sourceHeight - 1)
        for (x in 0 until fittedWidth) {
            val sourceX = (x.toLong() * sourceWidth / fittedWidth).toInt().coerceAtMost(sourceWidth - 1)
            val sourceOffset = (sourceY * sourceWidth + sourceX) * RGBA_CHANNELS
            val targetOffset = ((targetY + y) * targetWidth + targetX + x) * RGBA_CHANNELS
            copyInto(result, targetOffset, sourceOffset, sourceOffset + RGBA_CHANNELS)
        }
    }
    return result
}

private fun sourceUnavailable(message: String) = RecordingException(RecordingError.SourceUnavailable(message))
private const val RGBA_CHANNELS = 4
private const val NANOS_PER_SECOND = 1_000_000_000L
