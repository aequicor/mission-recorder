package io.aequicor.audio.windows.wasapi

import io.aequicor.capture.core.AudioCaptureAdapter
import io.aequicor.capture.platform.AudioSourceRepository
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

class WindowsWasapiAudioAdapters internal constructor(
    val sourceRepository: AudioSourceRepository,
    val captureAdapter: AudioCaptureAdapter,
    private val dispatcher: ExecutorCoroutineDispatcher,
) : AutoCloseable {
    override fun close() {
        dispatcher.close()
    }
}

object WindowsWasapiAudioAdapterFactory {
    fun createIfSupported(): WindowsWasapiAudioAdapters? {
        if (!isSupported()) {
            return null
        }
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mission-recorder-wasapi").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val clientFactory = JnaWasapiLoopbackClientFactory()
        return WindowsWasapiAudioAdapters(
            sourceRepository = WindowsWasapiAudioSourceRepository(clientFactory, dispatcher),
            captureAdapter = WindowsWasapiAudioCaptureAdapter(clientFactory, dispatcher),
            dispatcher = dispatcher,
        )
    }

    fun isSupported(osName: String = System.getProperty("os.name")): Boolean =
        osName.startsWith("Windows", ignoreCase = true)
}
