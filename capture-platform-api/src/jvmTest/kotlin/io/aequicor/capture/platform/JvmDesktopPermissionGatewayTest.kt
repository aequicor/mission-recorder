package io.aequicor.capture.platform

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JvmDesktopPermissionGatewayTest {
    @Test
    fun macOsReportsRequiredSystemSettingsActions() = runTest {
        val report = JvmDesktopPermissionGateway("Mac OS X").check(CapturePermission.entries.toSet())

        assertIs<PermissionStatus.RequiresUserAction>(report.status(CapturePermission.ScreenRecording))
        assertIs<PermissionStatus.RequiresUserAction>(report.status(CapturePermission.Microphone))
        assertIs<PermissionStatus.RequiresUserAction>(report.status(CapturePermission.SystemAudio))
    }

    @Test
    fun windowsUsesCaptureApiAccessChecks() = runTest {
        val report = JvmDesktopPermissionGateway("Windows 11").check(CapturePermission.entries.toSet())

        assertTrue(report.allGrantedFor(CapturePermission.entries.toSet()))
    }

    @Test
    fun linuxX11AllowsAwtScreenAndMicrophoneButRejectsSystemAudio() = runTest {
        val report = JvmDesktopPermissionGateway(
            osName = "Linux",
            sessionType = "x11",
            waylandDisplay = "",
            x11Display = ":0",
        ).check(CapturePermission.entries.toSet())

        assertIs<PermissionStatus.Granted>(report.status(CapturePermission.ScreenRecording))
        assertIs<PermissionStatus.Granted>(report.status(CapturePermission.Microphone))
        assertIs<PermissionStatus.Unsupported>(report.status(CapturePermission.SystemAudio))
    }

    @Test
    fun linuxWaylandFailsClosedBeforeOpeningScreenCapture() = runTest {
        val report = JvmDesktopPermissionGateway(
            osName = "Linux",
            sessionType = "wayland",
            waylandDisplay = "wayland-0",
            x11Display = ":0",
        ).check(setOf(CapturePermission.ScreenRecording))

        val status = assertIs<PermissionStatus.Unsupported>(report.status(CapturePermission.ScreenRecording))
        assertTrue(status.reason.contains("xdg-desktop-portal"))
    }

    @Test
    fun linuxAllowsSystemAudioOnlyWhenPulseAdapterIsAvailable() = runTest {
        val report = JvmDesktopPermissionGateway(
            osName = "Linux",
            sessionType = "x11",
            x11Display = ":0",
            linuxSystemAudioAvailable = true,
        ).check(setOf(CapturePermission.SystemAudio))

        assertIs<PermissionStatus.Granted>(report.status(CapturePermission.SystemAudio))
    }

    @Test
    fun unknownLinuxSessionFailsClosedForScreenCapture() = runTest {
        val report = JvmDesktopPermissionGateway(
            osName = "Linux",
            sessionType = "",
            waylandDisplay = "",
            x11Display = "",
        ).check(setOf(CapturePermission.ScreenRecording))

        assertIs<PermissionStatus.Unsupported>(report.status(CapturePermission.ScreenRecording))
    }

    @Test
    fun unknownPlatformsFailClosed() = runTest {
        val report = JvmDesktopPermissionGateway("Plan 9").check(setOf(CapturePermission.ScreenRecording))

        assertIs<PermissionStatus.Unsupported>(report.status(CapturePermission.ScreenRecording))
    }
}
