package io.aequicor.capture.windows.jna

import io.aequicor.capture.core.CaptureCapabilities
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.platform.CaptureSourceRepository
import io.aequicor.capture.platform.CaptureSourceRequest

class WindowsCaptureSourceRepository internal constructor(
    private val windowSystem: WindowsWindowSystem,
) : CaptureSourceRepository {
    override suspend fun listSources(request: CaptureSourceRequest): List<CaptureSource> {
        if (!request.includeWindows && !request.includeApplications) {
            return emptyList()
        }

        val windows = windowSystem.listWindows()
        return buildList {
            if (request.includeWindows) {
                windows.forEach { window -> add(window.toCaptureSource()) }
            }
            if (request.includeApplications) {
                windows
                    .groupBy(WindowsWindowDescriptor::processId)
                    .toSortedMap()
                    .forEach { (processId, applicationWindows) ->
                        val primary = applicationWindows.primaryWindow()
                        add(
                            CaptureSource.Application(
                                id = CaptureSourceId(WindowsCaptureSourceIds.application(processId)),
                                displayName = applicationDisplayName(primary),
                                capabilities = WINDOWS_CAPTURE_CAPABILITIES,
                            ),
                        )
                    }
            }
        }
    }
}

private fun WindowsWindowDescriptor.toCaptureSource(): CaptureSource.Window = CaptureSource.Window(
    id = CaptureSourceId(WindowsCaptureSourceIds.window(handle)),
    displayName = windowDisplayName(this),
    capabilities = WINDOWS_CAPTURE_CAPABILITIES,
)

private fun windowDisplayName(window: WindowsWindowDescriptor): String =
    window.processName
        ?.takeUnless { processName -> window.title.contains(processName, ignoreCase = true) }
        ?.let { processName -> "${window.title} ($processName)" }
        ?: window.title

private fun applicationDisplayName(primary: WindowsWindowDescriptor): String =
    primary.processName
        ?.let { processName -> "$processName (${primary.title})" }
        ?: primary.title

internal fun List<WindowsWindowDescriptor>.primaryWindow(): WindowsWindowDescriptor =
    sortedWith(
        compareByDescending<WindowsWindowDescriptor> { window -> !window.minimized }
            .thenByDescending { window -> window.bounds.area },
    ).first()

private val WINDOWS_CAPTURE_CAPABILITIES = CaptureCapabilities(
    supportsCursorCapture = true,
    supportsRegionCapture = false,
    supportsWindowCapture = true,
    supportsApplicationAudioFilter = false,
)
