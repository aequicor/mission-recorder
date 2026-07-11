package io.aequicor.audio.desktop.javasound

import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.platform.AudioSourceRepository
import io.aequicor.capture.platform.AudioSourceRequest

class JavaSoundAudioSourceRepository(
    private val deviceProvider: JavaSoundAudioDeviceProvider = SystemJavaSoundAudioDeviceProvider(),
) : AudioSourceRepository {
    override suspend fun listAudioSources(request: AudioSourceRequest): List<AudioSource> =
        buildList {
            if (request.includeMicrophones) {
                deviceProvider.listTargetDevices().forEach { device ->
                    add(
                        AudioSource.Microphone(
                            id = device.id,
                            displayName = device.displayName,
                            sampleRate = device.format.sampleRate,
                            channelCount = device.format.channelCount,
                        ),
                    )
                }
            }
        }
}
