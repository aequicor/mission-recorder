package io.aequicor.audio.desktop.javasound

import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.RecordingException
import io.aequicor.capture.core.RecordingSettings
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class JavaSoundAudioCaptureAdapterTest {
    @Test
    fun emitsPcmFramesFromMicrophoneLine() = runTest {
        val sourceId = AudioSourceId("javasound:target:test")
        val payload = byteArrayOf(
            0x00,
            0x00,
            0x01,
            0x00,
            0x02,
            0x00,
            0x03,
            0x00,
        )
        val line = FakeTargetLine(
            format = JavaSoundPcmFormat(sampleRate = 48_000, channelCount = 2),
            payload = payload,
        )
        val adapter = JavaSoundAudioCaptureAdapter(
            deviceProvider = FakeJavaSoundAudioDeviceProvider(targetLines = mapOf(sourceId to line)),
            timestampClock = SequenceAudioTimestampClock(100L, 123L),
            pollInterval = 1.milliseconds,
        )

        val frame = adapter.frames(recordingSettingsWith(AudioSource.Microphone(sourceId, "Mic", 48_000, 2)))
            .take(1)
            .single()

        assertEquals(sourceId, frame.sourceId)
        assertEquals(48_000, frame.sampleRate)
        assertEquals(2, frame.channelCount)
        assertEquals(2, frame.sampleCount)
        assertEquals(23L, frame.timestamp.nanoseconds)
        assertContentEquals(payload, frame.audioData)
        assertTrue(line.started)
        assertTrue(line.stopped)
        assertTrue(line.closed)
    }

    @Test
    fun returnsEmptyFlowWhenNoAudioSourcesAreEnabled() = runTest {
        val adapter = JavaSoundAudioCaptureAdapter(
            deviceProvider = FakeJavaSoundAudioDeviceProvider(),
            timestampClock = AudioTimestampClock { 0L },
        )

        val frames = adapter.frames(recordingSettingsWith()).toList()

        assertTrue(frames.isEmpty())
    }

    @Test
    fun rejectsSystemLoopbackBecauseJavaSoundDoesNotProvideItReliably() = runTest {
        val adapter = JavaSoundAudioCaptureAdapter(
            deviceProvider = FakeJavaSoundAudioDeviceProvider(),
            timestampClock = AudioTimestampClock { 0L },
        )
        val source = AudioSource.SystemLoopback(
            id = AudioSourceId("system:default"),
            displayName = "System audio",
            sampleRate = 48_000,
            channelCount = 2,
        )

        val exception = assertFailsWith<RecordingException> {
            adapter.frames(recordingSettingsWith(source)).toList()
        }

        assertTrue(exception.error.message.contains("system loopback"))
    }
}

private class SequenceAudioTimestampClock(vararg timestamps: Long) : AudioTimestampClock {
    private val values = timestamps.iterator()

    override fun nowNanoseconds(): Long = values.next()
}

private class FakeTargetLine(
    override val format: JavaSoundPcmFormat,
    private val payload: ByteArray,
) : JavaSoundTargetLine {
    var started: Boolean = false
        private set
    var stopped: Boolean = false
        private set
    var closed: Boolean = false
        private set
    private var readOffset: Int = 0

    override fun start() {
        started = true
    }

    override fun stop() {
        stopped = true
    }

    override fun available(): Int = payload.size - readOffset

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val byteCount = min(length, payload.size - readOffset)
        payload.copyInto(buffer, destinationOffset = offset, startIndex = readOffset, endIndex = readOffset + byteCount)
        readOffset += byteCount
        return byteCount
    }

    override fun close() {
        closed = true
    }
}

private fun recordingSettingsWith(vararg audioSources: AudioSource): RecordingSettings =
    RecordingSettings(
        captureSource = CaptureSource.Screen(
            id = CaptureSourceId("screen:all"),
            displayName = "All screens",
        ),
        audioSources = audioSources.toList(),
        outputPath = "out.mrec",
        captureCursor = false,
    )
