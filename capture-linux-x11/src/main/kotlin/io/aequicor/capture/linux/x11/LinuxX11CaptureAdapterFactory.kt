package io.aequicor.capture.linux.x11

import io.aequicor.capture.core.VideoCaptureAdapter
import io.aequicor.capture.platform.CaptureSourceRepository
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

class LinuxX11CaptureAdapters internal constructor(
    val sourceRepository: CaptureSourceRepository,
    val videoCaptureAdapter: VideoCaptureAdapter,
    private val dispatcher: ExecutorCoroutineDispatcher,
) : AutoCloseable {
    override fun close() {
        dispatcher.close()
    }
}

object LinuxX11CaptureAdapterFactory {
    fun createIfSupported(): LinuxX11CaptureAdapters? {
        if (!isSupported()) return null
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mission-recorder-x11-capture").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val windowSystem = runCatching { JnaX11WindowSystem().also(JnaX11WindowSystem::probe) }
            .getOrElse {
                dispatcher.close()
                return null
            }
        return LinuxX11CaptureAdapters(
            sourceRepository = X11CaptureSourceRepository(windowSystem, dispatcher),
            videoCaptureAdapter = X11VideoCaptureAdapter(windowSystem, dispatcher),
            dispatcher = dispatcher,
        )
    }

    fun isSupported(
        osName: String = System.getProperty("os.name").orEmpty(),
        sessionType: String = System.getenv("XDG_SESSION_TYPE").orEmpty(),
        waylandDisplay: String = System.getenv("WAYLAND_DISPLAY").orEmpty(),
        x11Display: String = System.getenv("DISPLAY").orEmpty(),
    ): Boolean {
        val isLinux = osName.contains("linux", ignoreCase = true)
        val normalizedSession = sessionType.trim().lowercase()
        val isWayland = normalizedSession == "wayland" || waylandDisplay.isNotBlank()
        val isX11 = normalizedSession == "x11" || x11Display.isNotBlank()
        return isLinux && isX11 && !isWayland
    }
}
