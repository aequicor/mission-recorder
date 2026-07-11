package io.aequicor.capture.platform

import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.AudioSourceId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class CompositeAudioSourceRepositoryTest {
    @Test
    fun combinesMicrophoneAndLoopbackSources() = runBlocking {
        val microphone = AudioSource.Microphone(AudioSourceId("mic"), "Microphone", 48_000, 1)
        val loopback = AudioSource.SystemLoopback(AudioSourceId("system"), "System", 48_000, 2)
        val repository = CompositeAudioSourceRepository(
            listOf(
                StaticAudioRepository(listOf(microphone)),
                StaticAudioRepository(listOf(loopback)),
            ),
        )

        assertEquals(listOf(microphone, loopback), repository.listAudioSources())
    }
}

private class StaticAudioRepository(
    private val sources: List<AudioSource>,
) : AudioSourceRepository {
    override suspend fun listAudioSources(request: AudioSourceRequest): List<AudioSource> = sources
}
