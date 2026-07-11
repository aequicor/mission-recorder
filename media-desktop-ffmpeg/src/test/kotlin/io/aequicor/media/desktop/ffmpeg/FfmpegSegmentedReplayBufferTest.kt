package io.aequicor.media.desktop.ffmpeg

import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.MediaTimestamp
import io.aequicor.capture.core.PixelFormat
import io.aequicor.capture.core.RecordingSession
import io.aequicor.capture.core.RecordingSessionId
import io.aequicor.capture.core.RecordingSettings
import kotlinx.coroutines.test.runTest
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FfmpegSegmentedReplayBufferTest {
    @Test
    fun savesBoundedMp4AndContinuesBuffering() = runTest {
        val directory = Files.createTempDirectory("mission-recorder-replay-buffer-test")
        val storage = directory.resolve("cache")
        val firstOutput = directory.resolve("first-replay.mp4")
        val secondOutput = directory.resolve("second-replay.mp4")
        val microphone = AudioSource.Microphone(
            id = AudioSourceId("mic:test"),
            displayName = "Test microphone",
            sampleRate = AUDIO_SAMPLE_RATE,
            channelCount = 1,
        )
        val settings = RecordingSettings(
            captureSource = CaptureSource.Screen(
                id = CaptureSourceId("screen:test"),
                displayName = "Test screen",
            ),
            audioSources = listOf(microphone),
            outputPath = "unused.mp4",
            frameRate = FRAME_RATE,
            captureCursor = true,
            replayDuration = REPLAY_DURATION,
        )
        val session = RecordingSession(
            id = RecordingSessionId("replay-session-test"),
            settings = settings,
            startedAtNanoseconds = 0,
        )
        val buffer = FfmpegSegmentedReplayBuffer(storageRoot = storage, segmentDuration = 1.seconds)

        buffer.open(session, REPLAY_DURATION)
        repeat(FRAME_RATE * 5) { index ->
            val timestamp = index * NANOS_PER_FRAME
            buffer.writeVideoFrame(videoFrame(timestamp, index))
            buffer.writeAudioFrame(audioFrame(timestamp, microphone.id))
        }

        val firstResult = buffer.save(firstOutput.toString())

        assertEquals(firstOutput.toString(), firstResult.output.path)
        assertTrue(firstResult.videoFrames >= FRAME_RATE)
        assertTrue(firstResult.audioFrames > 0)
        assertTrue(inspectReplay(firstOutput) >= 100)
        assertTrue(segmentFiles(storage).size <= MAX_EXPECTED_SEGMENTS)

        repeat(FRAME_RATE * 2) { offset ->
            val index = FRAME_RATE * 5 + offset
            val timestamp = index * NANOS_PER_FRAME
            buffer.writeVideoFrame(videoFrame(timestamp, index))
            buffer.writeAudioFrame(audioFrame(timestamp, microphone.id))
        }

        val secondResult = buffer.save(secondOutput.toString())

        assertTrue(secondResult.videoFrames >= FRAME_RATE)
        assertTrue(inspectReplay(secondOutput) >= 180)

        buffer.close()
        assertTrue(storage.exists())
        assertEquals(0L, Files.list(storage).use { it.count() })
    }

    private fun inspectReplay(path: Path): Int {
        val grabber = FFmpegFrameGrabber(path.toFile())
        try {
            grabber.pixelFormat = AV_PIX_FMT_BGRA
            grabber.start()
            assertEquals(AV_CODEC_ID_H264, grabber.videoCodec)
            assertEquals(AV_CODEC_ID_AAC, grabber.audioCodec)
            assertEquals(1, grabber.audioChannels)
            assertTrue(grabber.lengthInTime in 1_000_000L..3_100_000L)
            val frame = assertNotNull(grabber.grabImage())
            val pixels = (frame.image[0] as ByteBuffer).duplicate()
            val blue = pixels.get(0).toInt() and 0xff
            val green = pixels.get(1).toInt() and 0xff
            val red = pixels.get(2).toInt() and 0xff
            assertTrue(blue > green + 25, "Replay channel order is wrong: RGB($red, $green, $blue).")
            return red
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    private fun segmentFiles(storage: Path): List<Path> =
        Files.walk(storage).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".ts") }.toList()
        }

    private fun videoFrame(timestampNanoseconds: Long, index: Int): io.aequicor.capture.core.VideoFrame {
        val pixels = ByteArray(WIDTH * HEIGHT * 4)
        val red = ((index / FRAME_RATE) * 40).coerceAtMost(255).toByte()
        var offset = 0
        repeat(WIDTH * HEIGHT) {
            pixels[offset] = red
            pixels[offset + 1] = 60
            pixels[offset + 2] = 120
            pixels[offset + 3] = 255.toByte()
            offset += 4
        }
        return io.aequicor.capture.core.VideoFrame(
            timestamp = MediaTimestamp(timestampNanoseconds),
            width = WIDTH,
            height = HEIGHT,
            pixelFormat = PixelFormat.Rgba8888,
            strideBytes = WIDTH * 4,
            sourceId = CaptureSourceId("screen:test"),
            pixelData = pixels,
        )
    }

    private fun audioFrame(timestampNanoseconds: Long, sourceId: AudioSourceId): AudioFrame {
        val sampleCount = AUDIO_SAMPLE_RATE / FRAME_RATE
        return AudioFrame(
            timestamp = MediaTimestamp(timestampNanoseconds),
            sampleRate = AUDIO_SAMPLE_RATE,
            channelCount = 1,
            sampleCount = sampleCount,
            sourceId = sourceId,
            audioData = ByteArray(sampleCount * 2),
        )
    }
}

private val REPLAY_DURATION = 2.seconds
private const val WIDTH = 64
private const val HEIGHT = 48
private const val FRAME_RATE = 10
private const val AUDIO_SAMPLE_RATE = 48_000
private const val NANOS_PER_FRAME = 100_000_000L
private const val MAX_EXPECTED_SEGMENTS = 3
