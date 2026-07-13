package io.aequicor.capture.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingControllerTest {
    @Test
    fun startsAndStopsRecording() = runTest {
        val encoder = FakeMediaEncoder()
        val controller = RecordingController(
            videoCaptureAdapter = FakeVideoCaptureAdapter(
                frames = listOf(defaultVideoFrame()),
                keepRunning = true,
            ),
            audioCaptureAdapter = FakeAudioCaptureAdapter(),
            mediaEncoder = encoder,
            scope = backgroundScope,
            clock = FakeMediaClock(),
            sessionIdFactory = FixedSessionIdFactory(),
        )

        val result = controller.start(defaultSettings())
        runCurrent()

        assertIs<StartRecordingResult.Started>(result)
        assertIs<RecordingState.Recording>(controller.recordingState.value)

        val stopResult = controller.stop()

        val stopped = assertIs<StopRecordingResult.Stopped>(stopResult)
        assertEquals("capture.mp4", stopped.state.outputPath)
        assertEquals(1, encoder.videoFrames.size)
        assertTrue(encoder.finished)
    }

    @Test
    fun accumulatesVideoAndAudioMetricsFromConcurrentStreams() = runTest {
        val microphone = AudioSource.Microphone(
            id = AudioSourceId("mic-1"),
            displayName = "Test microphone",
            sampleRate = 48_000,
            channelCount = 2,
        )
        val encoder = FakeMediaEncoder()
        val controller = RecordingController(
            videoCaptureAdapter = FakeVideoCaptureAdapter(
                frames = listOf(defaultVideoFrame()),
                keepRunning = true,
            ),
            audioCaptureAdapter = FakeAudioCaptureAdapter(
                frames = listOf(defaultAudioFrame(microphone.id)),
                keepRunning = true,
            ),
            mediaEncoder = encoder,
            scope = backgroundScope,
            clock = FakeMediaClock(),
            sessionIdFactory = FixedSessionIdFactory(),
        )

        controller.start(defaultSettings().copy(audioSources = listOf(microphone)))
        runCurrent()

        val stopped = assertIs<StopRecordingResult.Stopped>(controller.stop())

        assertEquals(1, stopped.state.metrics.videoFrames)
        assertEquals(1, stopped.state.metrics.audioFrames)
        assertEquals(1, encoder.videoFrames.size)
        assertEquals(1, encoder.audioFrames.size)
    }

    @Test
    fun capturesAheadWhileTheEncoderWritesTheCurrentFrame() = runTest {
        var emittedFrames = 0
        val encoder = BlockingFirstFrameMediaEncoder()
        val controller = RecordingController(
            videoCaptureAdapter = object : VideoCaptureAdapter {
                override fun frames(settings: RecordingSettings): Flow<VideoFrame> = flow {
                    repeat(3) { index ->
                        emit(defaultVideoFrame().copy(timestamp = MediaTimestamp(index * 33_333_333L)))
                        emittedFrames += 1
                    }
                    awaitCancellation()
                }
            },
            audioCaptureAdapter = FakeAudioCaptureAdapter(),
            mediaEncoder = encoder,
            scope = backgroundScope,
            clock = FakeMediaClock(),
            sessionIdFactory = FixedSessionIdFactory(),
        )

        controller.start(defaultSettings())
        runCurrent()

        assertEquals(2, emittedFrames)
        assertEquals(1, encoder.startedFrames)

        encoder.releaseFirstFrame.complete(Unit)
        runCurrent()
        controller.stop()
    }

    @Test
    fun countsDroppedFramesFromVideoTimestampGaps() = runTest {
        val videoFrames = Channel<VideoFrame>(Channel.UNLIMITED)
        val clock = FakeMediaClock()
        val controller = RecordingController(
            videoCaptureAdapter = ChannelVideoCaptureAdapter(videoFrames),
            audioCaptureAdapter = FakeAudioCaptureAdapter(),
            mediaEncoder = FakeMediaEncoder(),
            scope = backgroundScope,
            clock = clock,
            sessionIdFactory = FixedSessionIdFactory(),
        )

        controller.start(defaultSettings().copy(frameRate = 30))
        runCurrent()
        videoFrames.send(defaultVideoFrame().copy(timestamp = MediaTimestamp(0)))
        videoFrames.send(defaultVideoFrame().copy(timestamp = MediaTimestamp(33_333_333)))
        videoFrames.send(defaultVideoFrame().copy(timestamp = MediaTimestamp(133_333_332)))
        clock.nowNanoseconds = 133_333_332
        runCurrent()

        val stopped = assertIs<StopRecordingResult.Stopped>(controller.stop())

        assertEquals(3, stopped.state.metrics.videoFrames)
        assertEquals(2, stopped.state.metrics.droppedFrames)
    }

    @Test
    fun marksNextEncodedFrameAsImportant() = runTest {
        val videoFrames = Channel<VideoFrame>(Channel.UNLIMITED)
        val encoder = FakeMediaEncoder()
        val controller = RecordingController(
            videoCaptureAdapter = ChannelVideoCaptureAdapter(videoFrames),
            audioCaptureAdapter = FakeAudioCaptureAdapter(),
            mediaEncoder = encoder,
            scope = backgroundScope,
            clock = FakeMediaClock(),
            sessionIdFactory = FixedSessionIdFactory(),
        )
        val frame = VideoFrame(
            timestamp = MediaTimestamp(100_000_000),
            width = 64,
            height = 48,
            pixelFormat = PixelFormat.Rgba8888,
            strideBytes = 64 * 4,
            sourceId = CaptureSourceId("screen-1"),
            pixelData = ByteArray(64 * 48 * 4) { 0xff.toByte() },
        )

        assertIs<MarkImportantFrameResult.NotRecording>(controller.markImportantFrame())
        controller.start(defaultSettings())
        runCurrent()
        assertIs<MarkImportantFrameResult.Marked>(controller.markImportantFrame())
        videoFrames.send(frame)
        runCurrent()

        val marked = encoder.videoFrames.single()
        val markerOffset = (
            (InputEventFrameMarker.MARGIN_PIXELS + InputEventFrameMarker.CELL_SIZE_PIXELS / 2) * marked.strideBytes +
                (InputEventFrameMarker.MARGIN_PIXELS + InputEventFrameMarker.CELL_SIZE_PIXELS / 2) * 4
            )
        assertEquals(InputEventFrameMarker.ACCENT_RED, requireNotNull(marked.pixelData)[markerOffset].toInt() and 0xff)
        controller.stop()
    }

    @Test
    fun derivesEffectiveFramesPerSecondFromActiveDuration() {
        val metrics = RecordingMetrics(duration = 2.seconds, videoFrames = 120)

        assertEquals(60.0, metrics.effectiveFramesPerSecond)
        assertEquals(0.0, RecordingMetrics(videoFrames = 1).effectiveFramesPerSecond)
    }

    @Test
    fun pausesCaptureAndRemovesCumulativePauseTimeFromMediaTimeline() = runTest {
        val microphone = AudioSource.Microphone(
            id = AudioSourceId("mic-1"),
            displayName = "Test microphone",
            sampleRate = 48_000,
            channelCount = 2,
        )
        val videoFrames = Channel<VideoFrame>(Channel.UNLIMITED)
        val audioFrames = Channel<AudioFrame>(Channel.UNLIMITED)
        val encoder = FakeMediaEncoder()
        val clock = FakeMediaClock()
        val controller = RecordingController(
            videoCaptureAdapter = ChannelVideoCaptureAdapter(videoFrames),
            audioCaptureAdapter = ChannelAudioCaptureAdapter(audioFrames),
            mediaEncoder = encoder,
            scope = backgroundScope,
            clock = clock,
            sessionIdFactory = FixedSessionIdFactory(),
        )

        assertIs<PauseRecordingResult.NotRecording>(controller.pause())
        controller.start(defaultSettings().copy(audioSources = listOf(microphone)))
        runCurrent()

        clock.nowNanoseconds = 100_000_000
        videoFrames.send(defaultVideoFrame().copy(timestamp = MediaTimestamp(100_000_000)))
        audioFrames.send(defaultAudioFrame(microphone.id).copy(timestamp = MediaTimestamp(100_000_000)))
        runCurrent()

        val firstPause = assertIs<PauseRecordingResult.Paused>(controller.pause())
        assertEquals(100.milliseconds, firstPause.state.metrics.duration)
        assertIs<PauseRecordingResult.AlreadyPaused>(controller.pause())

        clock.nowNanoseconds = 600_000_000
        videoFrames.send(defaultVideoFrame().copy(timestamp = MediaTimestamp(600_000_000)))
        audioFrames.send(defaultAudioFrame(microphone.id).copy(timestamp = MediaTimestamp(600_000_000)))
        runCurrent()
        assertEquals(1, encoder.videoFrames.size)
        assertEquals(1, encoder.audioFrames.size)

        clock.nowNanoseconds = 1_100_000_000
        assertIs<ResumeRecordingResult.Resumed>(controller.resume())
        assertIs<ResumeRecordingResult.NotPaused>(controller.resume())
        clock.nowNanoseconds = 1_200_000_000
        videoFrames.send(defaultVideoFrame().copy(timestamp = MediaTimestamp(1_200_000_000)))
        audioFrames.send(defaultAudioFrame(microphone.id).copy(timestamp = MediaTimestamp(1_200_000_000)))
        runCurrent()

        assertIs<PauseRecordingResult.Paused>(controller.pause())
        clock.nowNanoseconds = 1_700_000_000
        assertIs<ResumeRecordingResult.Resumed>(controller.resume())
        clock.nowNanoseconds = 1_800_000_000
        videoFrames.send(defaultVideoFrame().copy(timestamp = MediaTimestamp(1_800_000_000)))
        audioFrames.send(defaultAudioFrame(microphone.id).copy(timestamp = MediaTimestamp(1_800_000_000)))
        runCurrent()

        val stopped = assertIs<StopRecordingResult.Stopped>(controller.stop())

        assertEquals(
            listOf(100_000_000L, 200_000_000L, 300_000_000L),
            encoder.videoFrames.map { frame -> frame.timestamp.nanoseconds },
        )
        assertEquals(
            listOf(100_000_000L, 200_000_000L, 300_000_000L),
            encoder.audioFrames.map { frame -> frame.timestamp.nanoseconds },
        )
        assertEquals(300.milliseconds, stopped.state.metrics.duration)
        assertEquals(3, stopped.state.metrics.videoFrames)
        assertEquals(3, stopped.state.metrics.audioFrames)
    }

    @Test
    fun stopsAndFinalizesWhilePaused() = runTest {
        val clock = FakeMediaClock()
        val controller = RecordingController(
            videoCaptureAdapter = FakeVideoCaptureAdapter(keepRunning = true),
            audioCaptureAdapter = FakeAudioCaptureAdapter(),
            mediaEncoder = FakeMediaEncoder(),
            scope = backgroundScope,
            clock = clock,
            sessionIdFactory = FixedSessionIdFactory(),
        )

        controller.start(defaultSettings())
        runCurrent()
        clock.nowNanoseconds = 250_000_000
        controller.pause()
        clock.nowNanoseconds = 2_000_000_000

        val stopped = assertIs<StopRecordingResult.Stopped>(controller.stop())

        assertEquals(250.milliseconds, stopped.state.metrics.duration)
    }

    @Test
    fun cancelsRecordingAndReleasesEncoder() = runTest {
        val encoder = FakeMediaEncoder()
        val controller = RecordingController(
            videoCaptureAdapter = FakeVideoCaptureAdapter(keepRunning = true),
            audioCaptureAdapter = FakeAudioCaptureAdapter(),
            mediaEncoder = encoder,
            scope = backgroundScope,
            clock = FakeMediaClock(),
            sessionIdFactory = FixedSessionIdFactory(),
        )

        controller.start(defaultSettings())
        runCurrent()

        val cancelResult = controller.cancel()

        assertIs<CancelRecordingResult.Cancelled>(cancelResult)
        assertIs<RecordingState.Cancelled>(controller.recordingState.value)
        assertTrue(encoder.cancelled)
    }

    @Test
    fun reportsCaptureFailure() = runTest {
        val controller = RecordingController(
            videoCaptureAdapter = FakeVideoCaptureAdapter(
                failure = RecordingException(RecordingError.SourceUnavailable("screen is unavailable")),
            ),
            audioCaptureAdapter = FakeAudioCaptureAdapter(),
            mediaEncoder = FakeMediaEncoder(),
            scope = backgroundScope,
            clock = FakeMediaClock(),
            sessionIdFactory = FixedSessionIdFactory(),
        )

        controller.start(defaultSettings())
        runCurrent()

        val failed = assertIs<RecordingState.Failed>(controller.recordingState.value)
        assertIs<RecordingError.SourceUnavailable>(failed.error)
    }

    @Test
    fun failsWhenCaptureStreamEndsBeforeStop() = runTest {
        val controller = RecordingController(
            videoCaptureAdapter = FakeVideoCaptureAdapter(),
            audioCaptureAdapter = FakeAudioCaptureAdapter(),
            mediaEncoder = FakeMediaEncoder(),
            scope = backgroundScope,
            clock = FakeMediaClock(),
            sessionIdFactory = FixedSessionIdFactory(),
        )

        controller.start(defaultSettings())
        runCurrent()

        val failed = assertIs<RecordingState.Failed>(controller.recordingState.value)
        assertIs<RecordingError.SourceUnavailable>(failed.error)
    }

    @Test
    fun rejectsInvalidSettingsBeforeStarting() = runTest {
        val controller = RecordingController(
            videoCaptureAdapter = FakeVideoCaptureAdapter(),
            audioCaptureAdapter = FakeAudioCaptureAdapter(),
            mediaEncoder = FakeMediaEncoder(),
            scope = backgroundScope,
            clock = FakeMediaClock(),
            sessionIdFactory = FixedSessionIdFactory(),
        )

        val result = controller.start(
            defaultSettings().copy(
                captureSource = CaptureSource.Region(
                    id = CaptureSourceId("region"),
                    displayName = "Broken region",
                    region = CaptureRegion(x = 0, y = 0, width = 0, height = 720),
                ),
            ),
        )

        val rejected = assertIs<StartRecordingResult.Rejected>(result)
        assertTrue(rejected.issues.any { it.field == "captureSource.region.width" })
        assertEquals(RecordingState.Idle, controller.recordingState.value)
    }

    private fun defaultSettings(): RecordingSettings =
        RecordingSettings(
            captureSource = CaptureSource.Screen(
                id = CaptureSourceId("screen-1"),
                displayName = "Primary screen",
            ),
            outputPath = "capture.mp4",
        )

    private fun defaultVideoFrame(): VideoFrame =
        VideoFrame(
            timestamp = MediaTimestamp(0),
            width = 1920,
            height = 1080,
            pixelFormat = PixelFormat.Bgra8888,
            strideBytes = 1920 * 4,
            sourceId = CaptureSourceId("screen-1"),
        )

    private fun defaultAudioFrame(sourceId: AudioSourceId): AudioFrame =
        AudioFrame(
            timestamp = MediaTimestamp(0),
            sampleRate = 48_000,
            channelCount = 2,
            sampleCount = 1,
            sourceId = sourceId,
            audioData = byteArrayOf(0, 0, 0, 0),
        )
}

private class FakeVideoCaptureAdapter(
    private val frames: List<VideoFrame> = emptyList(),
    private val keepRunning: Boolean = false,
    private val failure: Throwable? = null,
) : VideoCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<VideoFrame> = flow {
        failure?.let { throw it }
        frames.forEach { emit(it) }
        if (keepRunning) {
            awaitCancellation()
        }
    }
}

private class FakeAudioCaptureAdapter(
    private val frames: List<AudioFrame> = emptyList(),
    private val keepRunning: Boolean = false,
    private val failure: Throwable? = null,
) : AudioCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<AudioFrame> = flow {
        failure?.let { throw it }
        frames.forEach { emit(it) }
        if (keepRunning) {
            awaitCancellation()
        }
    }
}

private class FakeMediaEncoder : MediaEncoder {
    val videoFrames = mutableListOf<VideoFrame>()
    val audioFrames = mutableListOf<AudioFrame>()
    var finished = false
        private set
    var cancelled = false
        private set

    override suspend fun open(session: RecordingSession, settings: RecordingSettings) = Unit

    override suspend fun writeVideoFrame(frame: VideoFrame) {
        videoFrames += frame
    }

    override suspend fun writeAudioFrame(frame: AudioFrame) {
        audioFrames += frame
    }

    override suspend fun finish(session: RecordingSession, metrics: RecordingMetrics): RecordingOutput {
        finished = true
        return RecordingOutput(session.settings.outputPath)
    }

    override suspend fun cancel(session: RecordingSession?) {
        cancelled = true
    }
}

private class BlockingFirstFrameMediaEncoder : MediaEncoder {
    val releaseFirstFrame = CompletableDeferred<Unit>()
    var startedFrames = 0
        private set

    override suspend fun open(session: RecordingSession, settings: RecordingSettings) = Unit

    override suspend fun writeVideoFrame(frame: VideoFrame) {
        startedFrames += 1
        if (startedFrames == 1) {
            releaseFirstFrame.await()
        }
    }

    override suspend fun writeAudioFrame(frame: AudioFrame) = Unit

    override suspend fun finish(session: RecordingSession, metrics: RecordingMetrics): RecordingOutput =
        RecordingOutput(session.settings.outputPath)

    override suspend fun cancel(session: RecordingSession?) = Unit
}

private class FakeMediaClock(
    var nowNanoseconds: Long = 0,
) : MediaClock {
    override fun nowNanoseconds(): Long = nowNanoseconds
}

private class ChannelVideoCaptureAdapter(
    private val channel: Channel<VideoFrame>,
) : VideoCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<VideoFrame> = channel.receiveAsFlow()
}

private class ChannelAudioCaptureAdapter(
    private val channel: Channel<AudioFrame>,
) : AudioCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<AudioFrame> = channel.receiveAsFlow()
}

private class FixedSessionIdFactory : RecordingSessionIdFactory {
    override fun nextId(): RecordingSessionId = RecordingSessionId("session-1")
}
