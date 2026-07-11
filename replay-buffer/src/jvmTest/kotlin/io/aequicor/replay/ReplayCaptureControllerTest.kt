package io.aequicor.replay

import io.aequicor.capture.core.AudioCaptureAdapter
import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.MediaClock
import io.aequicor.capture.core.MediaTimestamp
import io.aequicor.capture.core.PixelFormat
import io.aequicor.capture.core.RecordingOutput
import io.aequicor.capture.core.RecordingSession
import io.aequicor.capture.core.RecordingSessionId
import io.aequicor.capture.core.RecordingSessionIdFactory
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.VideoCaptureAdapter
import io.aequicor.capture.core.VideoFrame
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayCaptureControllerTest {
    @Test
    fun buffersSavesAndStopsWithoutPlatformDevices() = runTest {
        val mediaBuffer = FakeReplayMediaBuffer()
        val controller = ReplayCaptureController(
            videoCaptureAdapter = OneFrameReplayVideoAdapter,
            audioCaptureAdapter = EmptyReplayAudioAdapter,
            mediaBuffer = mediaBuffer,
            scope = backgroundScope,
            clock = FixedReplayClock,
            sessionIdFactory = FixedReplaySessionIdFactory,
        )

        val start = controller.start(settings())
        runCurrent()

        assertIs<StartReplayResult.Started>(start)
        val buffering = assertIs<ReplayCaptureState.Buffering>(controller.state.value)
        assertEquals(1, buffering.stats.videoFrameCount)

        val save = controller.save("replay.mp4")

        val saved = assertIs<SaveReplayResult.Saved>(save)
        assertEquals("replay.mp4", saved.result.output.path)
        assertIs<ReplayCaptureState.Buffering>(controller.state.value)

        val stop = controller.stop()

        assertIs<StopReplayResult.Stopped>(stop)
        assertEquals(ReplayCaptureState.Idle, controller.state.value)
        assertTrue(mediaBuffer.closed)
    }

    @Test
    fun closesBufferAndPublishesCaptureFailure() = runTest {
        val mediaBuffer = FakeReplayMediaBuffer(failVideoWrite = true)
        val controller = ReplayCaptureController(
            videoCaptureAdapter = OneFrameReplayVideoAdapter,
            audioCaptureAdapter = EmptyReplayAudioAdapter,
            mediaBuffer = mediaBuffer,
            scope = backgroundScope,
            clock = FixedReplayClock,
            sessionIdFactory = FixedReplaySessionIdFactory,
        )

        controller.start(settings())
        runCurrent()

        val failed = assertIs<ReplayCaptureState.Failed>(controller.state.value)
        assertTrue(failed.message.contains("Synthetic replay failure"))
        assertTrue(mediaBuffer.closed)
    }

    private fun settings() = RecordingSettings(
        captureSource = CaptureSource.Screen(
            id = CaptureSourceId("screen:test"),
            displayName = "Test screen",
        ),
        outputPath = "unused.mp4",
        frameRate = 30,
        replayDuration = 5.seconds,
    )
}

private class FakeReplayMediaBuffer(
    private val failVideoWrite: Boolean = false,
) : ReplayMediaBuffer {
    private var videoFrames = 0
    var closed = false
        private set

    override suspend fun open(session: RecordingSession, duration: Duration) = Unit

    override suspend fun writeVideoFrame(frame: VideoFrame): ReplayBufferStats {
        if (failVideoWrite) {
            error("Synthetic replay failure")
        }
        videoFrames += 1
        return stats()
    }

    override suspend fun writeAudioFrame(frame: AudioFrame): ReplayBufferStats = stats()

    override suspend fun save(outputPath: String): ReplaySaveResult = ReplaySaveResult(
        output = RecordingOutput(outputPath),
        videoFrames = videoFrames.toLong(),
        audioFrames = 0,
        duration = 1.seconds,
    )

    override suspend fun close() {
        closed = true
    }

    private fun stats() = ReplayBufferStats(
        videoFrameCount = videoFrames,
        audioFrameCount = 0,
        retainedDuration = 1.seconds,
        storagePolicy = ReplayStoragePolicy.DiskSegments,
    )
}

private data object OneFrameReplayVideoAdapter : VideoCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<VideoFrame> = flow {
        emit(
            VideoFrame(
                timestamp = MediaTimestamp(0),
                width = 1,
                height = 1,
                pixelFormat = PixelFormat.Rgba8888,
                strideBytes = 4,
                sourceId = settings.captureSource.id,
                pixelData = byteArrayOf(0, 0, 0, 255.toByte()),
            ),
        )
        awaitCancellation()
    }
}

private data object EmptyReplayAudioAdapter : AudioCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<AudioFrame> = emptyFlow()
}

private data object FixedReplayClock : MediaClock {
    override fun nowNanoseconds(): Long = 0
}

private data object FixedReplaySessionIdFactory : RecordingSessionIdFactory {
    override fun nextId(): RecordingSessionId = RecordingSessionId("replay-session-test")
}
