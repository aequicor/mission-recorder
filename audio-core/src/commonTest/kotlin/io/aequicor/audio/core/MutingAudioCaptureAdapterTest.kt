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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MutingAudioCaptureAdapterTest {
    @Test
    fun soloGatesOtherSourcesWithoutChangingTheirMuteState() = runTest {
        val microphoneId = AudioSourceId("mic:test")
        val systemId = AudioSourceId("system:test")
        val input = Channel<AudioFrame>(Channel.UNLIMITED)
        val controller = MutableAudioMuteController()
        val adapter = MutingAudioCaptureAdapter(MuteTestAudioCaptureAdapter(input), controller)
        val output = mutableListOf<AudioFrame>()
        backgroundScope.launch {
            adapter.frames(
                muteSettings(AudioSource.Microphone(microphoneId, "Microphone", 48_000, 2)),
            ).collect(output::add)
        }
        runCurrent()

        controller.setMuted(systemId, true)
        controller.setSolo(microphoneId)
        val systemFrame = muteFrame(systemId, 10, byteArrayOf(1, 2, 3, 4))
        val microphoneFrame = muteFrame(microphoneId, 20, byteArrayOf(5, 6, 7, 8))
        input.send(systemFrame)
        input.send(microphoneFrame)
        runCurrent()

        assertContentEquals(byteArrayOf(0, 0, 0, 0), output.first().audioData)
        assertSame(microphoneFrame, output.last())
        controller.setSolo(null)

        assertEquals(setOf(systemId), controller.mutedSourceIds.value)
        assertEquals(null, controller.soloSourceId.value)
    }

    @Test
    fun replacesOnlyPayloadWhileMutedAndKeepsTimelineContinuous() = runTest {
        val source = AudioSource.Microphone(
            id = AudioSourceId("mic:test"),
            displayName = "Test microphone",
            sampleRate = 48_000,
            channelCount = 2,
        )
        val input = Channel<AudioFrame>(Channel.UNLIMITED)
        val muteController = MutableAudioMuteController()
        val adapter = MutingAudioCaptureAdapter(
            delegate = MuteTestAudioCaptureAdapter(input),
            muteController = muteController,
        )
        val output = mutableListOf<AudioFrame>()
        backgroundScope.launch {
            adapter.frames(muteSettings(source)).collect(output::add)
        }
        runCurrent()

        val audible = muteFrame(source.id, timestampNanoseconds = 10, payload = byteArrayOf(1, 2, 3, 4))
        input.send(audible)
        runCurrent()

        assertSame(audible, output.single())

        muteController.setMuted(source.id, true)
        val mutedInput = muteFrame(source.id, timestampNanoseconds = 20, payload = byteArrayOf(5, 6, 7, 8))
        input.send(mutedInput)
        runCurrent()

        val mutedOutput = output.last()
        assertEquals(mutedInput.timestamp, mutedOutput.timestamp)
        assertEquals(mutedInput.sampleCount, mutedOutput.sampleCount)
        assertEquals(mutedInput.sampleFormat, mutedOutput.sampleFormat)
        assertEquals(mutedInput.sourceId, mutedOutput.sourceId)
        assertContentEquals(byteArrayOf(0, 0, 0, 0), mutedOutput.audioData)
        assertContentEquals(byteArrayOf(5, 6, 7, 8), mutedInput.audioData)

        muteController.setMuted(source.id, false)
        val unmuted = muteFrame(source.id, timestampNanoseconds = 30, payload = byteArrayOf(9, 10, 11, 12))
        input.send(unmuted)
        runCurrent()

        assertSame(unmuted, output.last())
        assertEquals(listOf(10L, 20L, 30L), output.map { it.timestamp.nanoseconds })
        assertTrue(muteController.mutedSourceIds.value.isEmpty())
    }
}

private class MuteTestAudioCaptureAdapter(
    private val input: Channel<AudioFrame>,
) : AudioCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<AudioFrame> = input.receiveAsFlow()
}

private fun muteSettings(source: AudioSource): RecordingSettings = RecordingSettings(
    captureSource = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen"),
    audioSources = listOf(source),
    outputPath = "recording.mp4",
)

private fun muteFrame(
    sourceId: AudioSourceId,
    timestampNanoseconds: Long,
    payload: ByteArray,
): AudioFrame = AudioFrame(
    timestamp = MediaTimestamp(timestampNanoseconds),
    sampleRate = 48_000,
    channelCount = 2,
    sampleCount = 1,
    sourceId = sourceId,
    sampleFormat = AudioSampleFormat.PcmS16Le,
    audioData = payload,
)
