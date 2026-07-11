package io.aequicor.audio.linux.pulse

import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.platform.AudioSourceRepository
import io.aequicor.capture.platform.AudioSourceRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal class PulseAudioSourceRepository(
    private val backend: PulseAudioBackend,
    private val dispatcher: CoroutineDispatcher,
) : AudioSourceRepository {
    override suspend fun listAudioSources(request: AudioSourceRequest): List<AudioSource> {
        if (!request.includeSystemLoopback) {
            return emptyList()
        }
        return withContext(dispatcher) { backend.listMonitorDevices() }.map { device ->
            AudioSource.SystemLoopback(
                id = AudioSourceId(PULSE_MONITOR_ID_PREFIX + device.name),
                displayName = device.description,
                sampleRate = device.sampleRate,
                channelCount = device.channelCount,
            )
        }
    }
}

internal const val PULSE_MONITOR_ID_PREFIX = "pulse:monitor:"
