package io.aequicor.capture.linux.x11

import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.platform.CaptureSourceRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class X11CaptureAdaptersTest {
    @Test
    fun repositoryListsWindowsAndGroupsApplicationsByPid() = runTest {
        val windows = listOf(
            descriptor(0x10, 42, "Document", "Editor", 640, 480),
            descriptor(0x11, 42, "Find", "Editor", 300, 200),
            descriptor(0x20, 84, "Terminal", "Terminal", 800, 600),
            descriptor(0x30, null, "Unknown process", null, 400, 300),
        )
        val repository = X11CaptureSourceRepository(
            FakeX11WindowSystem(windows),
            UnconfinedTestDispatcher(testScheduler),
        )

        val sources = repository.listSources(CaptureSourceRequest())

        val windowSources = sources.filterIsInstance<CaptureSource.Window>()
        val applications = sources.filterIsInstance<CaptureSource.Application>()
        assertEquals(4, windowSources.size)
        assertEquals("window:x11:10", windowSources.first().id.value)
        assertEquals("Document (Editor)", windowSources.first().displayName)
        assertEquals(2, applications.size)
        assertEquals("application:x11:42", applications.first().id.value)
        assertEquals("Editor (Document)", applications.first().displayName)
        assertFalse(applications.first().capabilities.supportsApplicationAudioFilter)
    }

    @Test
    fun videoAdapterKeepsInitialDimensionsWhenWindowResizes() = runTest {
        val window = descriptor(0x10, 42, "Document", "Editor", 2, 2)
        val windowSystem = FakeX11WindowSystem(
            windows = listOf(window),
            capturedFrames = ArrayDeque(
                listOf(
                    solidFrame(window.bounds, red = 255, green = 0, blue = 0),
                    solidFrame(window.bounds.copy(width = 1, height = 1), red = 0, green = 0, blue = 255),
                ),
            ),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        var time = 0L
        val adapter = X11VideoCaptureAdapter(windowSystem, dispatcher) { time++ }
        val frames = async {
            adapter.frames(
                RecordingSettings(
                    captureSource = CaptureSource.Window(
                        CaptureSourceId(X11CaptureSourceIds.window(window.windowId)),
                        "Document",
                    ),
                    outputPath = "test.mp4",
                    frameRate = 30,
                    captureCursor = false,
                ),
            ).take(2).toList()
        }

        advanceUntilIdle()
        val captured = frames.await()

        assertEquals(listOf(2, 2), captured.map { it.width })
        assertEquals(listOf(2, 2), captured.map { it.height })
        assertContentEquals(byteArrayOf(255.toByte(), 0, 0, 255.toByte()), captured.first().pixelData!!.copyOf(4))
        assertContentEquals(byteArrayOf(0, 0, 255.toByte(), 255.toByte()), captured.last().pixelData!!.copyOf(4))
    }

    @Test
    fun convertsPackedXImagePixelsUsingByteOrderAndColourMasks() {
        val littleEndian = packedXImageToRgba(
            source = byteArrayOf(0x33, 0x22, 0x11, 0),
            width = 1,
            height = 1,
            bytesPerLine = 4,
            bitsPerPixel = 32,
            mostSignificantByteFirst = false,
            redMask = 0x00ff0000,
            greenMask = 0x0000ff00,
            blueMask = 0x000000ff,
        )
        val bigEndian = packedXImageToRgba(
            source = byteArrayOf(0, 0x11, 0x22, 0x33),
            width = 1,
            height = 1,
            bytesPerLine = 4,
            bitsPerPixel = 32,
            mostSignificantByteFirst = true,
            redMask = 0x00ff0000,
            greenMask = 0x0000ff00,
            blueMask = 0x000000ff,
        )
        val rgb565 = packedXImageToRgba(
            source = byteArrayOf(0, 0xf8.toByte()),
            width = 1,
            height = 1,
            bytesPerLine = 2,
            bitsPerPixel = 16,
            mostSignificantByteFirst = false,
            redMask = 0xf800,
            greenMask = 0x07e0,
            blueMask = 0x001f,
        )

        val expected = byteArrayOf(0x11, 0x22, 0x33, 0xff.toByte())
        assertContentEquals(expected, littleEndian)
        assertContentEquals(expected, bigEndian)
        assertContentEquals(byteArrayOf(0xff.toByte(), 0, 0, 0xff.toByte()), rgb565)
    }

    @Test
    fun cursorPainterClipsAtFrameEdges() {
        val pixels = ByteArray(4 * 4 * 4)

        X11RgbaCursorPainter.draw(pixels, 4, 4, hotspotX = 2, hotspotY = 2)

        assertTrue(pixels.any { it != 0.toByte() })
    }

    @Test
    fun factoryEnablesOnlyConfirmedX11LinuxSessions() {
        assertTrue(LinuxX11CaptureAdapterFactory.isSupported("Linux", "x11", "", ":0"))
        assertFalse(LinuxX11CaptureAdapterFactory.isSupported("Linux", "wayland", "wayland-0", ":0"))
        assertFalse(LinuxX11CaptureAdapterFactory.isSupported("Windows 11", "", "", ""))
    }
}

private class FakeX11WindowSystem(
    private val windows: List<X11WindowDescriptor>,
    private val capturedFrames: ArrayDeque<X11CapturedFrame> = ArrayDeque(),
) : X11WindowSystem {
    override fun listWindows(): List<X11WindowDescriptor> = windows

    override fun findWindow(windowId: Long): X11WindowDescriptor? = windows.firstOrNull { it.windowId == windowId }

    override fun captureWindow(windowId: Long): X11CapturedFrame = capturedFrames.removeFirst()

    override fun cursorPosition(): X11Point? = null
}

private fun descriptor(
    windowId: Long,
    processId: Long?,
    title: String,
    processName: String?,
    width: Int,
    height: Int,
) = X11WindowDescriptor(
    windowId = windowId,
    processId = processId,
    title = title,
    processName = processName,
    bounds = X11WindowBounds(0, 0, width, height),
    minimized = false,
)

private fun solidFrame(bounds: X11WindowBounds, red: Int, green: Int, blue: Int): X11CapturedFrame {
    val pixels = ByteArray(bounds.width * bounds.height * 4)
    for (offset in pixels.indices step 4) {
        pixels[offset] = red.toByte()
        pixels[offset + 1] = green.toByte()
        pixels[offset + 2] = blue.toByte()
        pixels[offset + 3] = 0xff.toByte()
    }
    return X11CapturedFrame(bounds, pixels)
}
