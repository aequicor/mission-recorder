package io.aequicor.capture.windows.jna

import io.aequicor.capture.core.VideoCaptureAdapter
import io.aequicor.capture.platform.CaptureSourceRepository
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

class WindowsCaptureAdapters internal constructor(
    val sourceRepository: CaptureSourceRepository,
    val videoCaptureAdapter: VideoCaptureAdapter,
    private val dispatcher: ExecutorCoroutineDispatcher,
) : AutoCloseable {
    override fun close() {
        dispatcher.close()
    }
}

object WindowsCaptureAdapterFactory {
    fun createIfSupported(): WindowsCaptureAdapters? {
        if (!isSupported()) {
            return null
        }
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mission-recorder-windows-capture").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val windowSystem = runCatching(::JnaWindowsWindowSystem).getOrElse { failure ->
            dispatcher.close()
            throw failure
        }
        return WindowsCaptureAdapters(
            sourceRepository = WindowsCaptureSourceRepository(windowSystem),
            videoCaptureAdapter = WindowsVideoCaptureAdapter(windowSystem, dispatcher),
            dispatcher = dispatcher,
        )
    }

    fun isSupported(osName: String = System.getProperty("os.name")): Boolean =
        osName.startsWith("Windows", ignoreCase = true)
}
