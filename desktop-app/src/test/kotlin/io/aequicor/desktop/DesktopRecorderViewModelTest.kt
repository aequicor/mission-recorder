package io.aequicor.desktop

import io.aequicor.audio.core.AudioLevels
import io.aequicor.audio.core.MutableAudioMuteController
import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.CancelRecordingResult
import io.aequicor.capture.core.CaptureRegion
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.CoordinateSpace
import io.aequicor.capture.core.EncoderSettings
import io.aequicor.capture.core.PauseRecordingResult
import io.aequicor.capture.core.PixelFormat
import io.aequicor.capture.core.MediaTimestamp
import io.aequicor.capture.core.MarkImportantFrameResult
import io.aequicor.capture.core.RecordingMetrics
import io.aequicor.capture.core.RecordingSession
import io.aequicor.capture.core.RecordingSessionId
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.RecordingState
import io.aequicor.capture.core.ResumeRecordingResult
import io.aequicor.capture.core.StartRecordingResult
import io.aequicor.capture.core.StopRecordingResult
import io.aequicor.capture.core.VideoFrame
import io.aequicor.capture.platform.AudioSourceRepository
import io.aequicor.capture.platform.AudioSourceRequest
import io.aequicor.capture.platform.CaptureSourceRepository
import io.aequicor.capture.platform.CaptureSourceRequest
import io.aequicor.capture.platform.CaptureRegionSelection
import io.aequicor.capture.platform.CaptureRegionSelector
import io.aequicor.capture.platform.CapturePermission
import io.aequicor.capture.platform.PermissionGateway
import io.aequicor.capture.platform.PermissionReport
import io.aequicor.capture.platform.PermissionStatus
import io.aequicor.compose.ui.RecorderStatus
import io.aequicor.compose.ui.PreviewUiStatus
import io.aequicor.compose.ui.RecorderUiAction
import io.aequicor.compose.ui.ReplayUiStatus
import io.aequicor.compose.ui.StoryboardMode
import io.aequicor.replay.ReplayBufferStats
import io.aequicor.replay.ReplayCaptureState
import io.aequicor.replay.ReplaySaveResult
import io.aequicor.replay.ReplayStoragePolicy
import io.aequicor.replay.SaveReplayResult
import io.aequicor.replay.StartReplayResult
import io.aequicor.replay.StopReplayResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopRecorderViewModelTest {
    @Test
    fun completesShutdownWhenWorkerScopeWasAlreadyCancelled() = runTest {
        val cancelledJob = Job().apply { cancel() }
        val viewModel = DesktopRecorderViewModel(
            scope = CoroutineScope(coroutineContext + cancelledJob),
            captureSourceRepository = StaticCaptureSourceRepository(emptyList()),
            audioSourceRepository = StaticAudioSourceRepository(emptyList()),
            recordingEngine = FakeDesktopRecordingEngine(),
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = FakeDesktopStoryboardExporter(),
            nextOutputPath = { "recordings/test.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
        )
        var completions = 0

        viewModel.shutdown { completions += 1 }
        runCurrent()

        assertEquals(1, completions)
    }

    @Test
    fun opensFolderForCurrentOutputPathAndReportsPlatformFailure() = runTest {
        val opener = FakeRecordingsDirectoryOpener()
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(emptyList()),
            audioSourceRepository = StaticAudioSourceRepository(emptyList()),
            recordingEngine = FakeDesktopRecordingEngine(),
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = FakeDesktopStoryboardExporter(),
            nextOutputPath = { "recordings/test.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
            recordingsDirectoryOpener = opener,
        )
        runCurrent()

        viewModel.onAction(RecorderUiAction.OpenRecordingsFolder)
        runCurrent()

        assertEquals("recordings/test.mp4", opener.lastOutputPath)
        assertEquals("Folder opener failed for test.", viewModel.state.value.errorMessage)
    }

    @Test
    fun previewsSelectedSourceAndStopsPreviewBeforeRecording() = runTest {
        val screen = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")
        val preview = FakeDesktopPreviewEngine()
        val recording = FakeDesktopRecordingEngine()
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(listOf(screen)),
            audioSourceRepository = StaticAudioSourceRepository(emptyList()),
            recordingEngine = recording,
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = FakeDesktopStoryboardExporter(),
            previewEngine = preview,
            nextOutputPath = { "recordings/test.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
        )
        runCurrent()

        viewModel.onAction(RecorderUiAction.StartPreview)
        runCurrent()

        assertEquals(PreviewUiStatus.Active, viewModel.state.value.previewStatus)
        assertEquals(5, assertNotNull(preview.settings).frameRate)
        assertTrue(assertNotNull(preview.settings).audioSources.isEmpty())
        assertFalse(assertNotNull(preview.settings).captureCursor)
        assertEquals(PixelFormat.Rgba8888, assertNotNull(viewModel.previewFrame.value).pixelFormat)
        assertEquals(
            listOf(255.toByte(), 0.toByte(), 0.toByte(), 255.toByte()),
            assertNotNull(viewModel.previewFrame.value).pixelData.take(4),
        )

        viewModel.onAction(RecorderUiAction.StartRecording)
        runCurrent()

        assertTrue(preview.cancelled)
        assertEquals(PreviewUiStatus.Idle, viewModel.state.value.previewStatus)
        assertNotNull(viewModel.previewFrame.value)
        assertTrue(assertNotNull(recording.startedSettings).captureCursor)
    }

    @Test
    fun savesActivePreviewAsScreenshot() = runTest {
        val screen = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")
        val screenshotSaver = FakeDesktopScreenshotSaver()
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(listOf(screen)),
            audioSourceRepository = StaticAudioSourceRepository(emptyList()),
            recordingEngine = FakeDesktopRecordingEngine(),
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = FakeDesktopStoryboardExporter(),
            previewEngine = FakeDesktopPreviewEngine(),
            nextOutputPath = { "recordings/test.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
            screenshotSaver = screenshotSaver,
            nextScreenshotOutputPath = { "recordings/screenshot-test.png" },
        )
        runCurrent()

        viewModel.onAction(RecorderUiAction.StartPreview)
        runCurrent()
        viewModel.onAction(RecorderUiAction.TakeScreenshot)
        runCurrent()

        assertEquals("recordings/screenshot-test.png", screenshotSaver.outputPath)
        assertEquals(PixelFormat.Rgba8888, assertNotNull(screenshotSaver.frame).pixelFormat)
        assertEquals("recordings/screenshot-test.png", viewModel.state.value.lastScreenshotPath)
        assertFalse(viewModel.state.value.isSavingScreenshot)
    }

    @Test
    fun preservesFullWidthQhdPreviewResolution() = runTest {
        val screen = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(listOf(screen)),
            audioSourceRepository = StaticAudioSourceRepository(emptyList()),
            recordingEngine = FakeDesktopRecordingEngine(),
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = FakeDesktopStoryboardExporter(),
            previewEngine = FakeDesktopPreviewEngine(width = 3440, height = 1440),
            nextOutputPath = { "recordings/test.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
        )
        runCurrent()

        viewModel.onAction(RecorderUiAction.StartPreview)
        runCurrent()

        val preview = assertNotNull(viewModel.previewFrame.value)
        assertEquals(3440, preview.width)
        assertEquals(1440, preview.height)
        assertEquals(preview.width * preview.height * 4, preview.pixelData.size)
    }

    @Test
    fun copiesBgraPreviewWithPaddedRowsBeforeReleasingCaptureFrame() = runTest {
        val screen = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")
        val width = 2
        val strideBytes = width * 4 + 8
        val pixels = ByteArray(strideBytes * 2).also { data ->
            data[0] = 10
            data[1] = 20
            data[2] = 30
            val lastSampleOffset = strideBytes + 4
            data[lastSampleOffset] = 50
            data[lastSampleOffset + 1] = 60
            data[lastSampleOffset + 2] = 70
        }
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(listOf(screen)),
            audioSourceRepository = StaticAudioSourceRepository(emptyList()),
            recordingEngine = FakeDesktopRecordingEngine(),
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = FakeDesktopStoryboardExporter(),
            previewEngine = FakeDesktopPreviewEngine(
                width = width,
                height = 2,
                pixelFormat = PixelFormat.Bgra8888,
                strideBytes = strideBytes,
                pixels = pixels,
            ),
            nextOutputPath = { "recordings/test.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
        )
        runCurrent()

        viewModel.onAction(RecorderUiAction.StartPreview)
        runCurrent()

        val preview = assertNotNull(viewModel.previewFrame.value)
        assertEquals(width, preview.width)
        assertEquals(2, preview.height)
        assertEquals(PixelFormat.Bgra8888, preview.pixelFormat)
        assertEquals(strideBytes, preview.strideBytes)
        assertTrue(preview.pixelData !== pixels)
        assertContentEquals(pixels, preview.pixelData)
    }

    @Test
    fun blocksPreviewBeforeOpeningVideoSourceWhenPermissionIsDenied() = runTest {
        val screen = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")
        val preview = FakeDesktopPreviewEngine()
        val permissions = RejectingPermissionGateway()
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(listOf(screen)),
            audioSourceRepository = StaticAudioSourceRepository(emptyList()),
            recordingEngine = FakeDesktopRecordingEngine(),
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = FakeDesktopStoryboardExporter(),
            previewEngine = preview,
            nextOutputPath = { "recordings/test.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
            permissionGateway = permissions,
        )
        runCurrent()

        viewModel.onAction(RecorderUiAction.StartPreview)
        runCurrent()

        assertEquals(PreviewUiStatus.Failed, viewModel.state.value.previewStatus)
        assertEquals(0, preview.starts)
        assertEquals(setOf(CapturePermission.ScreenRecording), permissions.lastChecked)
        assertEquals(setOf(CapturePermission.ScreenRecording), permissions.lastRequested)
    }

    @Test
    fun appliesOutputNamingPolicyAndPersistsProfileSnapshot() = runTest {
        val screen = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")
        val configuration = DesktopRecorderProfileConfiguration(
            summary = DesktopRecorderProfileSummary("default", "Default"),
            preferences = DesktopRecorderPreferences(),
            captureSource = screen,
            audioSources = emptyList(),
            outputPath = "recordings/old.mp4",
        )
        val store = SelectingProfileStore(
            selected = configuration,
            previewOutputPath = "captures/work-default-20260711.mp4",
        )
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(listOf(screen)),
            audioSourceRepository = StaticAudioSourceRepository(emptyList()),
            recordingEngine = FakeDesktopRecordingEngine(),
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = FakeDesktopStoryboardExporter(),
            nextOutputPath = { "recordings/fallback.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
            initialProfileCatalog = DesktopRecorderProfileCatalog(listOf(configuration.summary), configuration),
            profileStore = store,
        )
        runCurrent()

        viewModel.onAction(RecorderUiAction.ShowOutputNamingDialog)
        assertTrue(viewModel.state.value.showOutputNamingDialog)
        viewModel.onAction(
            RecorderUiAction.ApplyOutputNaming(
                directory = " captures ",
                fileNamePattern = " work-{profile}-{timestamp}.mp4 ",
            ),
        )
        runCurrent()

        assertEquals("captures", viewModel.state.value.outputDirectory)
        assertEquals("work-{profile}-{timestamp}.mp4", viewModel.state.value.outputFileNamePattern)
        assertEquals("captures/work-default-20260711.mp4", viewModel.state.value.outputPath)
        assertFalse(viewModel.state.value.showOutputNamingDialog)
        assertEquals(
            DesktopOutputPolicy("captures", "work-{profile}-{timestamp}.mp4"),
            store.saved.last().outputPolicy,
        )
    }

    @Test
    fun recordsSelectedSystemAudioEndpoint() = runTest {
        val screen = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")
        val speakers = AudioSource.SystemLoopback(AudioSourceId("system:speakers"), "Speakers", 48_000, 2)
        val headset = AudioSource.SystemLoopback(AudioSourceId("system:headset"), "Headset", 48_000, 2)
        val recordingEngine = FakeDesktopRecordingEngine()
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(listOf(screen)),
            audioSourceRepository = StaticAudioSourceRepository(listOf(speakers, headset)),
            recordingEngine = recordingEngine,
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = FakeDesktopStoryboardExporter(),
            nextOutputPath = { "recordings/test.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
        )
        runCurrent()

        assertEquals(listOf(speakers.id.value, headset.id.value), viewModel.state.value.systemAudioSources.map { it.id })
        viewModel.onAction(RecorderUiAction.SelectSystemAudio(headset.id.value))
        viewModel.onAction(RecorderUiAction.SetSystemAudioGainPercent(45))
        viewModel.onAction(RecorderUiAction.SetSystemAudioEnabled(true))
        viewModel.onAction(RecorderUiAction.StartRecording)
        runCurrent()

        assertEquals(headset.id.value, viewModel.state.value.selectedSystemAudioId)
        assertEquals(45, viewModel.state.value.systemAudioGainPercent)
        assertEquals(listOf(headset.copy(gain = 0.45)), recordingEngine.startedSettings?.audioSources)
    }

    @Test
    fun switchesNamedProfileAsOneCaptureConfigurationSnapshot() = runTest {
        val screen = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")
        val monitor = CaptureSource.Monitor(CaptureSourceId("monitor:test"), "Test monitor", index = 1)
        val microphone = AudioSource.Microphone(AudioSourceId("mic:test"), "Test mic", 48_000, 2, gain = 0.75)
        val systemAudio = AudioSource.SystemLoopback(
            AudioSourceId("system:test"),
            "System",
            48_000,
            2,
            gain = 0.4,
        )
        val defaultConfiguration = DesktopRecorderProfileConfiguration(
            summary = DesktopRecorderProfileSummary("default", "Default"),
            preferences = DesktopRecorderPreferences(),
            captureSource = screen,
            audioSources = emptyList(),
            outputPath = "recordings/default.mp4",
        )
        val gamingConfiguration = DesktopRecorderProfileConfiguration(
            summary = DesktopRecorderProfileSummary("gaming", "Gaming"),
            preferences = DesktopRecorderPreferences(
                frameRate = 60,
                captureCursor = false,
                replayDurationMinutes = 12,
                storyboardMode = StoryboardMode.ContactSheet,
                encoderSettings = EncoderSettings(videoBitrateBitsPerSecond = 20_000_000),
            ),
            captureSource = monitor,
            audioSources = listOf(microphone, systemAudio),
            outputPath = "recordings/gaming.mp4",
            overwriteOutput = true,
        )
        val initialCatalog = DesktopRecorderProfileCatalog(
            profiles = listOf(defaultConfiguration.summary, gamingConfiguration.summary),
            selected = defaultConfiguration,
        )
        val profileStore = SelectingProfileStore(
            selected = gamingConfiguration,
            nextOutputPath = "recordings/gaming-next.mp4",
        )
        val recordingEngine = FakeDesktopRecordingEngine()
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(listOf(screen, monitor)),
            audioSourceRepository = StaticAudioSourceRepository(listOf(microphone, systemAudio)),
            recordingEngine = recordingEngine,
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = FakeDesktopStoryboardExporter(),
            nextOutputPath = { "recordings/fallback.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
            initialProfileCatalog = initialCatalog,
            profileStore = profileStore,
        )
        runCurrent()

        viewModel.onAction(RecorderUiAction.SelectProfile("gaming"))
        runCurrent()

        val state = viewModel.state.value
        assertEquals("gaming", state.selectedProfileId)
        assertEquals(monitor.id.value, state.selectedSourceId)
        assertEquals(microphone.id.value, state.selectedMicrophoneId)
        assertEquals(systemAudio.id.value, state.selectedSystemAudioId)
        assertEquals(75, state.microphoneGainPercent)
        assertEquals(40, state.systemAudioGainPercent)
        assertTrue(state.systemAudioEnabled)
        assertEquals(60, state.frameRate)
        assertFalse(state.captureCursor)
        assertEquals(20, state.videoBitrateMbps)
        assertEquals(12, state.replayDurationMinutes)
        assertEquals(StoryboardMode.ContactSheet, state.storyboardMode)
        assertEquals("recordings/gaming.mp4", state.outputPath)
        assertTrue(state.overwriteOutput)
        assertEquals("default", profileStore.saved.single().profileId)

        viewModel.onAction(RecorderUiAction.StartRecording)
        runCurrent()
        assertTrue(requireNotNull(recordingEngine.startedSettings).overwriteOutput)
        assertEquals(listOf(microphone, systemAudio), recordingEngine.startedSettings?.audioSources)
        viewModel.onAction(RecorderUiAction.StopRecording)
        runCurrent()

        assertEquals("recordings/gaming.mp4", viewModel.state.value.lastOutputPath)
        assertEquals("recordings/gaming-next.mp4", viewModel.state.value.outputPath)
    }

    @Test
    fun blocksRecordingBeforeEngineStartWhenSelectedPermissionsAreDenied() = runTest {
        val screen = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")
        val microphone = AudioSource.Microphone(AudioSourceId("mic:test"), "Test mic", 48_000, 2)
        val systemAudio = AudioSource.SystemLoopback(AudioSourceId("system:test"), "System", 48_000, 2)
        val engine = FakeDesktopRecordingEngine()
        val permissions = RejectingPermissionGateway()
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(listOf(screen)),
            audioSourceRepository = StaticAudioSourceRepository(listOf(microphone, systemAudio)),
            recordingEngine = engine,
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = FakeDesktopStoryboardExporter(),
            nextOutputPath = { "recordings/test.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
            permissionGateway = permissions,
        )
        runCurrent()
        viewModel.onAction(RecorderUiAction.SelectMicrophone(microphone.id.value))
        viewModel.onAction(RecorderUiAction.SetSystemAudioEnabled(true))

        viewModel.onAction(RecorderUiAction.StartRecording)
        runCurrent()

        assertEquals(null, engine.startedSettings)
        assertEquals(RecorderStatus.Failed, viewModel.state.value.status)
        assertEquals(CapturePermission.entries.toSet(), permissions.lastChecked)
        assertEquals(CapturePermission.entries.toSet(), permissions.lastRequested)
        assertTrue(viewModel.state.value.errorMessage.orEmpty().contains("Required permissions"))
    }

    @Test
    fun blocksReplayBeforeEngineStartWhenScreenPermissionIsDenied() = runTest {
        val screen = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")
        val replayEngine = FakeDesktopReplayEngine()
        val permissions = RejectingPermissionGateway()
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(listOf(screen)),
            audioSourceRepository = StaticAudioSourceRepository(emptyList()),
            recordingEngine = FakeDesktopRecordingEngine(),
            replayEngine = replayEngine,
            storyboardExporter = FakeDesktopStoryboardExporter(),
            nextOutputPath = { "recordings/test.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
            permissionGateway = permissions,
        )
        runCurrent()

        viewModel.onAction(RecorderUiAction.StartReplayBuffer)
        runCurrent()

        assertEquals(null, replayEngine.startedSettings)
        assertEquals(ReplayUiStatus.Failed, viewModel.state.value.replayStatus)
        assertEquals(setOf(CapturePermission.ScreenRecording), permissions.lastChecked)
    }

    @Test
    fun restoresAndPersistsRecorderPreferencesSequentially() = runTest {
        val persisted = mutableListOf<DesktopRecorderPreferences>()
        val initial = DesktopRecorderPreferences(
            frameRate = 15,
            captureCursor = false,
            showInputOverlay = true,
            replayDurationMinutes = 8,
            storyboardMode = StoryboardMode.ContactSheet,
            encoderSettings = EncoderSettings(
                videoBitrateBitsPerSecond = 12_000_000,
                audioBitrateBitsPerSecond = 128_000,
                keyframeIntervalSeconds = 4,
            ),
        )
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(emptyList()),
            audioSourceRepository = StaticAudioSourceRepository(emptyList()),
            recordingEngine = FakeDesktopRecordingEngine(),
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = FakeDesktopStoryboardExporter(),
            nextOutputPath = { "recordings/test.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
            initialPreferences = initial,
            preferencesWriter = DesktopRecorderPreferencesWriter { preferences -> persisted += preferences },
        )
        runCurrent()

        assertEquals(15, viewModel.state.value.frameRate)
        assertFalse(viewModel.state.value.captureCursor)
        assertTrue(viewModel.state.value.showInputOverlay)
        assertEquals(8, viewModel.state.value.replayDurationMinutes)
        assertEquals(StoryboardMode.ContactSheet, viewModel.state.value.storyboardMode)
        assertEquals(12, viewModel.state.value.videoBitrateMbps)

        viewModel.onAction(RecorderUiAction.SetFrameRate(60))
        viewModel.onAction(RecorderUiAction.SetCaptureCursor(true))
        viewModel.onAction(RecorderUiAction.SetShowInputOverlay(false))
        viewModel.onAction(RecorderUiAction.SetReplayDurationMinutes(11))
        viewModel.onAction(RecorderUiAction.SetStoryboardMode(StoryboardMode.SeparatePngFiles))
        viewModel.onAction(RecorderUiAction.SetVideoBitrateMbps(18))
        runCurrent()

        assertEquals(
            DesktopRecorderPreferences(
                frameRate = 60,
                captureCursor = true,
                showInputOverlay = false,
                replayDurationMinutes = 11,
                storyboardMode = StoryboardMode.SeparatePngFiles,
                encoderSettings = initial.encoderSettings.copy(videoBitrateBitsPerSecond = 18_000_000),
            ),
            persisted.last(),
        )
    }

    @Test
    fun pausesResumesAndStopsTheSameRecordingSession() = runTest {
        val screen = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")
        val engine = FakeDesktopRecordingEngine()
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(listOf(screen)),
            audioSourceRepository = StaticAudioSourceRepository(emptyList()),
            recordingEngine = engine,
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = FakeDesktopStoryboardExporter(),
            nextOutputPath = { "recordings/test.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
            initialPreferences = DesktopRecorderPreferences(
                encoderSettings = EncoderSettings(
                    videoBitrateBitsPerSecond = 14_000_000,
                    audioBitrateBitsPerSecond = 144_000,
                ),
            ),
        )
        runCurrent()

        viewModel.onAction(RecorderUiAction.SetCaptureCursor(false))
        viewModel.onAction(RecorderUiAction.SetShowInputOverlay(true))
        viewModel.onAction(RecorderUiAction.StartRecording)
        runCurrent()
        assertEquals(RecorderStatus.Recording, viewModel.state.value.status)
        val startedSettings = assertNotNull(engine.startedSettings)
        assertFalse(startedSettings.captureCursor)
        assertTrue(startedSettings.showInputOverlay)
        assertEquals(14_000_000, startedSettings.encoder.videoBitrateBitsPerSecond)
        assertEquals(144_000, startedSettings.encoder.audioBitrateBitsPerSecond)

        viewModel.onAction(RecorderUiAction.MarkImportantFrame)
        runCurrent()
        assertEquals(1, engine.markImportantFrameCalls)
        assertEquals(1L, viewModel.state.value.importantFrameCaptureSequence)

        viewModel.onAction(RecorderUiAction.PauseRecording)
        runCurrent()
        assertEquals(RecorderStatus.Paused, viewModel.state.value.status)
        assertTrue(viewModel.state.value.hasActiveRecording)
        assertEquals(1, engine.pauseCalls)

        viewModel.onAction(RecorderUiAction.ResumeRecording)
        runCurrent()
        assertEquals(RecorderStatus.Recording, viewModel.state.value.status)
        assertEquals(1, engine.resumeCalls)

        viewModel.onAction(RecorderUiAction.PauseRecording)
        runCurrent()
        viewModel.onAction(RecorderUiAction.StopRecording)
        runCurrent()

        assertTrue(engine.stopCalled)
        assertEquals(RecorderStatus.Completed, viewModel.state.value.status)
    }

    @Test
    fun appliesRuntimeMuteToSelectedAudioSourcesAndClearsStaleState() = runTest {
        val screen = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")
        val microphone = AudioSource.Microphone(
            id = AudioSourceId("mic:test"),
            displayName = "Test microphone",
            sampleRate = 48_000,
            channelCount = 2,
        )
        val loopback = AudioSource.SystemLoopback(
            id = AudioSourceId("wasapi:loopback:default"),
            displayName = "System audio",
            sampleRate = 48_000,
            channelCount = 2,
        )
        val muteController = MutableAudioMuteController()
        val engine = FakeDesktopRecordingEngine()
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(listOf(screen)),
            audioSourceRepository = StaticAudioSourceRepository(listOf(microphone, loopback)),
            recordingEngine = engine,
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = FakeDesktopStoryboardExporter(),
            nextOutputPath = { "recordings/test.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
            audioMuteController = muteController,
        )
        runCurrent()

        viewModel.onAction(RecorderUiAction.SelectMicrophone(microphone.id.value))
        viewModel.onAction(RecorderUiAction.SetSystemAudioEnabled(true))
        viewModel.onAction(RecorderUiAction.StartRecording)
        runCurrent()

        viewModel.onAction(RecorderUiAction.SetMicrophoneMuted(true))
        viewModel.onAction(RecorderUiAction.SetSystemAudioMuted(true))
        viewModel.onAction(RecorderUiAction.SetMicrophoneSolo(true))
        runCurrent()

        assertEquals(setOf(microphone.id, loopback.id), muteController.mutedSourceIds.value)
        assertTrue(viewModel.state.value.microphoneMuted)
        assertTrue(viewModel.state.value.systemAudioMuted)
        assertEquals(microphone.id, muteController.soloSourceId.value)
        assertTrue(viewModel.state.value.microphoneSolo)
        assertFalse(viewModel.state.value.systemAudioSolo)
        assertEquals(listOf(microphone, loopback), assertNotNull(engine.startedSettings).audioSources)

        viewModel.onAction(RecorderUiAction.SetSystemAudioSolo(true))
        runCurrent()
        assertEquals(loopback.id, muteController.soloSourceId.value)
        assertFalse(viewModel.state.value.microphoneSolo)
        assertTrue(viewModel.state.value.systemAudioSolo)
        assertEquals(setOf(microphone.id, loopback.id), muteController.mutedSourceIds.value)

        viewModel.onAction(RecorderUiAction.StopRecording)
        runCurrent()
        viewModel.onAction(RecorderUiAction.SelectMicrophone(null))
        viewModel.onAction(RecorderUiAction.SetSystemAudioEnabled(false))
        runCurrent()

        assertTrue(muteController.mutedSourceIds.value.isEmpty())
        assertFalse(viewModel.state.value.microphoneMuted)
        assertFalse(viewModel.state.value.systemAudioMuted)
        assertEquals(null, muteController.soloSourceId.value)
        assertFalse(viewModel.state.value.microphoneSolo)
        assertFalse(viewModel.state.value.systemAudioSolo)
    }

    @Test
    fun choosesOutputFileBeforeAllowingRecordingToStart() = runTest {
        val screen = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")
        val selection = CompletableDeferred<DesktopOutputFileSelection>()
        val requestedPaths = mutableListOf<String>()
        val engine = FakeDesktopRecordingEngine()
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(listOf(screen)),
            audioSourceRepository = StaticAudioSourceRepository(emptyList()),
            recordingEngine = engine,
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = FakeDesktopStoryboardExporter(),
            nextOutputPath = { "recordings/default.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
            outputFileSelector = DesktopOutputFileSelector { currentPath ->
                requestedPaths += currentPath
                selection.await()
            },
        )
        runCurrent()

        viewModel.onAction(RecorderUiAction.ChooseOutputFile)
        assertTrue(viewModel.state.value.isChoosingOutputFile)
        viewModel.onAction(RecorderUiAction.StartRecording)
        runCurrent()

        assertEquals(listOf("recordings/default.mp4"), requestedPaths)
        assertEquals(null, engine.startedSettings)

        val selectedPath = Path.of("recordings", "selected.mp4").toAbsolutePath().normalize().toString()
        selection.complete(DesktopOutputFileSelection.Selected(selectedPath))
        runCurrent()

        assertFalse(viewModel.state.value.isChoosingOutputFile)
        assertEquals(selectedPath, viewModel.state.value.outputPath)

        viewModel.onAction(RecorderUiAction.StartRecording)
        runCurrent()

        assertEquals(selectedPath, assertNotNull(engine.startedSettings).outputPath)
    }

    @Test
    fun mapsSelectedAudioSourceLevelsOnlyWhileCaptureIsActive() = runTest {
        val screen = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")
        val microphone = AudioSource.Microphone(
            id = AudioSourceId("mic:test"),
            displayName = "Test microphone",
            sampleRate = 48_000,
            channelCount = 2,
        )
        val loopback = AudioSource.SystemLoopback(
            id = AudioSourceId("wasapi:loopback:default"),
            displayName = "System audio",
            sampleRate = 48_000,
            channelCount = 2,
        )
        val levels = MutableStateFlow<Map<AudioSourceId, AudioLevels>>(emptyMap())
        val engine = FakeDesktopRecordingEngine()
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(listOf(screen)),
            audioSourceRepository = StaticAudioSourceRepository(listOf(microphone, loopback)),
            recordingEngine = engine,
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = FakeDesktopStoryboardExporter(),
            nextOutputPath = { "recordings/test.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
            audioLevels = levels,
        )
        runCurrent()

        viewModel.onAction(RecorderUiAction.SelectMicrophone(microphone.id.value))
        viewModel.onAction(RecorderUiAction.SetSystemAudioEnabled(true))
        viewModel.onAction(RecorderUiAction.StartRecording)
        runCurrent()

        levels.value = mapOf(
            microphone.id to AudioLevels(peak = 0.8, rms = 0.5),
            loopback.id to AudioLevels(peak = 0.35, rms = 0.2),
        )
        runCurrent()

        assertEquals(0.8f, viewModel.state.value.microphoneLevel, absoluteTolerance = 0.0001f)
        assertEquals(0.35f, viewModel.state.value.systemAudioLevel, absoluteTolerance = 0.0001f)

        viewModel.onAction(RecorderUiAction.StopRecording)
        runCurrent()

        assertEquals(0f, viewModel.state.value.microphoneLevel)
        assertEquals(0f, viewModel.state.value.systemAudioLevel)
    }

    @Test
    fun selectsRegionKeepsItOnRefreshAndUsesItForRecording() = runTest {
        val screen = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")
        val region = CaptureRegion(
            x = -640,
            y = 120,
            width = 960,
            height = 540,
            monitorId = CaptureSourceId("monitor:2"),
            scaleFactor = 1.25,
            coordinateSpace = CoordinateSpace.LogicalPixels,
        )
        val engine = FakeDesktopRecordingEngine()
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(listOf(screen)),
            audioSourceRepository = StaticAudioSourceRepository(emptyList()),
            recordingEngine = engine,
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = FakeDesktopStoryboardExporter(),
            nextOutputPath = { "recordings/test.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
            captureRegionSelector = CaptureRegionSelector {
                CaptureRegionSelection.Selected(region)
            },
        )
        runCurrent()

        viewModel.onAction(RecorderUiAction.SelectRegion)
        runCurrent()

        assertFalse(viewModel.state.value.isSelectingRegion)
        assertEquals("region:-640,120,960x540", viewModel.state.value.selectedSourceId)
        assertEquals("960x540 @ -640,120", viewModel.state.value.sources.last().displayName)

        viewModel.onAction(RecorderUiAction.RefreshSources)
        runCurrent()

        assertEquals("region:-640,120,960x540", viewModel.state.value.selectedSourceId)
        assertEquals(1, viewModel.state.value.sources.count { it.id.startsWith("region:") })

        viewModel.onAction(RecorderUiAction.StartRecording)
        runCurrent()

        val settings = assertNotNull(engine.startedSettings)
        assertEquals(region, (settings.captureSource as CaptureSource.Region).region)
        assertTrue(settings.captureCursor)
    }

    @Test
    fun keepsRegionSelectionBusyUntilExplicitCancellation() = runTest {
        val result = CompletableDeferred<CaptureRegionSelection>()
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(emptyList()),
            audioSourceRepository = StaticAudioSourceRepository(emptyList()),
            recordingEngine = FakeDesktopRecordingEngine(),
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = FakeDesktopStoryboardExporter(),
            nextOutputPath = { "recordings/test.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
            captureRegionSelector = CaptureRegionSelector { result.await() },
        )
        runCurrent()

        viewModel.onAction(RecorderUiAction.SelectRegion)
        assertTrue(viewModel.state.value.isSelectingRegion)
        runCurrent()

        result.complete(CaptureRegionSelection.Cancelled)
        runCurrent()

        assertFalse(viewModel.state.value.isSelectingRegion)
        assertEquals(null, viewModel.state.value.selectedSourceId)
        assertEquals(null, viewModel.state.value.errorMessage)
    }

    @Test
    fun startsRecordingAfterHotkeyRegionSelection() = runTest {
        val region = CaptureRegion(
            x = 100,
            y = 200,
            width = 1280,
            height = 720,
            monitorId = CaptureSourceId("monitor:1"),
            scaleFactor = 1.0,
            coordinateSpace = CoordinateSpace.LogicalPixels,
        )
        val engine = FakeDesktopRecordingEngine()
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(emptyList()),
            audioSourceRepository = StaticAudioSourceRepository(emptyList()),
            recordingEngine = engine,
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = FakeDesktopStoryboardExporter(),
            nextOutputPath = { "recordings/test.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
            captureRegionSelector = CaptureRegionSelector {
                CaptureRegionSelection.Selected(region)
            },
        )
        runCurrent()

        viewModel.onAction(RecorderUiAction.SelectRegionAndStartRecording)
        runCurrent()

        val settings = assertNotNull(engine.startedSettings)
        assertEquals(region, (settings.captureSource as CaptureSource.Region).region)
    }

    @Test
    fun discoversSourcesAndControlsRecordingWithoutPlatformDevices() = runTest {
        val screen = CaptureSource.Screen(
            id = CaptureSourceId("screen:test"),
            displayName = "Test screen",
        )
        val microphone = AudioSource.Microphone(
            id = AudioSourceId("mic:test"),
            displayName = "Test microphone",
            sampleRate = 48_000,
            channelCount = 2,
        )
        val engine = FakeDesktopRecordingEngine()
        val storyboardExporter = FakeDesktopStoryboardExporter()
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(listOf(screen)),
            audioSourceRepository = StaticAudioSourceRepository(listOf(microphone)),
            recordingEngine = engine,
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = storyboardExporter,
            nextOutputPath = { "recordings/test.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
        )

        runCurrent()

        assertEquals("screen:test", viewModel.state.value.selectedSourceId)
        assertEquals(listOf("mic:test"), viewModel.state.value.microphones.map { it.id })

        viewModel.onAction(RecorderUiAction.SelectMicrophone("mic:test"))
        viewModel.onAction(RecorderUiAction.SetFrameRate(60))
        viewModel.onAction(RecorderUiAction.StartRecording)
        runCurrent()

        val settings = assertNotNull(engine.startedSettings)
        assertEquals(screen, settings.captureSource)
        assertEquals(listOf(microphone), settings.audioSources)
        assertEquals(60, settings.frameRate)
        assertTrue(settings.captureCursor)
        assertEquals(RecorderStatus.Recording, viewModel.state.value.status)

        engine.emitMetrics(
            RecordingMetrics(duration = 2.seconds, videoFrames = 120, audioFrames = 20, droppedFrames = 3),
        )
        runCurrent()

        assertEquals(2_000, viewModel.state.value.elapsedMilliseconds)
        assertEquals(120, viewModel.state.value.videoFrames)
        assertEquals(20, viewModel.state.value.audioFrames)
        assertEquals(3, viewModel.state.value.droppedFrames)
        assertEquals(60.0, viewModel.state.value.effectiveFramesPerSecond)

        viewModel.onAction(RecorderUiAction.StopRecording)
        runCurrent()

        assertTrue(engine.stopCalled)
        assertEquals(RecorderStatus.Completed, viewModel.state.value.status)
        assertEquals("recordings/test.mp4", viewModel.state.value.lastOutputPath)
        assertEquals("recordings/test.mp4", viewModel.state.value.storyboardInputPath)
        assertTrue(storyboardExporter.requests.isEmpty())

        viewModel.onAction(RecorderUiAction.SetStoryboardInputPath(" videos/source.mp4 "))
        viewModel.onAction(RecorderUiAction.SetStoryboardMode(StoryboardMode.ContactSheet))
        viewModel.onAction(RecorderUiAction.ExportStoryboard)
        runCurrent()

        assertEquals(
            DesktopStoryboardExportRequest(
                inputVideoPath = "videos/source.mp4",
                outputPath = Path.of("videos", "source-storyboard.png").toString(),
                mode = StoryboardMode.ContactSheet,
            ),
            storyboardExporter.requests.single(),
        )
        assertEquals(
            Path.of("videos", "source-storyboard.png").toString(),
            viewModel.state.value.lastStoryboardPath,
        )
    }

    @Test
    fun reportsUnavailableSystemAudioWithoutChangingToggle() = runTest {
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(emptyList()),
            audioSourceRepository = StaticAudioSourceRepository(emptyList()),
            recordingEngine = FakeDesktopRecordingEngine(),
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = FakeDesktopStoryboardExporter(),
            nextOutputPath = { "recordings/test.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
        )
        runCurrent()

        viewModel.onAction(RecorderUiAction.SetSystemAudioEnabled(true))

        assertEquals(false, viewModel.state.value.systemAudioEnabled)
        assertTrue(viewModel.state.value.errorMessage?.contains("System audio") == true)
    }

    @Test
    fun enablesDiscoveredSystemAudioForRecording() = runTest {
        val screen = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen")
        val loopback = AudioSource.SystemLoopback(
            id = AudioSourceId("wasapi:loopback:default"),
            displayName = "System audio",
            sampleRate = 48_000,
            channelCount = 2,
        )
        val engine = FakeDesktopRecordingEngine()
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(listOf(screen)),
            audioSourceRepository = StaticAudioSourceRepository(listOf(loopback)),
            recordingEngine = engine,
            replayEngine = FakeDesktopReplayEngine(),
            storyboardExporter = FakeDesktopStoryboardExporter(),
            nextOutputPath = { "recordings/test.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
        )
        runCurrent()

        assertTrue(viewModel.state.value.systemAudioAvailable)
        viewModel.onAction(RecorderUiAction.SetSystemAudioEnabled(true))
        viewModel.onAction(RecorderUiAction.StartRecording)
        runCurrent()

        assertTrue(viewModel.state.value.systemAudioEnabled)
        assertEquals(listOf(loopback), assertNotNull(engine.startedSettings).audioSources)
    }

    @Test
    fun controlsReplayBufferAndSavesWithoutStoppingIt() = runTest {
        val screen = CaptureSource.Screen(
            id = CaptureSourceId("screen:test"),
            displayName = "Test screen",
        )
        val replayEngine = FakeDesktopReplayEngine()
        val recordingEngine = FakeDesktopRecordingEngine()
        val viewModel = DesktopRecorderViewModel(
            scope = backgroundScope,
            captureSourceRepository = StaticCaptureSourceRepository(listOf(screen)),
            audioSourceRepository = StaticAudioSourceRepository(emptyList()),
            recordingEngine = recordingEngine,
            replayEngine = replayEngine,
            storyboardExporter = FakeDesktopStoryboardExporter(),
            nextOutputPath = { "recordings/test.mp4" },
            nextReplayOutputPath = { "recordings/replay.mp4" },
        )
        runCurrent()

        viewModel.onAction(RecorderUiAction.SetReplayDurationMinutes(7))
        viewModel.onAction(RecorderUiAction.SetCaptureCursor(false))
        viewModel.onAction(RecorderUiAction.SetVideoBitrateMbps(20))
        viewModel.onAction(RecorderUiAction.StartReplayBuffer)
        runCurrent()

        val settings = assertNotNull(replayEngine.startedSettings)
        assertEquals(7.minutes, settings.replayDuration)
        assertFalse(settings.captureCursor)
        assertEquals(20_000_000, settings.encoder.videoBitrateBitsPerSecond)
        assertEquals(ReplayUiStatus.Buffering, viewModel.state.value.replayStatus)
        assertEquals(null, recordingEngine.startedSettings)

        replayEngine.emitStats(
            ReplayBufferStats(
                videoFrameCount = 100,
                audioFrameCount = 0,
                retainedDuration = 90.seconds,
                storagePolicy = ReplayStoragePolicy.DiskSegments,
                droppedVideoFrameCount = 4,
            ),
        )
        runCurrent()

        assertEquals(90_000, viewModel.state.value.replayRetainedMilliseconds)
        assertEquals(100, viewModel.state.value.replayVideoFrames)
        assertEquals(4, viewModel.state.value.replayDroppedFrames)

        viewModel.onAction(RecorderUiAction.SaveReplayBuffer)
        runCurrent()

        assertEquals("recordings/replay.mp4", replayEngine.savedOutputPath)
        assertEquals("recordings/replay.mp4", viewModel.state.value.lastReplayPath)
        assertEquals(ReplayUiStatus.Buffering, viewModel.state.value.replayStatus)

        viewModel.onAction(RecorderUiAction.StopReplayBuffer)
        runCurrent()

        assertTrue(replayEngine.stopCalled)
        assertEquals(ReplayUiStatus.Idle, viewModel.state.value.replayStatus)
    }
}

private class FakeDesktopStoryboardExporter : DesktopStoryboardExporter {
    val requests = mutableListOf<DesktopStoryboardExportRequest>()

    override suspend fun export(request: DesktopStoryboardExportRequest): DesktopStoryboardExportResult {
        requests += request
        return DesktopStoryboardExportResult(outputPath = request.outputPath, frameCount = 3)
    }
}

private class FakeDesktopReplayEngine : DesktopReplayEngine {
    private val mutableState = MutableStateFlow<ReplayCaptureState>(ReplayCaptureState.Idle)
    private var session: RecordingSession? = null

    override val state: StateFlow<ReplayCaptureState> = mutableState
    var startedSettings: RecordingSettings? = null
        private set
    var savedOutputPath: String? = null
        private set
    var stopCalled: Boolean = false
        private set

    override suspend fun start(settings: RecordingSettings): StartReplayResult {
        startedSettings = settings
        val startedSession = RecordingSession(
            id = RecordingSessionId("replay-session:test"),
            settings = settings,
            startedAtNanoseconds = 0,
        )
        session = startedSession
        mutableState.value = ReplayCaptureState.Buffering(startedSession, replayStats())
        return StartReplayResult.Started(startedSession)
    }

    override suspend fun save(outputPath: String): SaveReplayResult {
        val activeSession = session ?: return SaveReplayResult.NotBuffering(mutableState.value)
        savedOutputPath = outputPath
        mutableState.value = ReplayCaptureState.Saving(activeSession, replayStats(), outputPath)
        val result = ReplaySaveResult(
            output = io.aequicor.capture.core.RecordingOutput(outputPath),
            videoFrames = replayStats().videoFrameCount.toLong(),
            audioFrames = replayStats().audioFrameCount.toLong(),
            duration = replayStats().retainedDuration,
        )
        mutableState.value = ReplayCaptureState.Buffering(activeSession, replayStats())
        return SaveReplayResult.Saved(result)
    }

    override suspend fun stop(): StopReplayResult {
        stopCalled = true
        val activeSession = session ?: return StopReplayResult.NotBuffering(mutableState.value)
        val stats = replayStats()
        session = null
        mutableState.value = ReplayCaptureState.Idle
        return StopReplayResult.Stopped(activeSession, stats)
    }

    fun emitStats(stats: ReplayBufferStats) {
        val activeSession = requireNotNull(session)
        mutableState.value = ReplayCaptureState.Buffering(activeSession, stats)
    }

    private fun replayStats(): ReplayBufferStats =
        when (val current = mutableState.value) {
            is ReplayCaptureState.Buffering -> current.stats
            is ReplayCaptureState.Saving -> current.stats
            is ReplayCaptureState.Stopping -> current.stats
            is ReplayCaptureState.Failed -> current.stats
            ReplayCaptureState.Idle,
            is ReplayCaptureState.Preparing,
            -> ReplayBufferStats(0, 0, kotlin.time.Duration.ZERO, ReplayStoragePolicy.DiskSegments)
        }
}

private class SelectingProfileStore(
    private val selected: DesktopRecorderProfileConfiguration,
    private val nextOutputPath: String? = null,
    private val previewOutputPath: String? = null,
) : DesktopRecorderProfileStore {
    val saved = mutableListOf<DesktopRecorderProfileSnapshot>()

    override suspend fun save(snapshot: DesktopRecorderProfileSnapshot) {
        saved += snapshot
    }

    override suspend fun select(profileId: String): DesktopRecorderProfileCatalog {
        assertEquals(selected.summary.id, profileId)
        return DesktopRecorderProfileCatalog(
            profiles = listOf(
                DesktopRecorderProfileSummary("default", "Default"),
                selected.summary,
            ),
            selected = selected,
        )
    }

    override suspend fun create(
        name: String,
        snapshot: DesktopRecorderProfileSnapshot,
    ): DesktopRecorderProfileCatalog = error("Not used in this test.")

    override suspend fun delete(profileId: String): DesktopRecorderProfileCatalog = error("Not used in this test.")

    override fun nextOutputPath(profileId: String): String? {
        assertEquals(selected.summary.id, profileId)
        return nextOutputPath
    }

    override fun previewOutputPath(profileId: String, outputPolicy: DesktopOutputPolicy): String? {
        assertEquals(selected.summary.id, profileId)
        return previewOutputPath
    }
}

private class RejectingPermissionGateway : PermissionGateway {
    var lastChecked: Set<CapturePermission> = emptySet()
        private set
    var lastRequested: Set<CapturePermission> = emptySet()
        private set

    override suspend fun check(required: Set<CapturePermission>): PermissionReport {
        lastChecked = required
        return denied(required)
    }

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

private class FakeDesktopPreviewEngine(
    private val width: Int = 2,
    private val height: Int = 1,
    private val pixelFormat: PixelFormat = PixelFormat.Rgba8888,
    private val strideBytes: Int = width * 4,
    private val pixels: ByteArray = previewPixels(width, height),
) : DesktopPreviewEngine {
    var settings: RecordingSettings? = null
        private set
    var starts: Int = 0
        private set
    var cancelled: Boolean = false
        private set

    override fun frames(settings: RecordingSettings) = flow {
        this@FakeDesktopPreviewEngine.settings = settings
        starts += 1
        try {
            emit(
                VideoFrame(
                    timestamp = MediaTimestamp(0),
                    width = width,
                    height = height,
                    pixelFormat = pixelFormat,
                    strideBytes = strideBytes,
                    sourceId = CaptureSourceId("screen:test"),
                    pixelData = pixels,
                ),
            )
            awaitCancellation()
        } finally {
            cancelled = true
        }
    }
}

private class FakeDesktopScreenshotSaver : DesktopScreenshotSaver {
    var frame: DesktopPreviewFrame? = null
        private set
    var outputPath: String? = null
        private set

    override suspend fun save(
        frame: DesktopPreviewFrame,
        outputPath: String,
    ): DesktopScreenshotSaveResult {
        this.frame = frame
        this.outputPath = outputPath
        return DesktopScreenshotSaveResult.Saved(outputPath)
    }
}

private fun previewPixels(width: Int, height: Int): ByteArray =
    if (width == 2 && height == 1) {
        byteArrayOf(
            255.toByte(), 0, 0, 255.toByte(),
            0, 255.toByte(), 0, 255.toByte(),
        )
    } else {
        ByteArray(width * height * 4)
    }

private class FakeRecordingsDirectoryOpener : DesktopRecordingsDirectoryOpener {
    var lastOutputPath: String? = null
        private set

    override suspend fun openForOutput(outputPath: String): DesktopDirectoryOpenResult {
        lastOutputPath = outputPath
        return DesktopDirectoryOpenResult.Unavailable("Folder opener failed for test.")
    }
}

private class FakeDesktopRecordingEngine : DesktopRecordingEngine {
    private val mutableState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    private var session: RecordingSession? = null

    override val state: StateFlow<RecordingState> = mutableState
    var startedSettings: RecordingSettings? = null
        private set
    var stopCalled: Boolean = false
        private set
    var pauseCalls: Int = 0
        private set
    var resumeCalls: Int = 0
        private set
    var markImportantFrameCalls: Int = 0
        private set

    override suspend fun start(settings: RecordingSettings): StartRecordingResult {
        startedSettings = settings
        val startedSession = RecordingSession(
            id = RecordingSessionId("session:test"),
            settings = settings,
            startedAtNanoseconds = 0,
        )
        session = startedSession
        mutableState.value = RecordingState.Recording(startedSession, RecordingMetrics())
        return StartRecordingResult.Started(startedSession)
    }

    override suspend fun pause(): PauseRecordingResult {
        pauseCalls += 1
        val current = mutableState.value
        if (current is RecordingState.Paused) {
            return PauseRecordingResult.AlreadyPaused(current)
        }
        if (current !is RecordingState.Recording) {
            return PauseRecordingResult.NotRecording(current)
        }
        val paused = RecordingState.Paused(current.session, current.metrics)
        mutableState.value = paused
        return PauseRecordingResult.Paused(paused)
    }

    override suspend fun resume(): ResumeRecordingResult {
        resumeCalls += 1
        val current = mutableState.value
        if (current !is RecordingState.Paused) {
            return ResumeRecordingResult.NotPaused(current)
        }
        val recording = RecordingState.Recording(current.session, current.metrics)
        mutableState.value = recording
        return ResumeRecordingResult.Resumed(recording)
    }

    override suspend fun stop(): StopRecordingResult {
        stopCalled = true
        val activeSession = session ?: return StopRecordingResult.NotRecording(mutableState.value)
        val metrics = when (val current = mutableState.value) {
            is RecordingState.Recording -> current.metrics
            is RecordingState.Paused -> current.metrics
            else -> RecordingMetrics()
        }
        val completed = RecordingState.Completed(
            session = activeSession,
            metrics = metrics,
            outputPath = activeSession.settings.outputPath,
        )
        mutableState.value = completed
        session = null
        return StopRecordingResult.Stopped(completed)
    }

    override suspend fun cancel(): CancelRecordingResult {
        val activeSession = session ?: return CancelRecordingResult.NotRecording(mutableState.value)
        val cancelled = RecordingState.Cancelled(activeSession, RecordingMetrics())
        mutableState.value = cancelled
        session = null
        return CancelRecordingResult.Cancelled(cancelled)
    }

    override suspend fun markImportantFrame(): MarkImportantFrameResult {
        if (mutableState.value !is RecordingState.Recording) {
            return MarkImportantFrameResult.NotRecording(mutableState.value)
        }
        markImportantFrameCalls += 1
        return MarkImportantFrameResult.Marked
    }

    fun emitMetrics(metrics: RecordingMetrics) {
        val activeSession = requireNotNull(session)
        mutableState.value = RecordingState.Recording(activeSession, metrics)
    }
}
