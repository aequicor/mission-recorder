package io.aequicor.capture.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RecordingSettingsValidatorTest {
    @Test
    fun rejectsNonFiniteAudioGain() {
        val settings = RecordingSettings(
            captureSource = CaptureSource.Screen(CaptureSourceId("screen"), "Screen"),
            audioSources = listOf(
                AudioSource.Microphone(
                    id = AudioSourceId("mic"),
                    displayName = "Microphone",
                    sampleRate = 48_000,
                    channelCount = 2,
                    gain = Double.NaN,
                ),
            ),
            outputPath = "capture.mp4",
        )

        assertEquals(
            listOf(ValidationIssue("audioSources[0].gain", "Audio gain must be between 0% and 200%.")),
            RecordingSettingsValidator.validate(settings),
        )
    }
}
