package io.aequicor.capture.linux.x11

import io.aequicor.capture.core.CaptureCapabilities
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.platform.CaptureSourceRepository
import io.aequicor.capture.platform.CaptureSourceRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal class X11CaptureSourceRepository(
    private val windowSystem: X11WindowSystem,
    private val dispatcher: CoroutineDispatcher,
) : CaptureSourceRepository {
    override suspend fun listSources(request: CaptureSourceRequest): List<CaptureSource> {
        if (!request.includeWindows && !request.includeApplications) {
            return emptyList()
        }
        val windows = withContext(dispatcher) { windowSystem.listWindows() }
        return buildList {
            if (request.includeWindows) {
                windows.forEach { window -> add(window.toCaptureSource()) }
            }
            if (request.includeApplications) {
                windows.filter { it.processId != null }
                    .groupBy { requireNotNull(it.processId) }
                    .toSortedMap()
                    .forEach { (processId, applicationWindows) ->
                        val primary = applicationWindows.primaryWindow()
                        add(
                            CaptureSource.Application(
                                id = CaptureSourceId(X11CaptureSourceIds.application(processId)),
                                displayName = applicationDisplayName(primary),
                                capabilities = X11_CAPTURE_CAPABILITIES,
                            ),
                        )
                    }
            }
        }
    }
}

private fun X11WindowDescriptor.toCaptureSource() = CaptureSource.Window(
    id = CaptureSourceId(X11CaptureSourceIds.window(windowId)),
    displayName = processName
        ?.takeUnless { title.contains(it, ignoreCase = true) }
        ?.let { "$title ($it)" }
        ?: title,
    capabilities = X11_CAPTURE_CAPABILITIES,
)

private fun applicationDisplayName(primary: X11WindowDescriptor): String =
    primary.processName?.let { "$it (${primary.title})" } ?: primary.title

internal fun List<X11WindowDescriptor>.primaryWindow(): X11WindowDescriptor =
    sortedWith(
        compareByDescending<X11WindowDescriptor> { !it.minimized }
            .thenByDescending { it.bounds.area },
    ).first()

private val X11_CAPTURE_CAPABILITIES = CaptureCapabilities(
    supportsCursorCapture = true,
    supportsRegionCapture = false,
    supportsWindowCapture = true,
    supportsApplicationAudioFilter = false,
)
