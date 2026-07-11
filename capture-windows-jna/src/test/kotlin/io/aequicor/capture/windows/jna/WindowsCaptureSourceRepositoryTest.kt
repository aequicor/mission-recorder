package io.aequicor.capture.windows.jna

import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.platform.CaptureSourceRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WindowsCaptureSourceRepositoryTest {
    @Test
    fun listsWindowsAndGroupsApplicationsByProcess() = runBlocking {
        val repository = WindowsCaptureSourceRepository(
            FakeWindowsWindowSystem(
                windows = listOf(
                    window(handle = 1, processId = 42, title = "Document", processName = "editor.exe", width = 800),
                    window(handle = 2, processId = 42, title = "Find", processName = "editor.exe", width = 300),
                    window(handle = 3, processId = 7, title = "Browser", processName = "browser.exe", width = 1200),
                ),
            ),
        )

        val sources = repository.listSources()

        assertEquals(3, sources.filterIsInstance<CaptureSource.Window>().size)
        val applications = sources.filterIsInstance<CaptureSource.Application>()
        assertEquals(2, applications.size)
        assertEquals("application:win32:7", applications[0].id.value)
        assertEquals("browser.exe (Browser)", applications[0].displayName)
        assertTrue(applications.all { source -> source.capabilities.supportsCursorCapture })
        assertFalse(applications.any { source -> source.capabilities.supportsApplicationAudioFilter })
    }

    @Test
    fun honorsRequestedSourceKinds() = runBlocking {
        val repository = WindowsCaptureSourceRepository(
            FakeWindowsWindowSystem(
                windows = listOf(window(handle = 0x2a, processId = 5, title = "Notes")),
            ),
        )

        val sources = repository.listSources(
            CaptureSourceRequest(
                includeScreens = true,
                includeMonitors = true,
                includeWindows = true,
                includeApplications = false,
            ),
        )

        val source = assertIs<CaptureSource.Window>(sources.single())
        assertEquals("window:win32:2a", source.id.value)
    }
}
