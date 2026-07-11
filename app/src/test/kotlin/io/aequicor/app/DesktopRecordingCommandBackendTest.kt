package io.aequicor.app

import io.aequicor.capture.core.AudioCaptureAdapter
import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.AudioSampleFormat
import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.MediaTimestamp
import io.aequicor.capture.core.PixelFormat
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.VideoCaptureAdapter
import io.aequicor.capture.core.VideoFrame
import io.aequicor.capture.platform.AudioSourceRepository
import io.aequicor.capture.platform.AudioSourceRequest
import io.aequicor.capture.platform.CaptureSourceRepository
import io.aequicor.capture.platform.CaptureSourceRequest
import io.aequicor.capture.platform.CapturePermission
import io.aequicor.capture.platform.PermissionGateway
import io.aequicor.capture.platform.PermissionReport
import io.aequicor.capture.platform.PermissionStatus
import io.aequicor.cli.CliCommand
import io.aequicor.cli.RecordOptions
import io.aequicor.cli.RecordTarget
import io.aequicor.cli.RecordingControlAction
import io.aequicor.cli.RecordingControlCommandResult
import io.aequicor.cli.RecordingCommandResult
import io.aequicor.settings.AudioSettings
import io.aequicor.settings.AudioSourceSettings
import io.aequicor.settings.AudioSourceType
import io.aequicor.settings.MissionRecorderSettings
import io.aequicor.settings.MissionRecorderSettingsFactory
import io.aequicor.settings.MissionRecorderSettingsStore
import io.aequicor.settings.OutputSettings
import io.aequicor.settings.VideoSettings
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopRecordingCommandBackendTest {
    @Test
    fun directOverwriteFlagReplacesExistingArtifact() = runTest {
        val temp = Files.createTempDirectory("mission-recorder-cli-overwrite-test")
        val output = temp.resolve("recording.mrec")
        Files.createDirectories(output)
        val marker = output.resolve("old.txt")
        Files.writeString(marker, "old")
        val backend = DesktopRecordingCommandBackend(
            captureSourceRepository = StaticCaptureSourceRepository(
                listOf(CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")),
            ),
            videoCaptureAdapter = OneFrameVideoCaptureAdapter,
        )

        val result = backend.record(
            CliCommand.Record(
                target = RecordTarget.Screen,
                options = RecordOptions(
                    outputPath = output.toString(),
                    duration = "1ms",
                    overwriteOutput = true,
                ),
            ),
        )

        assertIs<RecordingCommandResult.Completed>(result)
        assertFalse(marker.exists())
        assertTrue(output.resolve("recording.json").exists())
    }

    @Test
    fun rejectsDeniedPermissionBeforeOpeningCaptureOrCreatingOutput() = runTest {
        val temp = Files.createTempDirectory("mission-recorder-permission-test")
        val output = temp.resolve("recording.mrec")
        val permissions = DenyingPermissionGateway()
        val backend = DesktopRecordingCommandBackend(
            captureSourceRepository = StaticCaptureSourceRepository(
                listOf(CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")),
            ),
            videoCaptureAdapter = OneFrameVideoCaptureAdapter,
            permissionGateway = permissions,
        )

        val result = backend.record(
            CliCommand.Record(
                target = RecordTarget.Screen,
                options = RecordOptions(outputPath = output.toString(), duration = "1ms"),
            ),
        )

        val rejected = assertIs<RecordingCommandResult.Rejected>(result)
        assertTrue(rejected.message.contains("screen recording"))
        assertEquals(setOf(CapturePermission.ScreenRecording), permissions.lastRequested)
        assertFalse(output.exists())
    }

    @Test
    fun recordsWithoutDurationUntilExplicitStopRequest() = runTest {
        val temp = Files.createTempDirectory("mission-recorder-stop-request-test")
        val output = temp.resolve("recording.mrec")
        val backend = DesktopRecordingCommandBackend(
            captureSourceRepository = StaticCaptureSourceRepository(
                listOf(CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")),
            ),
            videoCaptureAdapter = OneFrameVideoCaptureAdapter,
        )

        val recording = async {
            backend.record(
                CliCommand.Record(
                    target = RecordTarget.Screen,
                    options = RecordOptions(
                        outputPath = output.toString(),
                        captureCursor = true,
                    ),
                ),
            )
        }
        runCurrent()

        assertFalse(recording.isCompleted)

        val stopResult = backend.requestStop()
        val recordingResult = recording.await()

        val completed = assertIs<RecordingCommandResult.Completed>(recordingResult)
        assertEquals(completed, stopResult)
        assertEquals(1, completed.videoFrames)
        assertTrue(output.resolve("frames").resolve("frame-000001.png").exists())
        assertNull(backend.requestStop())
    }

    @Test
    fun exposesOptInLocalControlEndpointForPauseResumeStatusAndStop() = runTest {
        val temp = Files.createTempDirectory("mission-recorder-control-test")
        val output = temp.resolve("recording.mrec")
        val endpoint = temp.resolve("control.json")
        val backend = DesktopRecordingCommandBackend(
            captureSourceRepository = StaticCaptureSourceRepository(
                listOf(CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")),
            ),
            videoCaptureAdapter = OneFrameVideoCaptureAdapter,
        )
        val controlBackend = LocalRecordingControlCommandBackend()
        val recording = async {
            backend.record(
                CliCommand.Record(
                    target = RecordTarget.Screen,
                    options = RecordOptions(
                        outputPath = output.toString(),
                        controlEndpointPath = endpoint.toString(),
                    ),
                ),
            )
        }
        runCurrent()

        if (recording.isCompleted) {
            error("Recording completed before control endpoint became available: ${recording.await()}")
        }
        assertTrue(endpoint.exists())
        val status = controlBackend.control(
            CliCommand.Control(RecordingControlAction.Status, endpoint.toString(), json = false),
        )
        assertEquals("recording", assertIs<RecordingControlCommandResult.Completed>(status).status.state)
        val save = controlBackend.control(
            CliCommand.Control(
                action = RecordingControlAction.Save,
                endpointPath = endpoint.toString(),
                outputPath = temp.resolve("snapshot.mp4").toString(),
                json = false,
            ),
        )
        assertTrue(assertIs<RecordingControlCommandResult.Rejected>(save).message.contains("replay buffers"))
        val paused = controlBackend.control(
            CliCommand.Control(RecordingControlAction.Pause, endpoint.toString(), json = false),
        )
        assertEquals("paused", assertIs<RecordingControlCommandResult.Completed>(paused).status.state)
        val resumed = controlBackend.control(
            CliCommand.Control(RecordingControlAction.Resume, endpoint.toString(), json = false),
        )
        assertEquals("recording", assertIs<RecordingControlCommandResult.Completed>(resumed).status.state)
        val stopped = controlBackend.control(
            CliCommand.Control(RecordingControlAction.Stop, endpoint.toString(), json = false),
        )
        assertEquals("Stop requested.", assertIs<RecordingControlCommandResult.Completed>(stopped).message)

        runCurrent()
        assertIs<RecordingCommandResult.Completed>(recording.await())
        assertFalse(endpoint.exists())
    }

    @Test
    fun rejectsExistingControlDescriptorWithoutOverwritingItOrOpeningOutput() = runTest {
        val temp = Files.createTempDirectory("mission-recorder-control-collision-test")
        val output = temp.resolve("recording.mrec")
        val endpoint = temp.resolve("control.json")
        Files.writeString(endpoint, "owned-by-another-process")
        val backend = DesktopRecordingCommandBackend(
            captureSourceRepository = StaticCaptureSourceRepository(
                listOf(CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")),
            ),
            videoCaptureAdapter = OneFrameVideoCaptureAdapter,
        )

        val result = backend.record(
            CliCommand.Record(
                target = RecordTarget.Screen,
                options = RecordOptions(
                    outputPath = output.toString(),
                    duration = "1ms",
                    controlEndpointPath = endpoint.toString(),
                ),
            ),
        )

        assertTrue(assertIs<RecordingCommandResult.Rejected>(result).message.contains("already exists"))
        assertEquals("owned-by-another-process", Files.readString(endpoint))
        assertFalse(output.exists())
    }

    @Test
    fun rejectsNonPositiveDurationBeforeOpeningCapture() = runTest {
        val temp = Files.createTempDirectory("mission-recorder-duration-test")
        val output = temp.resolve("recording.mrec")
        val backend = DesktopRecordingCommandBackend(
            captureSourceRepository = StaticCaptureSourceRepository(
                listOf(CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")),
            ),
            videoCaptureAdapter = OneFrameVideoCaptureAdapter,
        )

        val result = backend.record(
            CliCommand.Record(
                target = RecordTarget.Screen,
                options = RecordOptions(outputPath = output.toString(), duration = "0s"),
            ),
        )

        val rejected = assertIs<RecordingCommandResult.Rejected>(result)
        assertTrue(rejected.message.contains("Invalid --duration"))
        assertFalse(output.exists())
    }

    @Test
    fun cancellationReleasesActiveRecordingAndRemovesPartialOutput() = runTest {
        val temp = Files.createTempDirectory("mission-recorder-cancellation-test")
        val output = temp.resolve("recording.mrec")
        val endpoint = temp.resolve("control.json")
        val backend = DesktopRecordingCommandBackend(
            captureSourceRepository = StaticCaptureSourceRepository(
                listOf(CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")),
            ),
            videoCaptureAdapter = OneFrameVideoCaptureAdapter,
        )
        val recording = async {
            backend.record(
                CliCommand.Record(
                    target = RecordTarget.Screen,
                    options = RecordOptions(
                        outputPath = output.toString(),
                        controlEndpointPath = endpoint.toString(),
                    ),
                ),
            )
        }
        runCurrent()

        recording.cancelAndJoin()

        assertFalse(output.exists())
        assertFalse(endpoint.exists())
        assertNull(backend.requestStop())
    }

    @Test
    fun rejectsControlEndpointInsideRecordingOutputPath() = runTest {
        val temp = Files.createTempDirectory("mission-recorder-control-path-test")
        val output = temp.resolve("recording.mrec")
        val endpoint = output.resolve("control.json")
        val backend = DesktopRecordingCommandBackend(
            captureSourceRepository = StaticCaptureSourceRepository(
                listOf(CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")),
            ),
            videoCaptureAdapter = OneFrameVideoCaptureAdapter,
        )

        val result = backend.record(
            CliCommand.Record(
                target = RecordTarget.Screen,
                options = RecordOptions(
                    outputPath = output.toString(),
                    duration = "1ms",
                    controlEndpointPath = endpoint.toString(),
                ),
            ),
        )

        assertEquals(
            "Control endpoint must be outside the recording output path.",
            assertIs<RecordingCommandResult.Rejected>(result).message,
        )
        assertFalse(output.exists())
    }

    @Test
    fun recordsScreenThroughControllerAndFrameSequenceEncoder() = runTest {
        val temp = Files.createTempDirectory("mission-recorder-app-test")
        val output = temp.resolve("recording.mrec")
        val backend = DesktopRecordingCommandBackend(
            captureSourceRepository = StaticCaptureSourceRepository(
                listOf(
                    CaptureSource.Screen(
                        id = CaptureSourceId("screen:test"),
                        displayName = "Test screen",
                    ),
                ),
            ),
            videoCaptureAdapter = OneFrameVideoCaptureAdapter,
        )

        val result = backend.record(
            CliCommand.Record(
                target = RecordTarget.Screen,
                options = RecordOptions(
                    outputPath = output.toString(),
                    captureCursor = false,
                    duration = "1ms",
                ),
            ),
        )

        val completed = assertIs<RecordingCommandResult.Completed>(result)
        assertEquals(output.toString(), completed.outputPath)
        assertEquals(1, completed.videoFrames)
        assertTrue(output.resolve("frames").resolve("frame-000001.png").exists())
    }

    @Test
    fun recordsUsingSettingsProfile() = runTest {
        val temp = Files.createTempDirectory("mission-recorder-profile-test")
        val settingsPath = temp.resolve("settings.json")
        val outputDirectory = temp.resolve("recordings")
        val expectedOutput = outputDirectory.resolve("profile-output.mrec")
        val audioPayload = byteArrayOf(0, 0, 1, 0, 2, 0, 3, 0)
        val microphone = AudioSource.Microphone(
            id = AudioSourceId("mic:profile"),
            displayName = "Profile microphone",
            sampleRate = 48_000,
            channelCount = 2,
        )
        val defaultSettings = MissionRecorderSettingsFactory.defaultLocal()
        val profile = defaultSettings.profiles.single().copy(
            output = OutputSettings(
                directory = outputDirectory.toString(),
                fileNamePattern = "profile-output.mrec",
                overwrite = true,
            ),
            video = VideoSettings(frameRate = 1, captureCursor = false),
            audio = AudioSettings(
                sources = listOf(
                    AudioSourceSettings(
                        type = AudioSourceType.Microphone,
                        id = microphone.id.value,
                        displayName = microphone.displayName,
                        sampleRate = microphone.sampleRate,
                        channelCount = microphone.channelCount,
                    ),
                ),
            ),
        )
        MissionRecorderSettingsStore(settingsPath).save(
            MissionRecorderSettings(
                defaultProfileId = profile.id,
                profiles = listOf(profile),
            ),
        )
        Files.createDirectories(expectedOutput)
        val oldMarker = expectedOutput.resolve("old.txt")
        Files.writeString(oldMarker, "old")
        val backend = DesktopRecordingCommandBackend(
            captureSourceRepository = StaticCaptureSourceRepository(
                listOf(
                    CaptureSource.Screen(
                        id = CaptureSourceId("screen:test"),
                        displayName = "Test screen",
                    ),
                ),
            ),
            videoCaptureAdapter = OneFrameVideoCaptureAdapter,
            audioSourceRepository = StaticAudioSourceRepository(listOf(microphone)),
            audioCaptureAdapter = OneFrameAudioCaptureAdapter(audioPayload),
            recordingProfileLoader = LocalRecordingProfileLoader(),
        )

        val result = backend.record(
            CliCommand.Record(
                target = RecordTarget.Profile,
                options = RecordOptions(
                    settingsPath = settingsPath.toString(),
                    duration = "1ms",
                ),
            ),
        )

        val completed = assertIs<RecordingCommandResult.Completed>(result)
        assertEquals(expectedOutput.toString(), completed.outputPath)
        assertEquals(1, completed.videoFrames)
        assertEquals(1, completed.audioFrames)
        assertTrue(expectedOutput.resolve("frames").resolve("frame-000001.png").exists())
        assertFalse(oldMarker.exists())
        assertContentEquals(
            audioPayload,
            Files.readAllBytes(expectedOutput.resolve("audio").resolve("audio-000001.pcm")),
        )
    }

    @Test
    fun recordsSelectedMicrophoneIntoPcmChunk() = runTest {
        val temp = Files.createTempDirectory("mission-recorder-microphone-test")
        val output = temp.resolve("recording.mrec")
        val microphone = AudioSource.Microphone(
            id = AudioSourceId("mic:test"),
            displayName = "Test microphone",
            sampleRate = 48_000,
            channelCount = 2,
        )
        val audioPayload = byteArrayOf(0, 0, 1, 0, 2, 0, 3, 0)
        val audioCapture = OneFrameAudioCaptureAdapter(audioPayload)
        val backend = DesktopRecordingCommandBackend(
            captureSourceRepository = StaticCaptureSourceRepository(
                listOf(
                    CaptureSource.Screen(
                        id = CaptureSourceId("screen:test"),
                        displayName = "Test screen",
                    ),
                ),
            ),
            videoCaptureAdapter = OneFrameVideoCaptureAdapter,
            audioSourceRepository = StaticAudioSourceRepository(listOf(microphone)),
            audioCaptureAdapter = audioCapture,
        )

        val result = backend.record(
            CliCommand.Record(
                target = RecordTarget.Screen,
                options = RecordOptions(
                    outputPath = output.toString(),
                    captureCursor = false,
                    microphone = "default",
                    microphoneGainPercent = 75,
                    duration = "1ms",
                ),
            ),
        )

        val completed = assertIs<RecordingCommandResult.Completed>(result)
        assertEquals(1, completed.videoFrames)
        assertEquals(1, completed.audioFrames)
        assertEquals(0.75, audioCapture.lastSource?.gain)
        assertContentEquals(
            audioPayload,
            Files.readAllBytes(output.resolve("audio").resolve("audio-000001.pcm")),
        )
        assertTrue(Files.readString(output.resolve("recording.json")).contains(""""sourceId": "mic:test"""))
    }

    @Test
    fun recordsSelectedSystemLoopbackIntoPcmChunk() = runTest {
        val temp = Files.createTempDirectory("mission-recorder-system-audio-test")
        val output = temp.resolve("recording.mrec")
        val loopback = AudioSource.SystemLoopback(
            id = AudioSourceId("wasapi:loopback:default"),
            displayName = "System audio",
            sampleRate = 48_000,
            channelCount = 2,
        )
        val audioPayload = byteArrayOf(0, 0, 1, 0, 2, 0, 3, 0)
        val audioCapture = OneFrameAudioCaptureAdapter(audioPayload)
        val backend = DesktopRecordingCommandBackend(
            captureSourceRepository = StaticCaptureSourceRepository(
                listOf(CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")),
            ),
            videoCaptureAdapter = OneFrameVideoCaptureAdapter,
            audioSourceRepository = StaticAudioSourceRepository(listOf(loopback)),
            audioCaptureAdapter = audioCapture,
        )

        val result = backend.record(
            CliCommand.Record(
                target = RecordTarget.Screen,
                options = RecordOptions(
                    outputPath = output.toString(),
                    captureCursor = false,
                    systemAudio = true,
                    systemAudioGainPercent = 40,
                    duration = "1ms",
                ),
            ),
        )

        val completed = assertIs<RecordingCommandResult.Completed>(result)
        assertEquals(1, completed.audioFrames)
        assertEquals(0.4, audioCapture.lastSource?.gain)
        assertContentEquals(
            audioPayload,
            Files.readAllBytes(output.resolve("audio").resolve("audio-000001.pcm")),
        )
        assertTrue(
            Files.readString(output.resolve("recording.json"))
                .contains(""""sourceId": "wasapi:loopback:default"""),
        )
    }

    @Test
    fun rejectsUnavailableMicrophoneBeforeOpeningCapture() = runTest {
        val temp = Files.createTempDirectory("mission-recorder-missing-microphone-test")
        val output = temp.resolve("recording.mrec")
        val backend = DesktopRecordingCommandBackend(
            captureSourceRepository = StaticCaptureSourceRepository(
                listOf(
                    CaptureSource.Screen(
                        id = CaptureSourceId("screen:test"),
                        displayName = "Test screen",
                    ),
                ),
            ),
            videoCaptureAdapter = OneFrameVideoCaptureAdapter,
            audioSourceRepository = StaticAudioSourceRepository(emptyList()),
        )

        val result = backend.record(
            CliCommand.Record(
                target = RecordTarget.Screen,
                options = RecordOptions(
                    outputPath = output.toString(),
                    captureCursor = false,
                    microphone = "mic:missing",
                    duration = "1ms",
                ),
            ),
        )

        val rejected = assertIs<RecordingCommandResult.Rejected>(result)
        assertTrue(rejected.message.contains("No microphones are available"))
        assertTrue(!output.exists())
    }
}

private class DenyingPermissionGateway : PermissionGateway {
    var lastRequested: Set<CapturePermission> = emptySet()
        private set

    override suspend fun check(required: Set<CapturePermission>): PermissionReport = denied(required)

    override suspend fun request(required: Set<CapturePermission>): PermissionReport {
        lastRequested = required
        return denied(required)
    }

    private fun denied(required: Set<CapturePermission>) = PermissionReport(
        required.associateWith { PermissionStatus.Denied("Denied for test.") },
    )
}

private class StaticCaptureSourceRepository(
    private val sources: List<CaptureSource>,
) : CaptureSourceRepository {
    override suspend fun listSources(request: CaptureSourceRequest): List<CaptureSource> = sources
}

private class StaticAudioSourceRepository(
    private val sources: List<AudioSource>,
) : AudioSourceRepository {
    override suspend fun listAudioSources(request: AudioSourceRequest): List<AudioSource> = sources
}

private data object OneFrameVideoCaptureAdapter : VideoCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<VideoFrame> = flow {
        emit(
            VideoFrame(
                timestamp = MediaTimestamp(0),
                width = 1,
                height = 1,
                pixelFormat = PixelFormat.Rgba8888,
                strideBytes = 4,
                sourceId = settings.captureSource.id,
                pixelData = byteArrayOf(0, 255.toByte(), 0, 255.toByte()),
            ),
        )
        awaitCancellation()
    }
}

private class OneFrameAudioCaptureAdapter(
    private val payload: ByteArray,
) : AudioCaptureAdapter {
    var lastSource: AudioSource? = null
        private set

    override fun frames(settings: RecordingSettings): Flow<AudioFrame> = flow {
        val source = settings.audioSources.single()
        lastSource = source
        val sampleRate = when (source) {
            is AudioSource.Microphone -> source.sampleRate
            is AudioSource.SystemLoopback -> source.sampleRate
        }
        val channelCount = when (source) {
            is AudioSource.Microphone -> source.channelCount
            is AudioSource.SystemLoopback -> source.channelCount
        }
        emit(
            AudioFrame(
                timestamp = MediaTimestamp(0),
                sampleRate = sampleRate,
                channelCount = channelCount,
                sampleCount = payload.size / (channelCount * AudioSampleFormat.PcmS16Le.bytesPerSample),
                sourceId = source.id,
                sampleFormat = AudioSampleFormat.PcmS16Le,
                audioData = payload,
            ),
        )
        awaitCancellation()
    }
}
