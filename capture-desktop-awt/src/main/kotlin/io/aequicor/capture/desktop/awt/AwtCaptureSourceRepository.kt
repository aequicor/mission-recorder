package io.aequicor.capture.desktop.awt

import io.aequicor.capture.core.CaptureCapabilities
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.platform.CaptureSourceRepository
import io.aequicor.capture.platform.CaptureSourceRequest
import java.awt.GraphicsEnvironment

class AwtCaptureSourceRepository : CaptureSourceRepository {
    override suspend fun listSources(request: CaptureSourceRequest): List<CaptureSource> {
        if (GraphicsEnvironment.isHeadless()) {
            return emptyList()
        }

        val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.toList()
        return buildList {
            if (request.includeScreens && devices.isNotEmpty()) {
                add(
                    CaptureSource.Screen(
                        id = CaptureSourceId("screen:all"),
                        displayName = "All screens",
                        capabilities = CaptureCapabilities(supportsCursorCapture = true, supportsRegionCapture = true),
                    ),
                )
            }
            if (request.includeMonitors) {
                devices.forEachIndexed { index, device ->
                    val bounds = device.defaultConfiguration.bounds
                    add(
                        CaptureSource.Monitor(
                            id = CaptureSourceId("monitor:$index"),
                            displayName = "Monitor ${index + 1} (${bounds.width}x${bounds.height})",
                            index = index,
                            capabilities = CaptureCapabilities(
                                supportsCursorCapture = true,
                                supportsRegionCapture = true,
                            ),
                        ),
                    )
                }
            }
        }
    }
}
