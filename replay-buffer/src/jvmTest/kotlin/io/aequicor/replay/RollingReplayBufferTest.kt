package io.aequicor.replay

import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.MediaTimestamp
import io.aequicor.capture.core.PixelFormat
import io.aequicor.capture.core.RecordingSessionId
import io.aequicor.capture.core.VideoFrame
import io.aequicor.encoder.FrameSequenceMediaEncoder
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RollingReplayBufferTest {
    @Test
    fun keepsOnlyFramesInsideDuration() {
        val buffer = RollingReplayBuffer(ReplayBufferSettings(duration = 2.seconds))

        buffer.append(videoFrame(seconds = 0))
        buffer.append(videoFrame(seconds = 1))
        buffer.append(videoFrame(seconds = 2))
        buffer.append(videoFrame(seconds = 3))

        val snapshot = buffer.snapshot()

        assertEquals(listOf(1L, 2L, 3L), snapshot.videoFrames.map { it.timestamp.nanoseconds / 1_000_000_000L })
        assertEquals(2.seconds, buffer.stats().retainedDuration)
    }

    @Test
    fun appliesMaxFrameLimitAfterDurationTrim() {
        val buffer = RollingReplayBuffer(
            ReplayBufferSettings(duration = 10.seconds, maxVideoFrames = 2),
        )

        buffer.append(videoFrame(seconds = 0))
        buffer.append(videoFrame(seconds = 1))
        buffer.append(videoFrame(seconds = 2))

        assertEquals(listOf(1L, 2L), buffer.snapshot().videoFrames.map { it.timestamp.nanoseconds / 1_000_000_000L })
    }

    @Test
    fun retainsIndependentCopyOfPixelPayload() {
        val buffer = RollingReplayBuffer(ReplayBufferSettings(duration = 5.seconds))
        val pixels = byteArrayOf(1, 2, 3, 4)

        buffer.append(videoFrame(seconds = 0, pixels = pixels))
        pixels[0] = 99

        val retainedPixels = buffer.snapshot().videoFrames.single().pixelData
        assertEquals(1, retainedPixels?.first())
    }

    @Test
    fun retainsIndependentCopyOfAudioPayload() {
        val buffer = RollingReplayBuffer(ReplayBufferSettings(duration = 5.seconds))
        val samples = byteArrayOf(1, 2)

        buffer.append(audioFrame(seconds = 0, audioData = samples))
        samples[0] = 99

        val retainedSamples = buffer.snapshot().audioFrames.single().audioData
        assertEquals(1, retainedSamples?.first())
    }

    @Test
    fun storesAudioFramesInSameRollingWindow() {
        val buffer = RollingReplayBuffer(ReplayBufferSettings(duration = 1.seconds))

        buffer.append(audioFrame(seconds = 0))
        buffer.append(audioFrame(seconds = 1))
        buffer.append(audioFrame(seconds = 2))

        val snapshot = buffer.snapshot()

        assertEquals(listOf(1L, 2L), snapshot.audioFrames.map { it.timestamp.nanoseconds / 1_000_000_000L })
        assertEquals(2, buffer.stats().audioFrameCount)
    }

    @Test
    fun videoAppendTrimsStaleAudioFrames() {
        val buffer = RollingReplayBuffer(ReplayBufferSettings(duration = 1.seconds))

        buffer.append(audioFrame(seconds = 0))
        buffer.append(videoFrame(seconds = 2))

        assertEquals(emptyList(), buffer.snapshot().audioFrames)
        assertEquals(1, buffer.stats().videoFrameCount)
    }

    @Test
    fun savesSnapshotThroughMediaEncoder() = runTest {
        val buffer = RollingReplayBuffer(ReplayBufferSettings(duration = 5.seconds))
        buffer.append(videoFrame(seconds = 0))
        buffer.append(videoFrame(seconds = 1))
        val output = Files.createTempDirectory("mission-recorder-replay").resolve("replay.mrec")

        val result = ReplayBufferSaver(FrameSequenceMediaEncoder()).save(
            ReplaySaveRequest(
                snapshot = buffer.snapshot(),
                outputPath = output.toString(),
                captureSource = CaptureSource.Screen(
                    id = CaptureSourceId("screen:test"),
                    displayName = "Test screen",
                ),
                frameRate = 1,
                sessionId = RecordingSessionId("replay-session"),
            ),
        )

        assertEquals(output.toString(), result.output.path)
        assertEquals(2, result.videoFrames)
        assertTrue(output.resolve("frames").resolve("frame-000002.png").exists())
        assertTrue(output.resolve("recording.json").exists())
    }

    @Test
    fun rejectsSavingEmptySnapshot() = runTest {
        val buffer = RollingReplayBuffer(ReplayBufferSettings(duration = 5.seconds))

        assertFailsWith<ReplayBufferException> {
            ReplayBufferSaver(FrameSequenceMediaEncoder()).save(
                ReplaySaveRequest(
                    snapshot = buffer.snapshot(),
                    outputPath = "unused.mrec",
                    captureSource = CaptureSource.Screen(
                        id = CaptureSourceId("screen:test"),
                        displayName = "Test screen",
                    ),
                    frameRate = 1,
                    sessionId = RecordingSessionId("replay-session"),
                ),
            )
        }
    }

    private fun videoFrame(seconds: Long, pixels: ByteArray = byteArrayOf(255.toByte(), 0, 0, 255.toByte())) =
        VideoFrame(
            timestamp = MediaTimestamp(seconds * 1_000_000_000L),
            width = 1,
            height = 1,
            pixelFormat = PixelFormat.Rgba8888,
            strideBytes = 4,
            sourceId = CaptureSourceId("screen:test"),
            pixelData = pixels,
        )

    private fun audioFrame(seconds: Long, audioData: ByteArray? = null) =
        AudioFrame(
            timestamp = MediaTimestamp(seconds * 1_000_000_000L),
            sampleRate = 48_000,
            channelCount = 2,
            sampleCount = 480,
            sourceId = AudioSourceId("audio:test"),
            audioData = audioData,
        )
}
