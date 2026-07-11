package io.aequicor.app

import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.CaptureRegion
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.platform.AudioSourceRepository
import io.aequicor.capture.platform.AudioSourceRequest
import io.aequicor.capture.platform.CaptureSourceRepository
import io.aequicor.cli.RecordTarget

internal class DesktopCaptureSelectionResolver(
    private val captureSourceRepository: CaptureSourceRepository,
    private val audioSourceRepository: AudioSourceRepository,
) {
    suspend fun resolveSource(target: RecordTarget, profileSource: CaptureSource? = null): CaptureSource? =
        when (target) {
            RecordTarget.Screen -> captureSourceRepository.listSources()
                .filterIsInstance<CaptureSource.Screen>()
                .firstOrNull()
            RecordTarget.Profile -> profileSource
            is RecordTarget.Monitor -> captureSourceRepository.listSources()
                .filterIsInstance<CaptureSource.Monitor>()
                .firstOrNull { it.id.value == target.id || it.index.toString() == target.id }
            is RecordTarget.Region -> CaptureSource.Region(
                id = CaptureSourceId("region:${target.x},${target.y},${target.width}x${target.height}"),
                displayName = "Region ${target.width}x${target.height} at ${target.x},${target.y}",
                region = CaptureRegion(
                    x = target.x,
                    y = target.y,
                    width = target.width,
                    height = target.height,
                ),
            )
            is RecordTarget.Window -> captureSourceRepository.listSources()
                .filterIsInstance<CaptureSource.Window>()
                .firstOrNull { it.id.value == target.id }
            is RecordTarget.Application -> captureSourceRepository.listSources()
                .filterIsInstance<CaptureSource.Application>()
                .firstOrNull { source ->
                    source.id.value == target.id || source.id.value.substringAfterLast(':') == target.id
                }
        }

    suspend fun resolveAudioSources(
        microphoneSelector: String?,
        includeSystemAudio: Boolean,
        systemAudioSelector: String? = null,
        microphoneGain: Double? = null,
        systemAudioGain: Double? = null,
        profileSources: List<AudioSource> = emptyList(),
    ): DesktopAudioSourceResolution {
        val profileMicrophones = profileSources.filterIsInstance<AudioSource.Microphone>()
        if (microphoneGain != null && microphoneSelector == null && profileMicrophones.isEmpty()) {
            return DesktopAudioSourceResolution.Rejected(
                "Microphone gain was provided, but the selected profile has no microphone. " +
                    "Pass --mic or enable a microphone in the profile.",
            )
        }
        val resolvedMicrophones = if (microphoneSelector != null || profileMicrophones.isNotEmpty()) {
            val availableMicrophones = audioSourceRepository.listAudioSources(
                AudioSourceRequest(includeMicrophones = true, includeSystemLoopback = false),
            ).filterIsInstance<AudioSource.Microphone>()
            if (availableMicrophones.isEmpty()) {
                return DesktopAudioSourceResolution.Rejected(
                    "No microphones are available. Run list-audio to inspect desktop audio inputs.",
                )
            }
            if (microphoneSelector != null) {
                val microphone = if (microphoneSelector.equals("default", ignoreCase = true)) {
                    availableMicrophones.first()
                } else {
                    availableMicrophones.findMatching(microphoneSelector)
                        ?: return DesktopAudioSourceResolution.Rejected(
                            "Microphone is not available: $microphoneSelector. " +
                                "Run list-audio and pass its id or display name to --mic.",
                        )
                }
                listOf(
                    microphone.copy(gain = microphoneGain ?: profileMicrophones.firstOrNull()?.gain ?: 1.0),
                )
            } else {
                profileMicrophones.map { configured ->
                    (availableMicrophones.findMatching(configured.id.value)
                        ?: availableMicrophones.findMatching(configured.displayName)
                        ?: return DesktopAudioSourceResolution.Rejected(
                            "Profile microphone is not available: ${configured.id.value}. " +
                                "Run list-audio to inspect inputs.",
                        )).copy(gain = microphoneGain ?: configured.gain)
                }
            }
        } else {
            emptyList()
        }

        val profileLoopback = profileSources.filterIsInstance<AudioSource.SystemLoopback>().firstOrNull()
        val resolvedLoopback = if (includeSystemAudio || systemAudioSelector != null || profileLoopback != null) {
            val availableLoopbacks = audioSourceRepository.listAudioSources(
                AudioSourceRequest(includeMicrophones = false, includeSystemLoopback = true),
            ).filterIsInstance<AudioSource.SystemLoopback>()
            if (availableLoopbacks.isEmpty()) {
                return DesktopAudioSourceResolution.Rejected(
                    "System audio is not available. Run list-audio to inspect loopback inputs.",
                )
            }
            if (systemAudioSelector != null) {
                (availableLoopbacks.findMatching(systemAudioSelector)
                    ?: return DesktopAudioSourceResolution.Rejected(
                        "System audio endpoint is not available: $systemAudioSelector. " +
                            "Run list-audio and pass its id or display name to --system-audio-endpoint.",
                    )).copy(gain = systemAudioGain ?: profileLoopback?.gain ?: 1.0)
            } else if (profileLoopback != null) {
                (availableLoopbacks.firstOrNull { source ->
                    source.id.value.equals(profileLoopback.id.value, ignoreCase = true) ||
                        source.displayName.equals(profileLoopback.displayName, ignoreCase = true)
                }
                    ?: return DesktopAudioSourceResolution.Rejected(
                        "Profile system audio source is not available: ${profileLoopback.id.value}. " +
                            "Run list-audio to inspect loopback inputs.",
                    )).copy(gain = systemAudioGain ?: profileLoopback.gain)
            } else {
                availableLoopbacks.first().copy(gain = systemAudioGain ?: 1.0)
            }
        } else {
            null
        }

        return DesktopAudioSourceResolution.Resolved(
            (resolvedMicrophones + listOfNotNull(resolvedLoopback)).distinctBy { source -> source.id },
        )
    }
}

internal sealed interface DesktopAudioSourceResolution {
    data class Resolved(val sources: List<AudioSource>) : DesktopAudioSourceResolution
    data class Rejected(val message: String) : DesktopAudioSourceResolution
}

private fun List<AudioSource.Microphone>.findMatching(selector: String): AudioSource.Microphone? =
    firstOrNull { microphone ->
        microphone.id.value.equals(selector, ignoreCase = true) ||
            microphone.displayName.equals(selector, ignoreCase = true)
    }

private fun List<AudioSource.SystemLoopback>.findMatching(selector: String): AudioSource.SystemLoopback? =
    firstOrNull { source ->
        source.id.value.equals(selector, ignoreCase = true) ||
            source.displayName.equals(selector, ignoreCase = true)
    }

internal fun Int.toAudioGain(): Double = toDouble() / 100.0
