package io.aequicor.encoder

import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.AudioSampleFormat
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.MediaTimestamp
import io.aequicor.capture.core.PixelFormat
import io.aequicor.capture.core.RecordingMetrics
import io.aequicor.capture.core.RecordingSession
import io.aequicor.capture.core.RecordingSessionId
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.VideoFrame
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FrameSequenceMediaEncoderTest {
    @Test
    fun replacesExistingArtifactOnFinishButPreservesItOnCancel() = runTest {
        val temp = Files.createTempDirectory("mission-recorder-encoder-overwrite-test")
        val output = temp.resolve("recording.mrec").also { it.createDirectories() }
        val marker = output.resolve("old.txt")
        Files.writeString(marker, "old")
        val settings = RecordingSettings(
            captureSource = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen"),
            outputPath = output.toString(),
            overwriteOutput = true,
        )
        val cancelSession = RecordingSession(RecordingSessionId("cancel-test"), settings, 0)
        val cancelled = FrameSequenceMediaEncoder()
        cancelled.open(cancelSession, settings)
        cancelled.cancel(cancelSession)
        assertTrue(marker.exists())

        val finishSession = RecordingSession(RecordingSessionId("finish-test"), settings, 0)
        val finished = FrameSequenceMediaEncoder()
        finished.open(finishSession, settings)
        finished.finish(finishSession, RecordingMetrics())

        assertTrue(output.resolve("recording.json").exists())
        assertTrue(!marker.exists())

        assertFailsWith<io.aequicor.capture.core.RecordingException> {
            FrameSequenceMediaEncoder().open(finishSession, settings.copy(overwriteOutput = false))
        }
    }

    @Test
    fun writesFrameSequenceArtifact() = runTest {
        val temp = Files.createTempDirectory("mission-recorder-encoder-test")
        val output = temp.resolve("recording.mrec")
        val settings = RecordingSettings(
            captureSource = CaptureSource.Screen(
                id = CaptureSourceId("screen:test"),
                displayName = "Test screen",
            ),
            outputPath = output.toString(),
        )
        val session = RecordingSession(
            id = RecordingSessionId("session-test"),
            settings = settings,
            startedAtNanoseconds = 0,
        )
        val encoder = FrameSequenceMediaEncoder()

        encoder.open(session, settings)
        encoder.writeVideoFrame(
            VideoFrame(
                timestamp = MediaTimestamp(0),
                width = 1,
                height = 1,
                pixelFormat = PixelFormat.Rgba8888,
                strideBytes = 4,
                sourceId = CaptureSourceId("screen:test"),
                pixelData = byteArrayOf(255.toByte(), 0, 0, 255.toByte()),
            ),
        )
        val audioPayload = byteArrayOf(0, 0, 1, 0, 2, 0, 3, 0)
        encoder.writeAudioFrame(
            AudioFrame(
                timestamp = MediaTimestamp(1_000_000),
                sampleRate = 48_000,
                channelCount = 2,
                sampleCount = 2,
                sourceId = AudioSourceId("mic:test"),
                sampleFormat = AudioSampleFormat.PcmS16Le,
                audioData = audioPayload,
            ),
        )
        val result = encoder.finish(session, RecordingMetrics(videoFrames = 1, audioFrames = 1))

        assertEquals(output.toString(), result.path)
        assertTrue(output.exists())
        assertTrue(output.resolve("frames").resolve("frame-000001.png").exists())
        assertContentEquals(audioPayload, Files.readAllBytes(output.resolve("audio").resolve("audio-000001.pcm")))
        assertTrue(output.resolve("recording.json").exists())
        val manifest = Files.readString(output.resolve("recording.json"))
        assertTrue(manifest.contains(""""videoFrames": 1"""))
        assertTrue(manifest.contains(""""audioFrames": 1"""))
        assertTrue(manifest.contains(""""file": "audio/audio-000001.pcm"""))
        assertTrue(manifest.contains(""""sampleFormat": "PcmS16Le"""))
    }
}
