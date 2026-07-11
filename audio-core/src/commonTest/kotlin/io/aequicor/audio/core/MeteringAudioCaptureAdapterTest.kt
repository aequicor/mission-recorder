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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class MeteringAudioCaptureAdapterTest {
    @Test
    fun publishesThrottledLevelsAndClearsThemWhenCaptureEnds() = runTest {
        val source = AudioSource.Microphone(
            id = AudioSourceId("mic:test"),
            displayName = "Test microphone",
            sampleRate = 48_000,
            channelCount = 1,
        )
        val input = Channel<AudioFrame>(Channel.UNLIMITED)
        val monitor = AudioLevelMonitor()
        val adapter = MeteringAudioCaptureAdapter(
            delegate = ChannelAudioCaptureAdapter(input),
            monitor = monitor,
            publishInterval = 50.milliseconds,
        )
        val captured = mutableListOf<AudioFrame>()
        val job = backgroundScope.launch {
            adapter.frames(settings(source)).collect(captured::add)
        }
        runCurrent()

        val loud = frame(source.id, timestampMilliseconds = 0, sample = Short.MAX_VALUE)
        input.send(loud)
        runCurrent()

        assertSame(loud, captured.single())
        assertEquals(1.0, monitor.levels.value.getValue(source.id).peak, absoluteTolerance = 0.0001)

        input.send(frame(source.id, timestampMilliseconds = 10, sample = 0))
        runCurrent()

        assertEquals(1.0, monitor.levels.value.getValue(source.id).peak, absoluteTolerance = 0.0001)

        input.send(frame(source.id, timestampMilliseconds = 50, sample = 0))
        runCurrent()

        assertEquals(AudioLevels.Silence, monitor.levels.value.getValue(source.id))

        job.cancelAndJoin()

        assertTrue(monitor.levels.value.isEmpty())
    }
}

private class ChannelAudioCaptureAdapter(
    private val input: Channel<AudioFrame>,
) : AudioCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<AudioFrame> = input.receiveAsFlow()
}

private fun settings(source: AudioSource): RecordingSettings = RecordingSettings(
    captureSource = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen"),
    audioSources = listOf(source),
    outputPath = "recording.mp4",
)

private fun frame(
    sourceId: AudioSourceId,
    timestampMilliseconds: Long,
    sample: Short,
): AudioFrame = AudioFrame(
    timestamp = MediaTimestamp(timestampMilliseconds * 1_000_000),
    sampleRate = 48_000,
    channelCount = 1,
    sampleCount = 1,
    sourceId = sourceId,
    sampleFormat = AudioSampleFormat.PcmS16Le,
    audioData = byteArrayOf(sample.toByte(), (sample.toInt() shr 8).toByte()),
)
