package io.aequicor.capture.windows.jna

import io.aequicor.capture.core.VideoCaptureAdapter
import io.aequicor.capture.platform.CaptureSourceRepository

class WindowsCaptureAdapters internal constructor(
    val sourceRepository: CaptureSourceRepository,
    val videoCaptureAdapter: VideoCaptureAdapter,
)

object WindowsCaptureAdapterFactory {
    fun createIfSupported(): WindowsCaptureAdapters? {
        if (!isSupported()) {
            return null
        }
        val windowSystem = JnaWindowsWindowSystem()
        return WindowsCaptureAdapters(
            sourceRepository = WindowsCaptureSourceRepository(windowSystem),
            videoCaptureAdapter = WindowsVideoCaptureAdapter(windowSystem),
        )
    }

    fun isSupported(osName: String = System.getProperty("os.name")): Boolean =
        osName.startsWith("Windows", ignoreCase = true)
}
