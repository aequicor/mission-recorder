package io.aequicor.audio.core

import io.aequicor.capture.core.AudioCaptureAdapter
import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.AudioSampleFormat
import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.MediaTimestamp
import io.aequicor.capture.core.RecordingSettings
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class MixingAudioCaptureAdapterTest {
    @Test
    fun appliesGainToSingleSourceRoute() = runTest {
        val microphone = AudioSource.Microphone(AudioSourceId("mic"), "Mic", 48_000, 1, gain = 0.5)
        val sourceFrame = pcmFrame(microphone.id, channelCount = 1, samples = ShortArray(480) { 10_000 })
        val adapter = MixingAudioCaptureAdapter(
            routes = listOf(AudioCaptureRoute({ it is AudioSource.Microphone }, SustainedFrameAdapter(sourceFrame))),
        )

        val frame = adapter.frames(settings(listOf(microphone))).first()

        assertContentEquals(ShortArray(480) { 5_000 }.toPcmS16Le(), frame.audioData)
        assertEquals(sourceFrame.timestamp, frame.timestamp)
    }

    @Test
    fun normalizesAndMixesMicrophoneWithSystemAudio() = runTest {
        val microphone = AudioSource.Microphone(AudioSourceId("mic"), "Mic", 44_100, 1, gain = 0.5)
        val system = AudioSource.SystemLoopback(AudioSourceId("system"), "System", 48_000, 2, gain = 0.25)
        val microphoneFrame = pcmFrame(
            sourceId = microphone.id,
            sampleRate = 44_100,
            channelCount = 1,
            samples = ShortArray(441) { 1_000 },
        )
        val systemFrame = pcmFrame(
            sourceId = system.id,
            sampleRate = 48_000,
            channelCount = 2,
            samples = ShortArray(960) { index -> if (index % 2 == 0) 2_000 else -2_000 },
        )
        val adapter = MixingAudioCaptureAdapter(
            routes = listOf(
                AudioCaptureRoute({ it is AudioSource.Microphone }, SustainedFrameAdapter(microphoneFrame)),
                AudioCaptureRoute({ it is AudioSource.SystemLoopback }, SustainedFrameAdapter(systemFrame)),
            ),
        )

        val mixed = adapter.frames(settings(listOf(microphone, system))).first()

        assertEquals(AudioSourceId("audio:mixed"), mixed.sourceId)
        assertEquals(48_000, mixed.sampleRate)
        assertEquals(2, mixed.channelCount)
        assertEquals(480, mixed.sampleCount)
        assertContentEquals(
            ShortArray(960) { index -> if (index % 2 == 0) 1_000 else 0 }.toPcmS16Le(),
            mixed.audioData,
        )
    }
}

private class SustainedFrameAdapter(
    private val frame: AudioFrame,
) : AudioCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<AudioFrame> = flow {
        emit(frame)
        awaitCancellation()
    }
}

private fun settings(audioSources: List<AudioSource>): RecordingSettings = RecordingSettings(
    captureSource = CaptureSource.Screen(CaptureSourceId("screen"), "Screen"),
    audioSources = audioSources,
    outputPath = "unused.mp4",
)

private fun pcmFrame(
    sourceId: AudioSourceId,
    sampleRate: Int = 48_000,
    channelCount: Int,
    samples: ShortArray,
): AudioFrame = AudioFrame(
    timestamp = MediaTimestamp(0),
    sampleRate = sampleRate,
    channelCount = channelCount,
    sampleCount = samples.size / channelCount,
    sourceId = sourceId,
    sampleFormat = AudioSampleFormat.PcmS16Le,
    audioData = samples.toPcmS16Le(),
)

private fun ShortArray.toPcmS16Le(): ByteArray = ByteArray(size * 2).also { output ->
    forEachIndexed { index, sample ->
        output[index * 2] = (sample.toInt() and 0xff).toByte()
        output[index * 2 + 1] = (sample.toInt() shr 8 and 0xff).toByte()
    }
}
