package io.aequicor.capture.windows.jna

import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.MediaTimestamp
import io.aequicor.capture.core.PixelFormat
import io.aequicor.capture.core.RecordingError
import io.aequicor.capture.core.RecordingException
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.VideoCaptureAdapter
import io.aequicor.capture.core.VideoFrame
import io.aequicor.capture.core.VideoFrameLease
import io.aequicor.capture.core.VideoFramePoint
import io.aequicor.capture.platform.InputOverlayRenderer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.awt.GraphicsEnvironment
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.nanoseconds

class WindowsVideoCaptureAdapter internal constructor(
    private val windowSystem: WindowsWindowSystem,
    private val dispatcher: CoroutineDispatcher,
    private val nanoTime: () -> Long = System::nanoTime,
) : VideoCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<VideoFrame> = captureFrames(settings, nativeFrames = false)

    override fun nativeFrames(settings: RecordingSettings): Flow<VideoFrame> = captureFrames(settings, nativeFrames = true)

    private fun captureFrames(settings: RecordingSettings, nativeFrames: Boolean): Flow<VideoFrame> = flow {
        val selection = settings.captureSource.toWindowsSelection()
        val intervalNanoseconds = NANOS_PER_SECOND / max(settings.frameRate, 1)
        var selectedWindow: WindowsWindowDescriptor? = null
        var outputWidth = 0
        var outputHeight = 0
        val inputOverlay = InputOverlayRenderer()
        val useNativeFrames = nativeFrames &&
            settings.encoder.hardwareAcceleration != io.aequicor.capture.core.HardwareAccelerationMode.Disabled &&
            !settings.showInputOverlay
        val screenCapture = (selection as? WindowsSelection.Desktop)
            ?.let { desktop ->
                windowSystem.openScreenCapture(
                    bounds = desktop.bounds,
                    frameRate = settings.frameRate,
                    nativeFrames = useNativeFrames,
                    captureCursor = settings.captureCursor,
                )
            }
        val startedAt = nanoTime()
        var nextFrameDeadline = startedAt

        try {
            while (currentCoroutineContext().isActive) {
                val captured = if (screenCapture != null) {
                    screenCapture.capture()
                } else {
                    selectedWindow = resolveWindow(selection, selectedWindow)
                    capture(requireNotNull(selectedWindow), settings.captureSource.displayName)
                }
                val lease = VideoFrameLease(captured::release)
                try {
                    captured.validate()
                    val cursor = windowSystem.cursorPosition()?.takeIf(captured.bounds::contains)
                    val hotspotX = cursor?.x?.minus(captured.bounds.x)
                    val hotspotY = cursor?.y?.minus(captured.bounds.y)
                    if (captured.nativeFrame != null) {
                        emit(
                            VideoFrame(
                                timestamp = MediaTimestamp((nanoTime() - startedAt).coerceAtLeast(0)),
                                width = captured.bounds.width,
                                height = captured.bounds.height,
                                pixelFormat = PixelFormat.Nv12,
                                strideBytes = 0,
                                sourceId = settings.captureSource.id,
                                nativeFrame = captured.nativeFrame,
                                cursorPosition = hotspotX?.let { x ->
                                    hotspotY?.let { y -> VideoFramePoint(x, y) }
                                },
                                lease = lease,
                            ),
                        )
                        nextFrameDeadline = paceFrame(nextFrameDeadline, intervalNanoseconds)
                        continue
                    }
                    var bgraPixels = requireNotNull(captured.bgraPixels)
                    if (settings.captureCursor && hotspotX != null && hotspotY != null) {
                        RgbaCursorPainter.drawBgra(
                            bgraPixels = bgraPixels,
                            frameWidth = captured.bounds.width,
                            frameHeight = captured.bounds.height,
                            hotspotX = hotspotX,
                            hotspotY = hotspotY,
                        )
                    }
                    if (settings.showInputOverlay) {
                        val label = inputOverlay.update(
                            pressedInputs = windowSystem.pressedInputs(),
                            timestampNanoseconds = nanoTime(),
                            hotspotX = hotspotX,
                            hotspotY = hotspotY,
                        )
                        if (label != null) {
                            inputOverlay.drawBgra(
                                pixels = bgraPixels,
                                frameWidth = captured.bounds.width,
                                frameHeight = captured.bounds.height,
                                hotspotX = hotspotX ?: 0,
                                hotspotY = hotspotY ?: 0,
                                text = label,
                            )
                        }
                    }
                    if (outputWidth == 0 || outputHeight == 0) {
                        outputWidth = captured.bounds.width
                        outputHeight = captured.bounds.height
                    } else if (captured.bounds.width != outputWidth || captured.bounds.height != outputHeight) {
                        bgraPixels = bgraPixels.fitInto(
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
                            pixelFormat = PixelFormat.Bgra8888,
                            strideBytes = outputWidth * RGBA_CHANNEL_COUNT,
                            sourceId = settings.captureSource.id,
                            pixelData = bgraPixels,
                            cursorPosition = hotspotX?.let { x ->
                                hotspotY?.let { y ->
                                    VideoFramePoint(
                                        x = x * outputWidth / captured.bounds.width,
                                        y = y * outputHeight / captured.bounds.height,
                                    )
                                }
                            },
                            lease = lease,
                        ),
                    )
                } catch (throwable: Throwable) {
                    lease.release()
                    throw throwable
                }
                nextFrameDeadline = paceFrame(nextFrameDeadline, intervalNanoseconds)
            }
        } finally {
            screenCapture?.close()
        }
    }.flowOn(dispatcher)

    private suspend fun paceFrame(previousDeadline: Long, intervalNanoseconds: Long): Long {
        var nextDeadline = previousDeadline + intervalNanoseconds
        val remainingNanoseconds = nextDeadline - nanoTime()
        if (remainingNanoseconds > 0) {
            delay(remainingNanoseconds.nanoseconds)
        } else if (remainingNanoseconds < -intervalNanoseconds) {
            val missedIntervals = (-remainingNanoseconds) / intervalNanoseconds
            nextDeadline += missedIntervals * intervalNanoseconds
        }
        return nextDeadline
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
        is WindowsSelection.Desktop -> null
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
    data class Desktop(val bounds: WindowsWindowBounds) : WindowsSelection
}

private fun CaptureSource.toWindowsSelection(): WindowsSelection = when (this) {
    is CaptureSource.Window -> WindowsCaptureSourceIds.parseWindow(id.value)?.let(WindowsSelection::Window)
    is CaptureSource.Application -> WindowsCaptureSourceIds.parseApplication(id.value)?.let(WindowsSelection::Application)
    is CaptureSource.Screen -> allScreensBounds()?.let(WindowsSelection::Desktop)
    is CaptureSource.Monitor -> monitorBounds(index)?.let(WindowsSelection::Desktop)
    is CaptureSource.Region -> WindowsSelection.Desktop(
        WindowsWindowBounds(region.x, region.y, region.width, region.height),
    )
} ?: throw sourceUnavailable("The selected Windows capture area is unavailable.")

private fun allScreensBounds(): WindowsWindowBounds? = GraphicsEnvironment
    .getLocalGraphicsEnvironment()
    .screenDevices
    .map { device -> device.defaultConfiguration.bounds }
    .reduceOrNull { combined, bounds -> combined.union(bounds) }
    ?.let { bounds -> WindowsWindowBounds(bounds.x, bounds.y, bounds.width, bounds.height) }

private fun monitorBounds(index: Int): WindowsWindowBounds? = GraphicsEnvironment
    .getLocalGraphicsEnvironment()
    .screenDevices
    .getOrNull(index)
    ?.defaultConfiguration
    ?.bounds
    ?.let { bounds -> WindowsWindowBounds(bounds.x, bounds.y, bounds.width, bounds.height) }

private fun WindowsCapturedFrame.validate() {
    val expectedBytes = bounds.width.toLong() * bounds.height * RGBA_CHANNEL_COUNT
    val invalidPayload = if (nativeFrame != null) {
        bgraPixels != null
    } else {
        expectedBytes > Int.MAX_VALUE || bgraPixels?.size != expectedBytes.toInt()
    }
    if (bounds.width <= 0 || bounds.height <= 0 || invalidPayload) {
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
private const val NANOS_PER_SECOND = 1_000_000_000L
