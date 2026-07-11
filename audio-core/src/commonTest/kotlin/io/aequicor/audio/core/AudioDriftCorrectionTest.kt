package io.aequicor.audio.core

import io.aequicor.capture.core.AudioCaptureAdapter
import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.AudioSampleFormat
import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.MediaClock
import io.aequicor.capture.core.MediaTimestamp
import io.aequicor.capture.core.RecordingSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AudioDriftCorrectionTest {
    @Test
    fun insertsBoundedSilenceWhenAudioClockFallsBehindReferenceClock() = runTest {
        val fixture = driftFixture(secondWindowTransportOffsetMilliseconds = 80)

        val output = fixture.adapter.frames(fixture.settings).toList()

        assertTrue(output.any { frame -> frame.sampleCount == 105 })
        assertContinuous(output)
        val stats = fixture.monitor.stats.value.getValue(TEST_AUDIO_SOURCE.id)
        assertEquals(5, stats.insertedSamples)
        assertEquals(0, stats.droppedSamples)
        assertEquals(25_000_000, stats.estimatedDriftNanoseconds)
    }

    @Test
    fun dropsBoundedPrefixWhenAudioClockRunsAheadOfReferenceClock() = runTest {
        val fixture = driftFixture(secondWindowTransportOffsetMilliseconds = 20)

        val output = fixture.adapter.frames(fixture.settings).toList()

        assertTrue(output.any { frame -> frame.sampleCount == 95 })
        assertContinuous(output)
        val stats = fixture.monitor.stats.value.getValue(TEST_AUDIO_SOURCE.id)
        assertEquals(0, stats.insertedSamples)
        assertEquals(5, stats.droppedSamples)
        assertEquals(-25_000_000, stats.estimatedDriftNanoseconds)
    }

    @Test
    fun ignoresTransportJitterInsideTolerance() = runTest {
        val fixture = driftFixture(secondWindowTransportOffsetMilliseconds = 58)

        val output = fixture.adapter.frames(fixture.settings).toList()

        assertTrue(output.all { frame -> frame.sampleCount == 100 })
        val stats = fixture.monitor.stats.value.getValue(TEST_AUDIO_SOURCE.id)
        assertEquals(0, stats.insertedSamples)
        assertEquals(0, stats.droppedSamples)
        assertEquals(8_000_000, stats.estimatedDriftNanoseconds)
    }
}

private data class DriftFixture(
    val adapter: DriftCorrectingAudioCaptureAdapter,
    val monitor: AudioDriftCorrectionMonitor,
    val settings: RecordingSettings,
)

private fun driftFixture(secondWindowTransportOffsetMilliseconds: Long): DriftFixture {
    val clock = MutableAudioReferenceClock()
    val observations = buildList {
        repeat(11) { index ->
            add(observation(index, transportOffsetMilliseconds = 50))
        }
        repeat(11) { offset ->
            add(observation(index = offset + 11, transportOffsetMilliseconds = secondWindowTransportOffsetMilliseconds))
        }
    }
    val monitor = AudioDriftCorrectionMonitor()
    val delegate = ScriptedAudioCaptureAdapter(clock, observations)
    return DriftFixture(
        adapter = DriftCorrectingAudioCaptureAdapter(
            delegate = delegate,
            clock = clock,
            policy = AudioDriftCorrectionPolicy(
                measurementWindow = 1.seconds,
                tolerance = 10.milliseconds,
                maxCorrectionPerWindow = 5.milliseconds,
            ),
            monitor = monitor,
        ),
        monitor = monitor,
        settings = RecordingSettings(
            captureSource = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen"),
            audioSources = listOf(TEST_AUDIO_SOURCE),
            outputPath = "capture.mp4",
        ),
    )
}

private fun observation(index: Int, transportOffsetMilliseconds: Long): AudioObservation {
    val timestampNanoseconds = index * FRAME_DURATION_NANOSECONDS
    val frameEndNanoseconds = timestampNanoseconds + FRAME_DURATION_NANOSECONDS
    return AudioObservation(
        observedAtNanoseconds = frameEndNanoseconds + transportOffsetMilliseconds * 1_000_000,
        frame = AudioFrame(
            timestamp = MediaTimestamp(timestampNanoseconds),
            sampleRate = TEST_SAMPLE_RATE,
            channelCount = 1,
            sampleCount = TEST_FRAME_SAMPLES,
            sourceId = TEST_AUDIO_SOURCE.id,
            sampleFormat = AudioSampleFormat.PcmS16Le,
            audioData = ByteArray(TEST_FRAME_SAMPLES * Short.SIZE_BYTES) { 1 },
        ),
    )
}

private class ScriptedAudioCaptureAdapter(
    private val clock: MutableAudioReferenceClock,
    private val observations: List<AudioObservation>,
) : AudioCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<AudioFrame> = flow {
        observations.forEach { observation ->
            clock.nowNanoseconds = observation.observedAtNanoseconds
            emit(observation.frame)
        }
    }
}

private data class AudioObservation(
    val observedAtNanoseconds: Long,
    val frame: AudioFrame,
)

private class MutableAudioReferenceClock : MediaClock {
    var nowNanoseconds: Long = 0

    override fun nowNanoseconds(): Long = nowNanoseconds
}

private fun assertContinuous(frames: List<AudioFrame>) {
    frames.zipWithNext().forEach { (current, next) ->
        val expected = current.timestamp.nanoseconds +
            current.sampleCount.toLong() * 1_000_000_000 / current.sampleRate
        assertEquals(expected, next.timestamp.nanoseconds)
    }
}

private val TEST_AUDIO_SOURCE = AudioSource.Microphone(
    id = AudioSourceId("mic:test"),
    displayName = "Test microphone",
    sampleRate = TEST_SAMPLE_RATE,
    channelCount = 1,
)
private const val TEST_SAMPLE_RATE = 1_000
private const val TEST_FRAME_SAMPLES = 100
private const val FRAME_DURATION_NANOSECONDS = 100_000_000L
