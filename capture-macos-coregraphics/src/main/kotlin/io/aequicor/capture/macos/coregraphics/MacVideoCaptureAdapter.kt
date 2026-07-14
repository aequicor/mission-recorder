package io.aequicor.capture.macos.coregraphics

import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.MediaTimestamp
import io.aequicor.capture.core.PixelFormat
import io.aequicor.capture.core.RecordingError
import io.aequicor.capture.core.RecordingException
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.VideoCaptureAdapter
import io.aequicor.capture.core.VideoFrame
import io.aequicor.capture.core.VideoFramePoint
import io.aequicor.capture.platform.InputOverlayRenderer
import io.aequicor.capture.platform.MouseTrailOverlayRenderer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.nanoseconds

internal class MacVideoCaptureAdapter(
    private val windowSystem: MacWindowSystem,
    private val dispatcher: CoroutineDispatcher,
    private val nanoTime: () -> Long = System::nanoTime,
) : VideoCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<VideoFrame> = flow {
        val selection = settings.captureSource.toMacSelection()
        val intervalNanoseconds = NANOS_PER_SECOND / max(settings.frameRate, 1)
        val startedAt = nanoTime()
        var nextFrameDeadline = startedAt
        var selected: MacWindowDescriptor? = null
        var outputWidth = 0
        var outputHeight = 0
        val inputOverlay = InputOverlayRenderer()
        val mouseTrail = MouseTrailOverlayRenderer()
        while (currentCoroutineContext().isActive) {
            selected = resolveWindow(selection, selected)
            val captured = try {
                windowSystem.captureWindow(selected.windowId)
            } catch (failure: MacCaptureFailure) {
                throw sourceUnavailable("Unable to capture ${settings.captureSource.displayName}: ${failure.message}")
            }
            captured.validate()
            val frameTimestampNanoseconds = (nanoTime() - startedAt).coerceAtLeast(0)
            var pixels = captured.rgbaPixels
            val cursor = captured.cursorPixelPosition(windowSystem.cursorPosition())
            if (settings.showMouseTrail) {
                mouseTrail.update(
                    timestampMicros = frameTimestampNanoseconds / NANOS_PER_MICROSECOND,
                    hotspotX = cursor?.x,
                    hotspotY = cursor?.y,
                    frameWidth = captured.pixelWidth,
                    frameHeight = captured.pixelHeight,
                )
                mouseTrail.drawRgba(pixels, captured.pixelWidth, captured.pixelHeight)
            }
            if (settings.captureCursor && cursor != null) {
                MacRgbaCursorPainter.draw(pixels, captured.pixelWidth, captured.pixelHeight, cursor.x, cursor.y)
            }
            if (settings.showInputOverlay) {
                val label = inputOverlay.update(
                    pressedInputs = windowSystem.pressedInputs(),
                    timestampNanoseconds = nanoTime(),
                    hotspotX = cursor?.x,
                    hotspotY = cursor?.y,
                )
                if (label != null) {
                    inputOverlay.drawRgba(
                        pixels = pixels,
                        frameWidth = captured.pixelWidth,
                        frameHeight = captured.pixelHeight,
                        hotspotX = cursor?.x ?: 0,
                        hotspotY = cursor?.y ?: 0,
                        text = label,
                    )
                }
            }
            if (outputWidth == 0 || outputHeight == 0) {
                outputWidth = captured.pixelWidth
                outputHeight = captured.pixelHeight
            } else if (captured.pixelWidth != outputWidth || captured.pixelHeight != outputHeight) {
                pixels = pixels.fitInto(captured.pixelWidth, captured.pixelHeight, outputWidth, outputHeight)
            }
            emit(
                VideoFrame(
                    timestamp = MediaTimestamp(frameTimestampNanoseconds),
                    width = outputWidth,
                    height = outputHeight,
                    pixelFormat = PixelFormat.Rgba8888,
                    strideBytes = outputWidth * 4,
                    sourceId = settings.captureSource.id,
                    scaleFactor = captured.pixelWidth.toDouble() / captured.logicalBounds.width,
                    pixelData = pixels,
                    cursorPosition = cursor?.let { position ->
                        VideoFramePoint(
                            x = position.x * outputWidth / captured.pixelWidth,
                            y = position.y * outputHeight / captured.pixelHeight,
                        )
                    },
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

    private fun resolveWindow(selection: MacSelection, previous: MacWindowDescriptor?): MacWindowDescriptor =
        when (selection) {
            is MacSelection.Window -> windowSystem.findWindow(selection.windowId)
            is MacSelection.Application -> previous?.let { windowSystem.findWindow(it.windowId) }
                ?.takeIf { it.processId == selection.processId && !it.minimized }
                ?: windowSystem.listWindows().filter { it.processId == selection.processId }
                    .takeIf(List<MacWindowDescriptor>::isNotEmpty)?.primaryWindow()
        } ?: throw sourceUnavailable("The selected macOS window or application is no longer available.")
}

private sealed interface MacSelection {
    data class Window(val windowId: Long) : MacSelection
    data class Application(val processId: Long) : MacSelection
}

private fun CaptureSource.toMacSelection(): MacSelection = when (this) {
    is CaptureSource.Window -> MacCaptureSourceIds.parseWindow(id.value)?.let(MacSelection::Window)
    is CaptureSource.Application -> MacCaptureSourceIds.parseApplication(id.value)?.let(MacSelection::Application)
    else -> null
} ?: throw sourceUnavailable("macOS native capture requires a macOS window or application source id.")

private fun MacCapturedFrame.cursorPixelPosition(cursor: MacPoint?): MacPoint? {
    if (cursor == null || !logicalBounds.contains(cursor)) return null
    return MacPoint(
        ((cursor.x - logicalBounds.x).toDouble() * pixelWidth / logicalBounds.width).roundToInt(),
        ((cursor.y - logicalBounds.y).toDouble() * pixelHeight / logicalBounds.height).roundToInt(),
    )
}

private fun MacCapturedFrame.validate() {
    val expected = pixelWidth.toLong() * pixelHeight * 4
    if (
        logicalBounds.width <= 0 || logicalBounds.height <= 0 || pixelWidth <= 0 || pixelHeight <= 0 ||
        expected > Int.MAX_VALUE || rgbaPixels.size != expected.toInt()
    ) throw sourceUnavailable("CoreGraphics returned an invalid ${pixelWidth}x$pixelHeight RGBA frame.")
}

private fun ByteArray.fitInto(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): ByteArray {
    val result = ByteArray(targetWidth * targetHeight * 4)
    for (offset in 3 until result.size step 4) result[offset] = 0xff.toByte()
    val scale = min(targetWidth.toDouble() / sourceWidth, targetHeight.toDouble() / sourceHeight)
    val fittedWidth = max(1, (sourceWidth * scale).toInt()).coerceAtMost(targetWidth)
    val fittedHeight = max(1, (sourceHeight * scale).toInt()).coerceAtMost(targetHeight)
    val targetX = (targetWidth - fittedWidth) / 2
    val targetY = (targetHeight - fittedHeight) / 2
    for (y in 0 until fittedHeight) for (x in 0 until fittedWidth) {
        val sx = (x.toLong() * sourceWidth / fittedWidth).toInt().coerceAtMost(sourceWidth - 1)
        val sy = (y.toLong() * sourceHeight / fittedHeight).toInt().coerceAtMost(sourceHeight - 1)
        val sourceOffset = (sy * sourceWidth + sx) * 4
        val targetOffset = ((targetY + y) * targetWidth + targetX + x) * 4
        copyInto(result, targetOffset, sourceOffset, sourceOffset + 4)
    }
    return result
}

private fun sourceUnavailable(message: String) = RecordingException(RecordingError.SourceUnavailable(message))

private const val NANOS_PER_SECOND = 1_000_000_000L
private const val NANOS_PER_MICROSECOND = 1_000L
