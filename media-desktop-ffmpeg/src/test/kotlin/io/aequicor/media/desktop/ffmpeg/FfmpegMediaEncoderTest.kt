package io.aequicor.media.desktop.ffmpeg

import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.AudioCaptureAdapter
import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.HardwareAccelerationMode
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.MediaTimestamp
import io.aequicor.capture.core.MediaClock
import io.aequicor.capture.core.PauseRecordingResult
import io.aequicor.capture.core.PixelFormat
import io.aequicor.capture.core.RecordingController
import io.aequicor.capture.core.RecordingException
import io.aequicor.capture.core.RecordingMetrics
import io.aequicor.capture.core.RecordingSession
import io.aequicor.capture.core.RecordingSessionId
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.ResumeRecordingResult
import io.aequicor.capture.core.StopRecordingResult
import io.aequicor.capture.core.VideoCaptureAdapter
import io.aequicor.capture.core.VideoFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.nio.ByteBuffer
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class FfmpegMediaEncoderTest {
    @Test
    fun keepsVideoTimestampsOnDistinctEncoderTicks() {
        assertEquals(0, monotonicVideoTimestampMicros(0, null, frameRate = 60))
        assertEquals(16_667, monotonicVideoTimestampMicros(1_000, 0, frameRate = 60))
        assertEquals(50_000, monotonicVideoTimestampMicros(50_000, 16_667, frameRate = 60))
        assertEquals(66_667, monotonicVideoTimestampMicros(50_000, 50_000, frameRate = 60))
    }

    @Test
    fun usesWidelySupportedH264ChromaFormat() {
        assertEquals(AV_PIX_FMT_YUV420P, h264OutputPixelFormat())
    }

    @Test
    fun selectsHardwareEncodersBeforeSoftwareFallback() {
        assertEquals(
            listOf("h264_nvenc", "h264_qsv", "h264_mf", "libopenh264"),
            h264EncoderCandidates(HardwareAccelerationMode.Auto, osName = "Windows 11"),
        )
        assertEquals(
            listOf("libopenh264"),
            h264EncoderCandidates(HardwareAccelerationMode.Disabled, osName = "Windows 11"),
        )
        assertEquals(
            listOf("h264_videotoolbox"),
            h264EncoderCandidates(HardwareAccelerationMode.Required, osName = "Mac OS X"),
        )
    }

    @Test
    fun preservesRgbaChannelOrderInsteadOfTreatingArgbAlphaAsRed() = runTest {
        val output = Files.createTempDirectory("mission-recorder-color-test").resolve("colors.mp4")
        val settings = RecordingSettings(
            captureSource = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen"),
            outputPath = output.toString(),
            frameRate = VIDEO_FRAME_RATE,
        )
        val session = RecordingSession(RecordingSessionId("color-test"), settings, 0)
        val encoder = FfmpegMediaEncoder()

        encoder.open(session, settings)
        repeat(3) { index ->
            encoder.writeVideoFrame(solidRgbaFrame(index * FRAME_INTERVAL_NANOS, red = 0, green = 255, blue = 0))
        }
        encoder.finish(session, RecordingMetrics(videoFrames = 3, duration = 300.milliseconds))

        val grabber = FFmpegFrameGrabber(output.toFile())
        try {
            grabber.pixelFormat = AV_PIX_FMT_BGRA
            grabber.start()
            val decoded = assertNotNull(grabber.grabImage())
            val pixels = (decoded.image[0] as ByteBuffer).duplicate()
            val offset = decoded.imageHeight / 2 * decoded.imageStride + decoded.imageWidth / 2 * 4
            val blue = pixels.get(offset).toInt() and 0xff
            val green = pixels.get(offset + 1).toInt() and 0xff
            val red = pixels.get(offset + 2).toInt() and 0xff
            assertTrue(green > 180, "Expected green channel to remain dominant, got RGB($red, $green, $blue).")
            assertTrue(red < 80, "ARGB alpha was interpreted as red: RGB($red, $green, $blue).")
            assertTrue(blue < 80, "Unexpected blue channel: RGB($red, $green, $blue).")
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }

        val storyboard = FfmpegStoryboardExporter().export(
            StoryboardExportSettings(
                inputVideo = output,
                outputPath = output.parent.resolve("color-frames"),
                layout = StoryboardLayout.SeparatePngFiles,
                interval = 100.milliseconds,
            ),
        )
        val exported = assertNotNull(ImageIO.read(storyboard.outputPaths.first().toFile()))
        assertEquals(ENCODED_VIDEO_WIDTH, exported.width)
        assertEquals(ENCODED_VIDEO_HEIGHT, exported.height)
        val exportedRgb = exported.getRGB(exported.width / 2, exported.height / 2)
        val exportedRed = exportedRgb ushr 16 and 0xff
        val exportedGreen = exportedRgb ushr 8 and 0xff
        val exportedBlue = exportedRgb and 0xff
        assertTrue(
            exportedGreen > 180 && exportedRed < 80 && exportedBlue < 80,
            "Storyboard changed channel order: RGB($exportedRed, $exportedGreen, $exportedBlue).",
        )
    }

    @Test
    fun replacesExistingMp4OnlyAfterSuccessfulFinish() = runTest {
        val output = Files.createTempDirectory("mission-recorder-overwrite-test").resolve("recording.mp4")
        val original = "existing recording".encodeToByteArray()
        Files.write(output, original)
        val settings = RecordingSettings(
            captureSource = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen"),
            outputPath = output.toString(),
            overwriteOutput = true,
            frameRate = VIDEO_FRAME_RATE,
        )
        val session = RecordingSession(RecordingSessionId("overwrite-test"), settings, 0)
        val encoder = FfmpegMediaEncoder()

        encoder.open(session, settings)
        encoder.writeVideoFrame(videoFrame(0, 0))
        encoder.writeVideoFrame(videoFrame(FRAME_INTERVAL_NANOS, 1))
        encoder.finish(session, RecordingMetrics(videoFrames = 2, duration = 100.milliseconds))

        assertTrue(Files.size(output) > original.size)
        inspectVideo(output, expectedAudioChannels = 0)
    }

    @Test
    fun cancelPreservesExistingMp4AndOverwriteMustBeExplicit() = runTest {
        val output = Files.createTempDirectory("mission-recorder-overwrite-cancel-test").resolve("recording.mp4")
        val original = "existing recording".encodeToByteArray()
        Files.write(output, original)
        val baseSettings = RecordingSettings(
            captureSource = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen"),
            outputPath = output.toString(),
        )
        val rejectedSession = RecordingSession(RecordingSessionId("reject-test"), baseSettings, 0)

        assertFailsWith<RecordingException> {
            FfmpegMediaEncoder().open(rejectedSession, baseSettings)
        }

        val overwriteSettings = baseSettings.copy(overwriteOutput = true)
        val overwriteSession = RecordingSession(RecordingSessionId("cancel-test"), overwriteSettings, 0)
        val encoder = FfmpegMediaEncoder()
        encoder.open(overwriteSession, overwriteSettings)
        encoder.writeVideoFrame(videoFrame(0, 0))
        encoder.cancel(overwriteSession)

        assertContentEquals(original, Files.readAllBytes(output))
    }

    @Test
    fun writesPlayableMp4AndExportsBothStoryboardLayouts() = runTest {
        val temporaryDirectory = Files.createTempDirectory("mission-recorder-ffmpeg-test")
        val output = temporaryDirectory.resolve("recording.mp4")
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
            outputPath = output.toString(),
            frameRate = VIDEO_FRAME_RATE,
            captureCursor = true,
        )
        val session = RecordingSession(
            id = RecordingSessionId("session-test"),
            settings = settings,
            startedAtNanoseconds = 0,
        )
        val encoder = FfmpegMediaEncoder()

        encoder.open(session, settings)
        repeat(VIDEO_FRAME_COUNT) { index ->
            val timestamp = index * FRAME_INTERVAL_NANOS
            encoder.writeAudioFrame(audioFrame(timestamp, microphone.id))
            encoder.writeVideoFrame(videoFrame(timestamp, index))
        }
        val result = encoder.finish(
            session,
            RecordingMetrics(
                duration = 1.seconds,
                videoFrames = VIDEO_FRAME_COUNT.toLong(),
                audioFrames = VIDEO_FRAME_COUNT.toLong(),
            ),
        )

        assertEquals(output.toString(), result.path)
        assertTrue(output.exists())
        assertTrue(Files.size(output) > 1_000)
        inspectVideo(output, expectedAudioChannels = 1)

        val exporter = FfmpegStoryboardExporter()
        assertEquals(
            640,
            StoryboardExportSettings(
                inputVideo = output,
                outputPath = temporaryDirectory.resolve("unused-storyboard.png"),
                layout = StoryboardLayout.ContactSheet,
            ).thumbnailWidth,
        )
        val separateDirectory = temporaryDirectory.resolve("frames")
        val separate = exporter.export(
            StoryboardExportSettings(
                inputVideo = output,
                outputPath = separateDirectory,
                layout = StoryboardLayout.SeparatePngFiles,
                interval = 200.milliseconds,
            ),
        )
        assertTrue(separate.frameCount >= 4)
        assertEquals(separate.frameCount, separate.outputPaths.size)
        assertTrue(separate.outputPaths.all { it.exists() })
        separate.outputPaths.forEach { framePath ->
            val frame = assertNotNull(ImageIO.read(framePath.toFile()))
            assertEquals(ENCODED_VIDEO_WIDTH, frame.width)
            assertEquals(ENCODED_VIDEO_HEIGHT, frame.height)
        }

        val contactSheetPath = temporaryDirectory.resolve("storyboard.png")
        val contactSheet = exporter.export(
            StoryboardExportSettings(
                inputVideo = output,
                outputPath = contactSheetPath,
                layout = StoryboardLayout.ContactSheet,
                interval = 200.milliseconds,
                columns = 3,
            ),
        )
        assertEquals(separate.frameCount, contactSheet.frameCount)
        val sheetImage = assertNotNull(ImageIO.read(contactSheetPath.toFile()))
        assertTrue(sheetImage.width > VIDEO_WIDTH)
        assertTrue(sheetImage.height >= VIDEO_HEIGHT)
    }

    @Test
    fun writesMixedMicrophoneAndSystemAudioAsStereoAac() = runTest {
        val temporaryDirectory = Files.createTempDirectory("mission-recorder-ffmpeg-mixed-audio-test")
        val output = temporaryDirectory.resolve("mixed.mp4")
        val microphone = AudioSource.Microphone(AudioSourceId("mic:test"), "Mic", 44_100, 1)
        val system = AudioSource.SystemLoopback(AudioSourceId("system:test"), "System", 48_000, 2)
        val settings = RecordingSettings(
            captureSource = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen"),
            audioSources = listOf(microphone, system),
            outputPath = output.toString(),
            frameRate = VIDEO_FRAME_RATE,
        )
        val session = RecordingSession(RecordingSessionId("mixed-session"), settings, 0)
        val encoder = FfmpegMediaEncoder()

        encoder.open(session, settings)
        repeat(VIDEO_FRAME_COUNT) { index ->
            val timestamp = index * FRAME_INTERVAL_NANOS
            encoder.writeAudioFrame(audioFrame(timestamp, AudioSourceId("audio:mixed"), channelCount = 2))
            encoder.writeVideoFrame(videoFrame(timestamp, index))
        }
        encoder.finish(session, RecordingMetrics(duration = 1.seconds))

        inspectVideo(output, expectedAudioChannels = 2)
    }

    @Test
    fun controllerPauseProducesMp4WithoutPausedWallClockGap() = runTest {
        val temporaryDirectory = Files.createTempDirectory("mission-recorder-ffmpeg-pause-test")
        val output = temporaryDirectory.resolve("paused.mp4")
        val settings = RecordingSettings(
            captureSource = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen"),
            outputPath = output.toString(),
            frameRate = VIDEO_FRAME_RATE,
        )
        val videoFrames = Channel<VideoFrame>(Channel.UNLIMITED)
        val clock = MutableMediaClock()
        val controller = RecordingController(
            videoCaptureAdapter = ChannelVideoAdapter(videoFrames),
            audioCaptureAdapter = EmptyAudioAdapter,
            mediaEncoder = FfmpegMediaEncoder(),
            scope = backgroundScope,
            clock = clock,
            sessionIdFactory = { RecordingSessionId("pause-session") },
        )

        controller.start(settings)
        runCurrent()
        repeat(5) { index ->
            clock.nowNanoseconds = index * FRAME_INTERVAL_NANOS
            videoFrames.send(videoFrame(clock.nowNanoseconds, index))
            runCurrent()
        }

        clock.nowNanoseconds = 500_000_000
        assertIs<PauseRecordingResult.Paused>(controller.pause())
        listOf(1_000_000_000L, 3_000_000_000L).forEachIndexed { index, timestamp ->
            clock.nowNanoseconds = timestamp
            videoFrames.send(videoFrame(timestamp, index + 5))
            runCurrent()
        }

        clock.nowNanoseconds = 5_500_000_000
        assertIs<ResumeRecordingResult.Resumed>(controller.resume())
        repeat(5) { index ->
            val timestamp = 5_500_000_000 + index * FRAME_INTERVAL_NANOS
            clock.nowNanoseconds = timestamp
            videoFrames.send(videoFrame(timestamp, index + 7))
            runCurrent()
        }
        clock.nowNanoseconds = 6_000_000_000

        val stopped = assertIs<StopRecordingResult.Stopped>(controller.stop())

        assertEquals(10, stopped.state.metrics.videoFrames)
        assertEquals(1.seconds, stopped.state.metrics.duration)
        val grabber = FFmpegFrameGrabber(output.toFile())
        try {
            grabber.start()
            assertTrue(grabber.lengthInTime in 500_000..1_500_000)
            assertNotNull(grabber.grabImage())
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    private fun inspectVideo(output: java.nio.file.Path, expectedAudioChannels: Int) {
        val grabber = FFmpegFrameGrabber(output.toFile())
        try {
            grabber.start()
            assertEquals(VIDEO_WIDTH + (VIDEO_WIDTH and 1), grabber.imageWidth)
            assertEquals(VIDEO_HEIGHT + (VIDEO_HEIGHT and 1), grabber.imageHeight)
            assertEquals(expectedAudioChannels, grabber.audioChannels)
            assertEquals(AV_CODEC_ID_H264, grabber.videoCodec)
            if (expectedAudioChannels > 0) {
                assertEquals(AV_CODEC_ID_AAC, grabber.audioCodec)
            } else {
                assertEquals(0, grabber.audioCodec)
            }
            assertTrue(grabber.frameRate > 0.0)
            assertNotNull(grabber.grabImage())
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    private fun videoFrame(timestampNanoseconds: Long, index: Int): VideoFrame {
        val pixels = ByteArray(VIDEO_WIDTH * VIDEO_HEIGHT * 4)
        var offset = 0
        repeat(VIDEO_WIDTH * VIDEO_HEIGHT) { pixel ->
            pixels[offset] = ((pixel + index * 13) and 0xff).toByte()
            pixels[offset + 1] = ((pixel / VIDEO_WIDTH * 7) and 0xff).toByte()
            pixels[offset + 2] = (180 - index * 8).toByte()
            pixels[offset + 3] = 255.toByte()
            offset += 4
        }
        return VideoFrame(
            timestamp = MediaTimestamp(timestampNanoseconds),
            width = VIDEO_WIDTH,
            height = VIDEO_HEIGHT,
            pixelFormat = PixelFormat.Rgba8888,
            strideBytes = VIDEO_WIDTH * 4,
            sourceId = CaptureSourceId("screen:test"),
            pixelData = pixels,
        )
    }

    private fun solidRgbaFrame(timestampNanoseconds: Long, red: Int, green: Int, blue: Int): VideoFrame {
        val pixels = ByteArray(VIDEO_WIDTH * VIDEO_HEIGHT * 4)
        for (offset in pixels.indices step 4) {
            pixels[offset] = red.toByte()
            pixels[offset + 1] = green.toByte()
            pixels[offset + 2] = blue.toByte()
            pixels[offset + 3] = 0xff.toByte()
        }
        return VideoFrame(
            timestamp = MediaTimestamp(timestampNanoseconds),
            width = VIDEO_WIDTH,
            height = VIDEO_HEIGHT,
            pixelFormat = PixelFormat.Rgba8888,
            strideBytes = VIDEO_WIDTH * 4,
            sourceId = CaptureSourceId("screen:test"),
            pixelData = pixels,
        )
    }

    private fun audioFrame(
        timestampNanoseconds: Long,
        sourceId: AudioSourceId,
        channelCount: Int = 1,
    ): AudioFrame {
        val sampleCount = AUDIO_SAMPLE_RATE / VIDEO_FRAME_RATE
        return AudioFrame(
            timestamp = MediaTimestamp(timestampNanoseconds),
            sampleRate = AUDIO_SAMPLE_RATE,
            channelCount = channelCount,
            sampleCount = sampleCount,
            sourceId = sourceId,
            audioData = ByteArray(sampleCount * channelCount * 2),
        )
    }
}

private class MutableMediaClock(
    var nowNanoseconds: Long = 0,
) : MediaClock {
    override fun nowNanoseconds(): Long = nowNanoseconds
}

private class ChannelVideoAdapter(
    private val frames: Channel<VideoFrame>,
) : VideoCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<VideoFrame> = frames.receiveAsFlow()
}

private data object EmptyAudioAdapter : AudioCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<AudioFrame> = emptyFlow()
}

private const val VIDEO_WIDTH = 65
private const val VIDEO_HEIGHT = 49
private const val ENCODED_VIDEO_WIDTH = VIDEO_WIDTH + (VIDEO_WIDTH and 1)
private const val ENCODED_VIDEO_HEIGHT = VIDEO_HEIGHT + (VIDEO_HEIGHT and 1)
private const val VIDEO_FRAME_RATE = 10
private const val VIDEO_FRAME_COUNT = 10
private const val AUDIO_SAMPLE_RATE = 48_000
private const val FRAME_INTERVAL_NANOS = 100_000_000L
