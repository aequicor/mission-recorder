package io.aequicor.audio.linux.pulse

import io.aequicor.capture.core.AudioCaptureAdapter
import io.aequicor.capture.platform.AudioSourceRepository
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

class LinuxPulseAudioAdapters internal constructor(
    val sourceRepository: AudioSourceRepository,
    val captureAdapter: AudioCaptureAdapter,
    private val dispatcher: ExecutorCoroutineDispatcher,
) : AutoCloseable {
    override fun close() {
        dispatcher.close()
    }
}

object LinuxPulseAudioAdapterFactory {
    fun createIfSupported(): LinuxPulseAudioAdapters? {
        val commands = resolveCommands() ?: return null
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mission-recorder-pulse-audio").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val backend = CommandPulseAudioBackend(commands.pactl, commands.parec)
        if (runCatching(backend::listMonitorDevices).isFailure) {
            dispatcher.close()
            return null
        }
        return LinuxPulseAudioAdapters(
            sourceRepository = PulseAudioSourceRepository(backend, dispatcher),
            captureAdapter = PulseAudioCaptureAdapter(backend, dispatcher),
            dispatcher = dispatcher,
        )
    }

    fun isSupported(
        osName: String = System.getProperty("os.name").orEmpty(),
        path: String = System.getenv("PATH").orEmpty(),
    ): Boolean = resolveCommands(osName, path) != null

    private fun resolveCommands(
        osName: String = System.getProperty("os.name").orEmpty(),
        path: String = System.getenv("PATH").orEmpty(),
    ): PulseCommands? {
        if (!osName.contains("linux", ignoreCase = true)) {
            return null
        }
        val pactl = findExecutable("pactl", path) ?: return null
        val parec = findExecutable("parec", path) ?: return null
        return PulseCommands(pactl.toString(), parec.toString())
    }
}

private data class PulseCommands(val pactl: String, val parec: String)

private fun findExecutable(command: String, path: String): Path? =
    path.split(System.getProperty("path.separator"))
        .asSequence()
        .filter(String::isNotBlank)
        .map { directory -> Path.of(directory, command) }
        .firstOrNull { candidate -> Files.isRegularFile(candidate) && Files.isExecutable(candidate) }
