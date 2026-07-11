package io.aequicor.audio.windows.wasapi

import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.platform.AudioSourceRepository
import io.aequicor.capture.platform.AudioSourceRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class WindowsWasapiAudioSourceRepository internal constructor(
    private val clientFactory: WasapiLoopbackClientFactory,
    private val dispatcher: CoroutineDispatcher,
) : AudioSourceRepository {
    override suspend fun listAudioSources(request: AudioSourceRequest): List<AudioSource> {
        if (!request.includeSystemLoopback) {
            return emptyList()
        }
        return withContext(dispatcher) { clientFactory.endpoints() }.map { endpoint ->
            AudioSource.SystemLoopback(
                id = endpoint.id,
                displayName = endpoint.displayName,
                sampleRate = endpoint.sampleRate,
                channelCount = endpoint.channelCount,
            )
        }
    }
}
