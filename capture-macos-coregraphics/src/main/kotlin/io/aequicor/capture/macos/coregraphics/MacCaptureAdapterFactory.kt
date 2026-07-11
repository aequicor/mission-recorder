package io.aequicor.capture.macos.coregraphics

import io.aequicor.capture.core.VideoCaptureAdapter
import io.aequicor.capture.platform.CaptureSourceRepository
import io.aequicor.capture.platform.PermissionGateway
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

class MacCaptureAdapters internal constructor(
    val sourceRepository: CaptureSourceRepository,
    val videoCaptureAdapter: VideoCaptureAdapter,
    val permissionGateway: PermissionGateway,
    private val dispatcher: ExecutorCoroutineDispatcher,
) : AutoCloseable {
    override fun close() = dispatcher.close()
}

object MacCaptureAdapterFactory {
    fun createIfSupported(): MacCaptureAdapters? {
        if (!isSupported()) return null
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mission-recorder-coregraphics").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val windowSystem = runCatching { JnaMacWindowSystem().also(JnaMacWindowSystem::probe) }.getOrElse {
            dispatcher.close()
            return null
        }
        return MacCaptureAdapters(
            sourceRepository = MacCaptureSourceRepository(windowSystem, dispatcher),
            videoCaptureAdapter = MacVideoCaptureAdapter(windowSystem, dispatcher),
            permissionGateway = MacOsPermissionGateway(JnaMacScreenPermissionApi, JnaMacMicrophonePermissionApi),
            dispatcher = dispatcher,
        )
    }

    fun isSupported(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
        osName.contains("mac", ignoreCase = true) || osName.contains("darwin", ignoreCase = true)
}
