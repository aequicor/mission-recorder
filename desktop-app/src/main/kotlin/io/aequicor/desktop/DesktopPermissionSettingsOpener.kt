package io.aequicor.desktop

import io.aequicor.capture.platform.CapturePermission
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun interface DesktopPermissionSettingsOpener {
    suspend fun open(permission: CapturePermission): DesktopPermissionSettingsOpenResult
}

internal sealed interface DesktopPermissionSettingsOpenResult {
    data object Opened : DesktopPermissionSettingsOpenResult
    data class Unavailable(val message: String) : DesktopPermissionSettingsOpenResult
}

internal data object UnavailableDesktopPermissionSettingsOpener : DesktopPermissionSettingsOpener {
    override suspend fun open(permission: CapturePermission): DesktopPermissionSettingsOpenResult =
        DesktopPermissionSettingsOpenResult.Unavailable("Opening permission settings is unavailable.")
}

internal class MacOsPermissionSettingsOpener(
    private val operatingSystemName: String = System.getProperty("os.name").orEmpty(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val startProcess: (List<String>) -> Unit = { command -> ProcessBuilder(command).start() },
) : DesktopPermissionSettingsOpener {
    override suspend fun open(permission: CapturePermission): DesktopPermissionSettingsOpenResult =
        withContext(dispatcher) {
            if (!operatingSystemName.startsWith("Mac", ignoreCase = true) &&
                !operatingSystemName.contains("Darwin", ignoreCase = true)
            ) {
                return@withContext DesktopPermissionSettingsOpenResult.Unavailable(
                    "Permission settings links are available only on macOS.",
                )
            }
            val pane = when (permission) {
                CapturePermission.ScreenRecording -> "Privacy_ScreenCapture"
                CapturePermission.Microphone -> "Privacy_Microphone"
                CapturePermission.SystemAudio -> return@withContext DesktopPermissionSettingsOpenResult.Unavailable(
                    "The current macOS backend does not support system-audio capture.",
                )
            }
            runCatching {
                startProcess(
                    listOf(
                        "/usr/bin/open",
                        "x-apple.systempreferences:com.apple.preference.security?$pane",
                    ),
                )
                DesktopPermissionSettingsOpenResult.Opened
            }.getOrElse { failure ->
                DesktopPermissionSettingsOpenResult.Unavailable(
                    failure.message ?: "Could not open macOS permission settings.",
                )
            }
        }
}
