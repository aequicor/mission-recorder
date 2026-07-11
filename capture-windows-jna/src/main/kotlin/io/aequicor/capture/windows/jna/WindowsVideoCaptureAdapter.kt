package io.aequicor.capture.windows.jna

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
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class WindowsVideoCaptureAdapter internal constructor(
    private val windowSystem: WindowsWindowSystem,
    private val nanoTime: () -> Long = System::nanoTime,
) : VideoCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<VideoFrame> = flow {
        val selection = settings.captureSource.toWindowsSelection()
        val interval = 1.seconds / max(settings.frameRate, 1)
        val startedAt = nanoTime()
        var selectedWindow: WindowsWindowDescriptor? = null
        var outputWidth = 0
        var outputHeight = 0

        while (currentCoroutineContext().isActive) {
            selectedWindow = resolveWindow(selection, selectedWindow)
            val captured = capture(selectedWindow, settings.captureSource.displayName)
            captured.validate()
            var rgbaPixels = captured.bgraPixels.toOpaqueRgba()
            if (settings.captureCursor) {
                windowSystem.cursorPosition()
                    ?.takeIf(captured.bounds::contains)
                    ?.let { cursor ->
                        RgbaCursorPainter.draw(
                            rgbaPixels = rgbaPixels,
                            frameWidth = captured.bounds.width,
                            frameHeight = captured.bounds.height,
                            hotspotX = cursor.x - captured.bounds.x,
                            hotspotY = cursor.y - captured.bounds.y,
                        )
                    }
            }

            if (outputWidth == 0 || outputHeight == 0) {
                outputWidth = captured.bounds.width
                outputHeight = captured.bounds.height
            } else if (captured.bounds.width != outputWidth || captured.bounds.height != outputHeight) {
                rgbaPixels = rgbaPixels.fitInto(
                    sourceWidth = captured.bounds.width,
                    sourceHeight = captured.bounds.height,
                    targetWidth = outputWidth,
                    targetHeight = outputHeight,
                )
            }

            emit(
                VideoFrame(
                    timestamp = MediaTimestamp((nanoTime() - startedAt).coerceAtLeast(0)),
                    width = outputWidth,
                    height = outputHeight,
                    pixelFormat = PixelFormat.Rgba8888,
                    strideBytes = outputWidth * RGBA_CHANNEL_COUNT,
                    sourceId = settings.captureSource.id,
                    pixelData = rgbaPixels,
                ),
            )
            delay(interval.inWholeNanoseconds.nanoseconds)
        }
    }

    private fun resolveWindow(
        selection: WindowsSelection,
        previous: WindowsWindowDescriptor?,
    ): WindowsWindowDescriptor = when (selection) {
        is WindowsSelection.Window -> windowSystem.findWindow(selection.handle)
        is WindowsSelection.Application -> previous
            ?.let { window -> windowSystem.findWindow(window.handle) }
            ?.takeIf { window -> window.processId == selection.processId }
            ?: windowSystem.listWindows()
                .filter { window -> window.processId == selection.processId }
                .takeIf(List<WindowsWindowDescriptor>::isNotEmpty)
                ?.primaryWindow()
    } ?: throw sourceUnavailable("The selected window or application is no longer available.")

    private fun capture(window: WindowsWindowDescriptor, displayName: String): WindowsCapturedFrame =
        try {
            windowSystem.captureWindow(window.handle)
        } catch (failure: WindowsCaptureFailure) {
            throw sourceUnavailable("Unable to capture $displayName: ${failure.message}")
        } catch (failure: RuntimeException) {
            throw sourceUnavailable("Unable to capture $displayName: ${failure.message ?: "native capture failed"}.")
        }
}

private sealed interface WindowsSelection {
    data class Window(val handle: Long) : WindowsSelection
    data class Application(val processId: Long) : WindowsSelection
}

private fun CaptureSource.toWindowsSelection(): WindowsSelection = when (this) {
    is CaptureSource.Window -> WindowsCaptureSourceIds.parseWindow(id.value)?.let(WindowsSelection::Window)
    is CaptureSource.Application -> WindowsCaptureSourceIds.parseApplication(id.value)?.let(WindowsSelection::Application)
    else -> null
} ?: throw sourceUnavailable("Windows capture requires a Windows window or application source id.")

private fun ByteArray.toOpaqueRgba(): ByteArray {
    val result = ByteArray(size)
    var offset = 0
    while (offset + 3 < size) {
        result[offset] = this[offset + 2]
        result[offset + 1] = this[offset + 1]
        result[offset + 2] = this[offset]
        result[offset + 3] = 0xff.toByte()
        offset += RGBA_CHANNEL_COUNT
    }
    return result
}

private fun WindowsCapturedFrame.validate() {
    val expectedBytes = bounds.width.toLong() * bounds.height * RGBA_CHANNEL_COUNT
    if (bounds.width <= 0 || bounds.height <= 0 || expectedBytes > Int.MAX_VALUE || bgraPixels.size != expectedBytes.toInt()) {
        throw sourceUnavailable("Windows capture returned an invalid ${bounds.width}x${bounds.height} BGRA frame.")
    }
}

private fun ByteArray.fitInto(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): ByteArray {
    val result = ByteArray(targetWidth * targetHeight * RGBA_CHANNEL_COUNT)
    for (offset in 3 until result.size step RGBA_CHANNEL_COUNT) {
        result[offset] = 0xff.toByte()
    }
    val scale = min(targetWidth.toDouble() / sourceWidth, targetHeight.toDouble() / sourceHeight)
    val fittedWidth = max(1, (sourceWidth * scale).toInt()).coerceAtMost(targetWidth)
    val fittedHeight = max(1, (sourceHeight * scale).toInt()).coerceAtMost(targetHeight)
    val targetX = (targetWidth - fittedWidth) / 2
    val targetY = (targetHeight - fittedHeight) / 2
    for (y in 0 until fittedHeight) {
        val sourceY = (y.toLong() * sourceHeight / fittedHeight).toInt().coerceAtMost(sourceHeight - 1)
        for (x in 0 until fittedWidth) {
            val sourceX = (x.toLong() * sourceWidth / fittedWidth).toInt().coerceAtMost(sourceWidth - 1)
            val sourceOffset = (sourceY * sourceWidth + sourceX) * RGBA_CHANNEL_COUNT
            val targetOffset = ((targetY + y) * targetWidth + targetX + x) * RGBA_CHANNEL_COUNT
            copyInto(result, targetOffset, sourceOffset, sourceOffset + RGBA_CHANNEL_COUNT)
        }
    }
    return result
}

private fun sourceUnavailable(message: String): RecordingException =
    RecordingException(RecordingError.SourceUnavailable(message))

private const val RGBA_CHANNEL_COUNT = 4
