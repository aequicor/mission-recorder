package io.aequicor.capture.macos.coregraphics

import io.aequicor.capture.core.CaptureCapabilities
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.platform.CaptureSourceRepository
import io.aequicor.capture.platform.CaptureSourceRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal class MacCaptureSourceRepository(
    private val windowSystem: MacWindowSystem,
    private val dispatcher: CoroutineDispatcher,
) : CaptureSourceRepository {
    override suspend fun listSources(request: CaptureSourceRequest): List<CaptureSource> {
        if (!request.includeWindows && !request.includeApplications) return emptyList()
        val windows = withContext(dispatcher) { windowSystem.listWindows() }
        return buildList {
            if (request.includeWindows) {
                windows.forEach { window ->
                    add(
                        CaptureSource.Window(
                            CaptureSourceId(MacCaptureSourceIds.window(window.windowId)),
                            window.displayName(),
                            MAC_CAPTURE_CAPABILITIES,
                        ),
                    )
                }
            }
            if (request.includeApplications) {
                windows.groupBy(MacWindowDescriptor::processId).toSortedMap().forEach { (pid, appWindows) ->
                    val primary = appWindows.primaryWindow()
                    add(
                        CaptureSource.Application(
                            CaptureSourceId(MacCaptureSourceIds.application(pid)),
                            "${primary.processName} (${primary.title})",
                            MAC_CAPTURE_CAPABILITIES,
                        ),
                    )
                }
            }
        }
    }
}

private fun MacWindowDescriptor.displayName(): String =
    if (title.contains(processName, ignoreCase = true)) title else "$title ($processName)"

internal fun List<MacWindowDescriptor>.primaryWindow(): MacWindowDescriptor =
    sortedWith(
        compareByDescending<MacWindowDescriptor> { !it.minimized }
            .thenByDescending { it.bounds.area },
    ).first()

private val MAC_CAPTURE_CAPABILITIES = CaptureCapabilities(
    supportsCursorCapture = true,
    supportsWindowCapture = true,
    supportsApplicationAudioFilter = false,
)
