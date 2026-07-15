package io.aequicor.capture.macos.coregraphics

import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.platform.CapturePermission
import io.aequicor.capture.platform.CaptureSourceRequest
import io.aequicor.capture.platform.PermissionStatus
import io.aequicor.capture.platform.PermissionUserAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MacCaptureAdaptersTest {
    @Test
    fun cursorPainterAddsHighlightAroundHotspot() {
        val pixels = ByteArray(40 * 40 * 4) { 0xff.toByte() }

        MacRgbaCursorPainter.draw(pixels, width = 40, height = 40, hotspotX = 20, hotspotY = 20)

        val highlightedPixel = pixels.copyOfRange((20 * 40 + 10) * 4, (20 * 40 + 10) * 4 + 3)
        assertTrue(highlightedPixel.any { channel -> channel != 0xff.toByte() })
    }

    @Test
    fun repositoryListsWindowsAndGroupsApplications() = runTest {
        val windows = listOf(
            descriptor(10, 42, "Document", "Editor", 800, 600),
            descriptor(11, 42, "Find", "Editor", 300, 200),
            descriptor(20, 84, "Terminal", "Terminal", 900, 700),
        )
        val repository = MacCaptureSourceRepository(
            FakeMacWindowSystem(windows),
            UnconfinedTestDispatcher(testScheduler),
        )

        val sources = repository.listSources(CaptureSourceRequest())

        assertEquals(3, sources.filterIsInstance<CaptureSource.Window>().size)
        val apps = sources.filterIsInstance<CaptureSource.Application>()
        assertEquals(2, apps.size)
        assertEquals("application:macos:42", apps.first().id.value)
        assertEquals("Editor (Document)", apps.first().displayName)
        assertFalse(apps.first().capabilities.supportsApplicationAudioFilter)
    }

    @Test
    fun videoAdapterPreservesRetinaResolutionAndInitialStreamSize() = runTest {
        val window = descriptor(10, 42, "Document", "Editor", 2, 2)
        val frames = ArrayDeque(
            listOf(
                solidFrame(window.bounds, 4, 4, red = 255, blue = 0),
                solidFrame(window.bounds.copy(width = 1, height = 1), 2, 2, red = 0, blue = 255),
            ),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val adapter = MacVideoCaptureAdapter(FakeMacWindowSystem(listOf(window), frames), dispatcher)
        val capture = async {
            adapter.frames(
                RecordingSettings(
                    captureSource = CaptureSource.Window(CaptureSourceId("window:macos:10"), "Document"),
                    outputPath = "test.mp4",
                    captureCursor = false,
                ),
            ).take(2).toList()
        }

        advanceUntilIdle()
        val result = capture.await()

        assertEquals(listOf(4, 4), result.map { it.width })
        assertEquals(listOf(4, 4), result.map { it.height })
        assertEquals(2.0, result.first().scaleFactor)
        assertContentEquals(byteArrayOf(255.toByte(), 0, 0, 255.toByte()), result.first().pixelData!!.copyOf(4))
        assertContentEquals(byteArrayOf(0, 0, 255.toByte(), 255.toByte()), result.last().pixelData!!.copyOf(4))
    }

    @Test
    fun videoAdapterPaintsPressedInputsNearCursor() = runTest {
        val window = descriptor(10, 42, "Document", "Editor", 80, 60)
        val frames = ArrayDeque(
            listOf(MacCapturedFrame(window.bounds, 80, 60, ByteArray(80 * 60 * 4) { 0xff.toByte() })),
        )
        val windowSystem = FakeMacWindowSystem(
            windows = listOf(window),
            frames = frames,
            cursor = MacPoint(40, 30),
            pressedInputs = listOf("Cmd", "C"),
        )
        val adapter = MacVideoCaptureAdapter(windowSystem, UnconfinedTestDispatcher(testScheduler)) { 1L }

        val frame = adapter.frames(
            RecordingSettings(
                captureSource = CaptureSource.Window(CaptureSourceId("window:macos:10"), "Document"),
                outputPath = "test.mp4",
                captureCursor = false,
                showInputOverlay = true,
            ),
        ).first()

        assertTrue(requireNotNull(frame.pixelData).containsNonWhiteColor())
        assertEquals(1, windowSystem.pressedInputReadCount)
        assertTrue(!frame.importantFrame)
    }

    @Test
    fun permissionGatewayRequestsScreenOnlyAfterExplicitAuthorization() = runTest {
        val screen = FakeScreenPermissionApi(granted = false, requestResult = true)
        val gateway = MacOsPermissionGateway(screen) { MacMicrophoneAuthorization.Authorized }

        val checked = gateway.check(setOf(CapturePermission.ScreenRecording, CapturePermission.Microphone))
        val screenStatus = assertIs<PermissionStatus.RequiresUserAction>(
            checked.status(CapturePermission.ScreenRecording),
        )
        assertEquals(PermissionUserAction.Request, screenStatus.action)
        assertIs<PermissionStatus.Granted>(checked.status(CapturePermission.Microphone))
        assertEquals(0, screen.requestCount)

        val requested = gateway.request(setOf(CapturePermission.ScreenRecording))
        assertIs<PermissionStatus.Granted>(requested.status(CapturePermission.ScreenRecording))
        assertEquals(1, screen.requestCount)
    }

    @Test
    fun permissionGatewayDirectsToSettingsAfterScreenRequestIsDenied() = runTest {
        val gateway = MacOsPermissionGateway(FakeScreenPermissionApi(granted = false, requestResult = false)) {
            MacMicrophoneAuthorization.Authorized
        }

        val report = gateway.request(setOf(CapturePermission.ScreenRecording))

        val status = assertIs<PermissionStatus.RequiresUserAction>(
            report.status(CapturePermission.ScreenRecording),
        )
        assertEquals(PermissionUserAction.OpenSettings, status.action)
        assertTrue(status.restartMayBeRequired)
    }

    @Test
    fun permissionGatewayFailsClosedForDeniedMicrophoneAndSystemAudio() = runTest {
        val gateway = MacOsPermissionGateway(FakeScreenPermissionApi(true, true)) {
            MacMicrophoneAuthorization.Denied
        }

        val report = gateway.check(setOf(CapturePermission.Microphone, CapturePermission.SystemAudio))

        assertIs<PermissionStatus.RequiresUserAction>(report.status(CapturePermission.Microphone))
        assertIs<PermissionStatus.Unsupported>(report.status(CapturePermission.SystemAudio))
    }

    @Test
    fun permissionGatewayRequiresASeparateActionBeforeTheInitialMicrophonePrompt() = runTest {
        val gateway = MacOsPermissionGateway(FakeScreenPermissionApi(true, true)) {
            MacMicrophoneAuthorization.NotDetermined
        }

        val checked = gateway.check(setOf(CapturePermission.Microphone))
        val requested = gateway.request(setOf(CapturePermission.Microphone))

        val status = assertIs<PermissionStatus.RequiresUserAction>(
            checked.status(CapturePermission.Microphone),
        )
        assertEquals(PermissionUserAction.Request, status.action)
        assertIs<PermissionStatus.Granted>(requested.status(CapturePermission.Microphone))
    }

    @Test
    fun factoryEnablesOnlyMacOs() {
        assertTrue(MacCaptureAdapterFactory.isSupported("Mac OS X"))
        assertTrue(MacCaptureAdapterFactory.isSupported("Darwin"))
        assertFalse(MacCaptureAdapterFactory.isSupported("Linux"))
    }
}

private class FakeScreenPermissionApi(
    private var granted: Boolean,
    private val requestResult: Boolean,
) : MacScreenPermissionApi {
    var requestCount = 0
        private set

    override fun isGranted(): Boolean = granted

    override fun request(): Boolean {
        requestCount += 1
        granted = requestResult
        return requestResult
    }
}

private class FakeMacWindowSystem(
    private val windows: List<MacWindowDescriptor>,
    private val frames: ArrayDeque<MacCapturedFrame> = ArrayDeque(),
    private val cursor: MacPoint? = null,
    private val pressedInputs: List<String> = emptyList(),
) : MacWindowSystem {
    var pressedInputReadCount: Int = 0
        private set

    override fun listWindows(): List<MacWindowDescriptor> = windows
    override fun findWindow(windowId: Long): MacWindowDescriptor? = windows.firstOrNull { it.windowId == windowId }
    override fun captureWindow(windowId: Long): MacCapturedFrame = frames.removeFirst()
    override fun cursorPosition(): MacPoint? = cursor

    override fun pressedInputs(): List<String> {
        pressedInputReadCount += 1
        return pressedInputs
    }
}

private fun descriptor(
    windowId: Long,
    processId: Long,
    title: String,
    processName: String,
    width: Int,
    height: Int,
) = MacWindowDescriptor(
    windowId,
    processId,
    title,
    processName,
    MacWindowBounds(0, 0, width, height),
    minimized = false,
)

private fun solidFrame(bounds: MacWindowBounds, width: Int, height: Int, red: Int, blue: Int): MacCapturedFrame {
    val pixels = ByteArray(width * height * 4)
    for (offset in pixels.indices step 4) {
        pixels[offset] = red.toByte()
        pixels[offset + 2] = blue.toByte()
        pixels[offset + 3] = 0xff.toByte()
    }
    return MacCapturedFrame(bounds, width, height, pixels)
}

private fun ByteArray.containsNonWhiteColor(): Boolean = asList()
    .chunked(4)
    .any { pixel -> pixel.take(3).any { channel -> channel != 0xff.toByte() } }
