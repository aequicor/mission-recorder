package io.aequicor.capture.platform

class JvmDesktopPermissionGateway(
    osName: String = System.getProperty("os.name").orEmpty(),
    sessionType: String = System.getenv("XDG_SESSION_TYPE").orEmpty(),
    waylandDisplay: String = System.getenv("WAYLAND_DISPLAY").orEmpty(),
    x11Display: String = System.getenv("DISPLAY").orEmpty(),
    private val linuxSystemAudioAvailable: Boolean = false,
) : PermissionGateway {
    private val platform = DesktopPlatform.from(osName)
    private val desktopSession = DesktopSession.from(sessionType, waylandDisplay, x11Display)

    override suspend fun check(required: Set<CapturePermission>): PermissionReport =
        PermissionReport(required.associateWith(::status))

    override suspend fun request(required: Set<CapturePermission>): PermissionReport = check(required)

    private fun status(permission: CapturePermission): PermissionStatus = when (platform) {
        DesktopPlatform.Windows -> PermissionStatus.Granted

        DesktopPlatform.Linux -> linuxStatus(permission)

        DesktopPlatform.MacOs -> when (permission) {
            CapturePermission.ScreenRecording -> PermissionStatus.RequiresUserAction(
                instructions =
                    "Allow Mission Recorder in System Settings > Privacy & Security > Screen Recording, then retry.",
                restartMayBeRequired = true,
            )
            CapturePermission.Microphone -> PermissionStatus.RequiresUserAction(
                "Allow Mission Recorder in System Settings > Privacy & Security > Microphone, then retry.",
            )
            CapturePermission.SystemAudio -> PermissionStatus.Unsupported(
                "The current macOS desktop backend does not support system-audio capture.",
            )
        }

        DesktopPlatform.Unknown -> PermissionStatus.Unsupported(
            "Permission handling is not implemented for this desktop platform.",
        )
    }

    private fun linuxStatus(permission: CapturePermission): PermissionStatus = when (permission) {
        CapturePermission.Microphone -> PermissionStatus.Granted
        CapturePermission.SystemAudio -> if (linuxSystemAudioAvailable) {
            PermissionStatus.Granted
        } else {
            PermissionStatus.Unsupported(
                "Linux system-audio capture requires pactl and parec with an available PulseAudio monitor source.",
            )
        }
        CapturePermission.ScreenRecording -> when (desktopSession) {
            DesktopSession.X11 -> PermissionStatus.Granted
            DesktopSession.Wayland -> PermissionStatus.Unsupported(
                "Wayland screen capture requires xdg-desktop-portal and PipeWire consent, " +
                    "which are not implemented in the current backend.",
            )
            DesktopSession.Unknown -> PermissionStatus.Unsupported(
                "Could not verify an X11 desktop session. Wayland/portal capture is not implemented.",
            )
        }
    }
}

private enum class DesktopSession {
    X11,
    Wayland,
    Unknown;

    companion object {
        fun from(sessionType: String, waylandDisplay: String, x11Display: String): DesktopSession {
            val normalized = sessionType.trim().lowercase()
            return when {
                normalized == "wayland" || waylandDisplay.isNotBlank() -> Wayland
                normalized == "x11" || x11Display.isNotBlank() -> X11
                else -> Unknown
            }
        }
    }
}

private enum class DesktopPlatform {
    Windows,
    MacOs,
    Linux,
    Unknown;

    companion object {
        fun from(osName: String): DesktopPlatform {
            val normalized = osName.lowercase()
            return when {
                "win" in normalized -> Windows
                "mac" in normalized || "darwin" in normalized -> MacOs
                "linux" in normalized -> Linux
                else -> Unknown
            }
        }
    }
}
