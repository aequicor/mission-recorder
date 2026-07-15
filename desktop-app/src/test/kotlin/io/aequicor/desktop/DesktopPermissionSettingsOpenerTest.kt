package io.aequicor.desktop

import io.aequicor.capture.platform.CapturePermission
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPermissionSettingsOpenerTest {
    @Test
    fun opensTheRequestedMacOsPrivacyPane() = runTest {
        val commands = mutableListOf<List<String>>()
        val opener = MacOsPermissionSettingsOpener(
            operatingSystemName = "Mac OS X",
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            startProcess = commands::add,
        )

        val screen = opener.open(CapturePermission.ScreenRecording)
        val microphone = opener.open(CapturePermission.Microphone)

        assertIs<DesktopPermissionSettingsOpenResult.Opened>(screen)
        assertIs<DesktopPermissionSettingsOpenResult.Opened>(microphone)
        assertEquals(
            listOf(
                listOf(
                    "/usr/bin/open",
                    "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture",
                ),
                listOf(
                    "/usr/bin/open",
                    "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone",
                ),
            ),
            commands,
        )
    }

    @Test
    fun rejectsPermissionLinksOutsideMacOs() = runTest {
        val opener = MacOsPermissionSettingsOpener(
            operatingSystemName = "Linux",
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val result = opener.open(CapturePermission.ScreenRecording)

        assertIs<DesktopPermissionSettingsOpenResult.Unavailable>(result)
    }
}
