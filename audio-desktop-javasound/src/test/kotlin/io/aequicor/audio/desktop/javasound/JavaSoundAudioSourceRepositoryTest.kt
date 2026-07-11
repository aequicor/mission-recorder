package io.aequicor.audio.desktop.javasound

import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.platform.AudioSourceRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JavaSoundAudioSourceRepositoryTest {
    @Test
    fun listsMicrophonesFromProvider() = runTest {
        val sourceId = AudioSourceId("javasound:target:test")
        val repository = JavaSoundAudioSourceRepository(
            deviceProvider = FakeJavaSoundAudioDeviceProvider(
                devices = listOf(
                    JavaSoundAudioDeviceDescriptor(
                        id = sourceId,
                        displayName = "USB Microphone",
                        format = JavaSoundPcmFormat(sampleRate = 48_000, channelCount = 2),
                    ),
                ),
            ),
        )

        val sources = repository.listAudioSources()

        val microphone = assertIs<AudioSource.Microphone>(sources.single())
        assertEquals(sourceId, microphone.id)
        assertEquals("USB Microphone", microphone.displayName)
        assertEquals(48_000, microphone.sampleRate)
        assertEquals(2, microphone.channelCount)
    }

    @Test
    fun honorsMicrophoneFilter() = runTest {
        val repository = JavaSoundAudioSourceRepository(
            deviceProvider = FakeJavaSoundAudioDeviceProvider(
                devices = listOf(
                    JavaSoundAudioDeviceDescriptor(
                        id = AudioSourceId("javasound:target:test"),
                        displayName = "USB Microphone",
                        format = JavaSoundPcmFormat(sampleRate = 44_100, channelCount = 1),
                    ),
                ),
            ),
        )

        val sources = repository.listAudioSources(
            AudioSourceRequest(includeMicrophones = false, includeSystemLoopback = true),
        )

        assertTrue(sources.isEmpty())
    }
}

internal class FakeJavaSoundAudioDeviceProvider(
    private val devices: List<JavaSoundAudioDeviceDescriptor> = emptyList(),
    private val targetLines: Map<AudioSourceId, JavaSoundTargetLine> = emptyMap(),
) : JavaSoundAudioDeviceProvider {
    override fun listTargetDevices(): List<JavaSoundAudioDeviceDescriptor> = devices

    override fun openTargetLine(
        sourceId: AudioSourceId,
        format: JavaSoundPcmFormat,
        bufferSizeBytes: Int,
    ): JavaSoundTargetLine =
        targetLines[sourceId] ?: error("Unexpected source id: ${sourceId.value}")
}
