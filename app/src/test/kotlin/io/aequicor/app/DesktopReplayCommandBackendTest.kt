package io.aequicor.app

import io.aequicor.capture.core.AudioCaptureAdapter
import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.MediaTimestamp
import io.aequicor.capture.core.PixelFormat
import io.aequicor.capture.core.RecordingOutput
import io.aequicor.capture.core.RecordingSession
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.VideoCaptureAdapter
import io.aequicor.capture.core.VideoFrame
import io.aequicor.capture.platform.AudioSourceRepository
import io.aequicor.capture.platform.AudioSourceRequest
import io.aequicor.capture.platform.CaptureSourceRepository
import io.aequicor.capture.platform.CaptureSourceRequest
import io.aequicor.capture.platform.CapturePermission
import io.aequicor.capture.platform.GrantedPermissionGateway
import io.aequicor.capture.platform.PermissionGateway
import io.aequicor.capture.platform.PermissionReport
import io.aequicor.capture.platform.PermissionStatus
import io.aequicor.cli.CliCommand
import io.aequicor.cli.RecordTarget
import io.aequicor.cli.RecordingControlAction
import io.aequicor.cli.RecordingControlCommandResult
import io.aequicor.cli.ReplayCommandResult
import io.aequicor.cli.ReplayRunOptions
import io.aequicor.replay.ReplayBufferStats
import io.aequicor.replay.ReplayMediaBuffer
import io.aequicor.replay.ReplaySaveResult
import io.aequicor.replay.ReplayStoragePolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopReplayCommandBackendTest {
    @Test
    fun rejectsDeniedPermissionBeforeOpeningReplayBuffer() = runTest {
        val output = Files.createTempDirectory("mission-recorder-replay-permission-test").resolve("replay.mp4")
        val mediaBuffer = FakeCommandReplayMediaBuffer()

        val result = createReplayBackend(mediaBuffer, ReplayDenyingPermissionGateway).run(
            replayCommand(output = output, runDuration = "1ms"),
        )

        val rejected = assertIs<ReplayCommandResult.Rejected>(result)
        assertTrue(rejected.message.contains("screen recording"))
        assertEquals(null, mediaBuffer.openedSettings)
        assertFalse(mediaBuffer.closed)
    }

    @Test
    fun savesLatestBufferOnExplicitStopWithoutDeadline() = runTest {
        val output = Files.createTempDirectory("mission-recorder-replay-stop-test").resolve("replay.mp4")
        val mediaBuffer = FakeCommandReplayMediaBuffer()
        val backend = createReplayBackend(mediaBuffer)

        val replay = async {
            backend.run(replayCommand(output = output, runDuration = null))
        }
        runCurrent()

        assertFalse(replay.isCompleted)

        val stopResult = backend.requestSaveAndStop()
        val replayResult = replay.await()

        val completed = assertIs<ReplayCommandResult.Completed>(replayResult)
        assertEquals(completed, stopResult)
        assertEquals(output.toString(), mediaBuffer.savedOutputPath)
        assertTrue(mediaBuffer.closed)
        assertNull(backend.requestSaveAndStop())
    }

    @Test
    fun rejectsNonPositiveOptionalDeadlineBeforeOpeningBuffer() = runTest {
        val output = Files.createTempDirectory("mission-recorder-replay-duration-test").resolve("replay.mp4")
        val mediaBuffer = FakeCommandReplayMediaBuffer()

        val result = createReplayBackend(mediaBuffer).run(
            replayCommand(output = output, runDuration = "0s"),
        )

        val rejected = assertIs<ReplayCommandResult.Rejected>(result)
        assertTrue(rejected.message.contains("Invalid --run-for duration"))
        assertEquals(null, mediaBuffer.openedSettings)
    }

    @Test
    fun cancellationClosesReplayWithoutSavingSnapshot() = runTest {
        val output = Files.createTempDirectory("mission-recorder-replay-cancel-test").resolve("replay.mp4")
        val mediaBuffer = FakeCommandReplayMediaBuffer()
        val backend = createReplayBackend(mediaBuffer)
        val replay = async {
            backend.run(replayCommand(output = output, runDuration = null))
        }
        runCurrent()

        replay.cancelAndJoin()

        assertTrue(mediaBuffer.closed)
        assertNull(mediaBuffer.savedOutputPath)
        assertNull(backend.requestSaveAndStop())
    }

    @Test
    fun capturesForRequestedTimeAndSavesLatestBuffer() = runTest {
        val output = Files.createTempDirectory("mission-recorder-replay-command-test").resolve("replay.mp4")
        val mediaBuffer = FakeCommandReplayMediaBuffer()
        val backend = createReplayBackend(mediaBuffer)

        val result = backend.run(
            replayCommand(output = output, runDuration = "1ms"),
        )

        val completed = assertIs<ReplayCommandResult.Completed>(result)
        assertEquals(output.toString(), completed.outputPath)
        assertEquals(1, completed.videoFrames)
        assertEquals(2.seconds, mediaBuffer.openedDuration)
        assertTrue(requireNotNull(mediaBuffer.openedSettings).captureCursor)
        assertTrue(mediaBuffer.closed)
    }

    @Test
    fun controlsReplayStatusSnapshotSaveAndFinalStopThroughLocalEndpoint() = runTest {
        val temporaryDirectory = Files.createTempDirectory("mission-recorder-replay-control-test")
        val output = temporaryDirectory.resolve("final-replay.mp4")
        val snapshot = temporaryDirectory.resolve("snapshot.mp4")
        val endpoint = temporaryDirectory.resolve("replay-control.json")
        val mediaBuffer = FakeCommandReplayMediaBuffer()
        val backend = createReplayBackend(mediaBuffer)
        val controlBackend = LocalRecordingControlCommandBackend()
        val replay = async {
            backend.run(
                replayCommand(
                    output = output,
                    runDuration = null,
                    controlEndpointPath = endpoint.toString(),
                ),
            )
        }
        runCurrent()

        assertTrue(endpoint.exists())
        val status = controlBackend.control(
            CliCommand.Control(RecordingControlAction.Status, endpoint.toString(), json = false),
        )
        val completedStatus = assertIs<RecordingControlCommandResult.Completed>(status).status
        assertEquals(
            "replay-buffering",
            completedStatus.state,
        )
        assertEquals(2, completedStatus.droppedFrames)
        val conflictingSave = controlBackend.control(
            CliCommand.Control(
                action = RecordingControlAction.Save,
                endpointPath = endpoint.toString(),
                outputPath = output.toString(),
                json = false,
            ),
        )
        assertTrue(
            assertIs<RecordingControlCommandResult.Rejected>(conflictingSave).message.contains("must differ"),
        )

        val saved = controlBackend.control(
            CliCommand.Control(
                action = RecordingControlAction.Save,
                endpointPath = endpoint.toString(),
                outputPath = snapshot.toString(),
                json = false,
            ),
        )
        assertEquals("Replay snapshot saved.", assertIs<RecordingControlCommandResult.Completed>(saved).message)
        assertEquals(listOf(snapshot.toString()), mediaBuffer.savedOutputPaths)
        assertFalse(mediaBuffer.closed)
        assertFalse(replay.isCompleted)

        val stopped = controlBackend.control(
            CliCommand.Control(RecordingControlAction.Stop, endpoint.toString(), json = false),
        )
        assertEquals(
            "Replay save and stop requested.",
            assertIs<RecordingControlCommandResult.Completed>(stopped).message,
        )
        runCurrent()

        assertIs<ReplayCommandResult.Completed>(replay.await())
        assertEquals(listOf(snapshot.toString(), output.toString()), mediaBuffer.savedOutputPaths)
        assertTrue(mediaBuffer.closed)
        assertFalse(endpoint.exists())
    }
}

private fun createReplayBackend(
    mediaBuffer: ReplayMediaBuffer,
    permissionGateway: PermissionGateway = GrantedPermissionGateway,
) = DesktopReplayCommandBackend(
    captureSourceRepository = ReplayTestCaptureSourceRepository,
    videoCaptureAdapter = ReplayOneFrameVideoAdapter,
    audioSourceRepository = ReplayTestAudioSourceRepository,
    audioCaptureAdapter = ReplayEmptyAudioAdapter,
    mediaBufferFactory = { mediaBuffer },
    permissionGateway = permissionGateway,
)

private fun replayCommand(
    output: java.nio.file.Path,
    runDuration: String?,
    controlEndpointPath: String? = null,
) = CliCommand.ReplayRun(
    target = RecordTarget.Screen,
    options = ReplayRunOptions(
        bufferDuration = "2s",
        runDuration = runDuration,
        outputPath = output.toString(),
        controlEndpointPath = controlEndpointPath,
    ),
)

private class FakeCommandReplayMediaBuffer : ReplayMediaBuffer {
    private var videoFrames = 0
    var openedDuration: Duration? = null
        private set
    var openedSettings: RecordingSettings? = null
        private set
    var closed = false
        private set
    var savedOutputPath: String? = null
        private set
    val savedOutputPaths = mutableListOf<String>()

    override suspend fun open(session: RecordingSession, duration: Duration) {
        openedDuration = duration
        openedSettings = session.settings
    }

    override suspend fun writeVideoFrame(frame: VideoFrame): ReplayBufferStats {
        videoFrames += 1
        return stats()
    }

    override suspend fun writeAudioFrame(frame: AudioFrame): ReplayBufferStats = stats()

    override suspend fun save(outputPath: String): ReplaySaveResult {
        savedOutputPath = outputPath
        savedOutputPaths += outputPath
        return ReplaySaveResult(
            output = RecordingOutput(outputPath),
            videoFrames = videoFrames.toLong(),
            audioFrames = 0,
            duration = 1.seconds,
            droppedFrames = 2,
        )
    }

    override suspend fun close() {
        closed = true
    }

    private fun stats() = ReplayBufferStats(
        videoFrameCount = videoFrames,
        audioFrameCount = 0,
        retainedDuration = 1.seconds,
        storagePolicy = ReplayStoragePolicy.DiskSegments,
        droppedVideoFrameCount = 2,
    )
}

private data object ReplayTestCaptureSourceRepository : CaptureSourceRepository {
    override suspend fun listSources(request: CaptureSourceRequest): List<CaptureSource> = listOf(
        CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen"),
    )
}

private data object ReplayTestAudioSourceRepository : AudioSourceRepository {
    override suspend fun listAudioSources(request: AudioSourceRequest) = emptyList<io.aequicor.capture.core.AudioSource>()
}

private data object ReplayOneFrameVideoAdapter : VideoCaptureAdapter {
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

private data object ReplayEmptyAudioAdapter : AudioCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<AudioFrame> = emptyFlow()
}

private data object ReplayDenyingPermissionGateway : PermissionGateway {
    override suspend fun check(required: Set<CapturePermission>): PermissionReport = denied(required)

    override suspend fun request(required: Set<CapturePermission>): PermissionReport = denied(required)

    private fun denied(required: Set<CapturePermission>) = PermissionReport(
        required.associateWith { PermissionStatus.Denied("Denied for test.") },
    )
}
