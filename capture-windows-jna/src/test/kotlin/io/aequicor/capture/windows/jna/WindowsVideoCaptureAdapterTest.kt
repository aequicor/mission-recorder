package io.aequicor.capture.windows.jna

import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.PixelFormat
import io.aequicor.capture.core.RecordingError
import io.aequicor.capture.core.RecordingException
import io.aequicor.capture.core.RecordingSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WindowsVideoCaptureAdapterTest {
    @Test
    fun passesNativeBgraPixelsWithoutPerPixelConversion() = runBlocking {
        val selectedWindow = window(handle = 1, processId = 42, title = "Editor", width = 2, height = 1)
        val system = FakeWindowsWindowSystem(
            windows = listOf(selectedWindow),
            frames = mapOf(
                selectedWindow.handle to WindowsCapturedFrame(
                    bounds = selectedWindow.bounds,
                    bgraPixels = byteArrayOf(10, 20, 30, 0, 40, 50, 60, 0),
                ),
            ),
        )
        val frame = WindowsVideoCaptureAdapter(system, Dispatchers.Unconfined, incrementingNanoTime())
            .frames(settings(windowSource(selectedWindow), captureCursor = false))
            .first()

        assertEquals(PixelFormat.Bgra8888, frame.pixelFormat)
        assertContentEquals(byteArrayOf(10, 20, 30, 0, 40, 50, 60, 0), frame.pixelData)
    }

    @Test
    fun paintsCursorOnlyWhenItIsInsideTheWindow() = runBlocking {
        val selectedWindow = window(handle = 1, processId = 42, title = "Editor", x = 100, y = 200)
        val basePixels = solidBgra(selectedWindow.bounds, blue = 0xff, green = 0xff, red = 0xff)
        val system = FakeWindowsWindowSystem(
            windows = listOf(selectedWindow),
            frames = mapOf(
                selectedWindow.handle to WindowsCapturedFrame(selectedWindow.bounds, basePixels),
            ),
            cursor = WindowsPoint(105, 205),
        )

        val frame = WindowsVideoCaptureAdapter(system, Dispatchers.Unconfined, incrementingNanoTime())
            .frames(settings(windowSource(selectedWindow), captureCursor = true))
            .first()

        assertTrue(
            requireNotNull(frame.pixelData)
                .toList()
                .chunked(4)
                .any { pixel -> pixel.take(3).any { channel -> channel != 0xff.toByte() } },
        )
    }

    @Test
    fun applicationCaptureSelectsLargestNonMinimizedWindow() = runBlocking {
        val minimized = window(handle = 1, processId = 42, title = "Large", width = 1200, minimized = true)
        val primary = window(handle = 2, processId = 42, title = "Editor", width = 800)
        val tool = window(handle = 3, processId = 42, title = "Find", width = 300)
        val system = FakeWindowsWindowSystem(
            windows = listOf(minimized, primary, tool),
            frames = listOf(minimized, primary, tool).associate { descriptor ->
                descriptor.handle to WindowsCapturedFrame(descriptor.bounds, solidBgra(descriptor.bounds))
            },
        )

        WindowsVideoCaptureAdapter(system, Dispatchers.Unconfined, incrementingNanoTime())
            .frames(
                settings(
                    CaptureSource.Application(
                        id = CaptureSourceId(WindowsCaptureSourceIds.application(42)),
                        displayName = "Editor",
                    ),
                    captureCursor = false,
                ),
            )
            .first()

        assertEquals(primary.handle, system.lastCapturedHandle)
    }

    @Test
    fun reportsClosedWindowAsUnavailable() = runBlocking {
        val system = FakeWindowsWindowSystem(windows = emptyList())
        val source = CaptureSource.Window(
            id = CaptureSourceId(WindowsCaptureSourceIds.window(99)),
            displayName = "Closed",
        )

        val exception = assertFailsWith<RecordingException> {
            WindowsVideoCaptureAdapter(system, Dispatchers.Unconfined, incrementingNanoTime())
                .frames(settings(source, captureCursor = false))
                .first()
        }

        assertIs<RecordingError.SourceUnavailable>(exception.error)
        Unit
    }

    @Test
    fun keepsDesktopCaptureLifecycleOnInjectedThread() {
        val system = FakeWindowsWindowSystem(windows = emptyList())
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "windows-capture-test") }
            .asCoroutineDispatcher()
            .use { dispatcher ->
                runBlocking {
                    WindowsVideoCaptureAdapter(system, dispatcher, incrementingNanoTime())
                        .frames(
                            settings(
                                CaptureSource.Screen(CaptureSourceId("screen:all"), "All screens"),
                                captureCursor = false,
                            ),
                        )
                        .first()
                }
            }

        assertEquals(1, system.screenCaptureThreads.size)
        assertTrue(system.screenCaptureThreads.single().startsWith("windows-capture-test"))
    }
}

internal class FakeWindowsWindowSystem(
    private val windows: List<WindowsWindowDescriptor>,
    private val frames: Map<Long, WindowsCapturedFrame> = windows.associate { descriptor ->
        descriptor.handle to WindowsCapturedFrame(descriptor.bounds, solidBgra(descriptor.bounds))
    },
    private val cursor: WindowsPoint? = null,
) : WindowsWindowSystem {
    val screenCaptureThreads = mutableSetOf<String>()
    var lastCapturedHandle: Long? = null
        private set

    override fun listWindows(): List<WindowsWindowDescriptor> = windows

    override fun findWindow(handle: Long): WindowsWindowDescriptor? = windows.firstOrNull { it.handle == handle }

    override fun captureWindow(handle: Long): WindowsCapturedFrame {
        lastCapturedHandle = handle
        return frames[handle] ?: throw WindowsCaptureFailure("No fake frame for $handle.")
    }

    override fun openScreenCapture(bounds: WindowsWindowBounds, frameRate: Int): WindowsScreenCapture {
        screenCaptureThreads += Thread.currentThread().name
        return object : WindowsScreenCapture {
            override fun capture(): WindowsCapturedFrame {
                screenCaptureThreads += Thread.currentThread().name
                return WindowsCapturedFrame(bounds, solidBgra(bounds))
            }

            override fun close() {
                screenCaptureThreads += Thread.currentThread().name
            }
        }
    }

    override fun cursorPosition(): WindowsPoint? = cursor
}

internal fun window(
    handle: Long,
    processId: Long,
    title: String,
    processName: String? = null,
    x: Int = 0,
    y: Int = 0,
    width: Int = 32,
    height: Int = 32,
    minimized: Boolean = false,
): WindowsWindowDescriptor = WindowsWindowDescriptor(
    handle = handle,
    processId = processId,
    title = title,
    processName = processName,
    bounds = WindowsWindowBounds(x = x, y = y, width = width, height = height),
    minimized = minimized,
)

private fun windowSource(window: WindowsWindowDescriptor): CaptureSource.Window = CaptureSource.Window(
    id = CaptureSourceId(WindowsCaptureSourceIds.window(window.handle)),
    displayName = window.title,
)

private fun settings(source: CaptureSource, captureCursor: Boolean): RecordingSettings = RecordingSettings(
    captureSource = source,
    outputPath = "unused.mp4",
    captureCursor = captureCursor,
)

internal fun solidBgra(
    bounds: WindowsWindowBounds,
    blue: Int = 0,
    green: Int = 0,
    red: Int = 0,
): ByteArray = ByteArray(bounds.width * bounds.height * 4).also { pixels ->
    for (offset in pixels.indices step 4) {
        pixels[offset] = blue.toByte()
        pixels[offset + 1] = green.toByte()
        pixels[offset + 2] = red.toByte()
    }
}

private fun incrementingNanoTime(): () -> Long {
    var value = 0L
    return { ++value }
}
