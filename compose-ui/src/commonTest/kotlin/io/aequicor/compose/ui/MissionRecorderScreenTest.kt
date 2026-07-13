package io.aequicor.compose.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MissionRecorderScreenTest {
    @Test
    fun showsEffectiveFpsAndDroppedFramesInTransportBar() = runComposeUiTest {
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(
                    status = RecorderStatus.Recording,
                    elapsedMilliseconds = 2_000,
                    videoFrames = 120,
                    droppedFrames = 3,
                    effectiveFramesPerSecond = 60.0,
                ),
                onAction = {},
            )
        }

        onAllNodesWithTag("effective-fps").assertCountEquals(1)
        onAllNodesWithTag("dropped-frames").assertCountEquals(1)
    }

    @Test
    fun openRecordingsFolderButtonHoistsAction() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(outputPath = "recordings/test.mp4"),
                onAction = actions::add,
            )
        }

        onNodeWithTag("open-recordings-folder").performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf<RecorderUiAction>(RecorderUiAction.OpenRecordingsFolder), actions)
    }

    @Test
    fun previewDoesNotRequireAnExplicitStartButton() = runComposeUiTest {
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(
                    sources = listOf(RecorderSourceUi("screen:test", "Test screen", RecorderSourceKind.Screen)),
                    selectedSourceId = "screen:test",
                    outputPath = "recordings/test.mp4",
                    previewStatus = PreviewUiStatus.Active,
                ),
                onAction = {},
            )
        }

        onAllNodesWithTag("toggle-preview").assertCountEquals(0)
        onAllNodesWithTag("preview-image").assertCountEquals(0)
    }

    @Test
    fun displaysFirstPreviewFrameAfterSourceSelection() = runComposeUiTest {
        val previewImage = mutableStateOf<ImageBitmap?>(null)
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(
                    sources = listOf(RecorderSourceUi("window:test", "Test window", RecorderSourceKind.Window)),
                    selectedSourceId = "window:test",
                    previewStatus = PreviewUiStatus.Active,
                ),
                onAction = {},
                previewImage = previewImage,
            )
        }

        previewImage.value = ImageBitmap(width = 1, height = 1)

        onAllNodesWithTag("preview-image").assertCountEquals(1)
    }

    @Test
    fun doesNotShowProfileControls() = runComposeUiTest {
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(outputPath = "recordings/test.mp4"),
                onAction = {},
            )
        }

        onAllNodesWithTag("profile-selector").assertCountEquals(0)
        onAllNodesWithTag("create-profile").assertCountEquals(0)
        onAllNodesWithTag("delete-profile").assertCountEquals(0)
    }

    @Test
    fun opensShortcutSettingsFromHeader() = runComposeUiTest {
        var configureRequests = 0
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(outputPath = "recordings/test.mp4"),
                onAction = {},
                onConfigureShortcuts = { configureRequests++ },
            )
        }

        onNodeWithTag("configure-hotkeys").performClick()

        assertEquals(1, configureRequests)
    }

    @Test
    fun miniControllerHoistsPrimaryRecordingActions() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        var expandRequests = 0
        var hideRequests = 0
        var openEditorRequests = 0
        setContent {
            MiniRecorderController(
                state = RecorderUiState(
                    sources = listOf(
                        RecorderSourceUi("screen:test", "Test screen", RecorderSourceKind.Screen),
                    ),
                    selectedSourceId = "screen:test",
                    microphones = listOf(RecorderMicrophoneUi("mic:test", "Test microphone")),
                    selectedMicrophoneId = "mic:test",
                    systemAudioAvailable = true,
                    systemAudioEnabled = true,
                    systemAudioMuted = true,
                    outputPath = "recordings/test.mp4",
                    status = RecorderStatus.Recording,
                    elapsedMilliseconds = 65_000,
                ),
                onAction = actions::add,
                onExpand = { expandRequests++ },
                onHide = { hideRequests++ },
                onOpenEditor = { openEditorRequests++ },
            )
        }

        onAllNodesWithTag("mini-microphone-mute").assertCountEquals(0)
        onAllNodesWithTag("mini-system-audio-mute").assertCountEquals(0)
        onNodeWithTag("mini-expand").assertIsEnabled().performClick()
        onNodeWithTag("mini-hide").assertIsEnabled().performClick()
        onNodeWithTag("mini-pause-toggle").assertIsEnabled().performClick()
        onNodeWithTag("mini-record-toggle").assertIsEnabled().performClick()
        onNodeWithTag("mini-open-editor-icon", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("mini-open-editor").assertIsEnabled().performClick()
        onNodeWithTag("mini-mark-important-frame").assertIsEnabled().performClick()

        val transportCenter = onNodeWithTag("mini-transport-controls").fetchSemanticsNode().boundsInRoot.center.x
        val pauseCenter = onNodeWithTag("mini-pause-toggle").fetchSemanticsNode().boundsInRoot.center.x
        val recordCenter = onNodeWithTag("mini-record-toggle").fetchSemanticsNode().boundsInRoot.center.x
        val editorCenter = onNodeWithTag("mini-open-editor").fetchSemanticsNode().boundsInRoot.center.x
        assertTrue(kotlin.math.abs(transportCenter - recordCenter) < 0.5f)
        assertTrue(kotlin.math.abs((recordCenter - pauseCenter) - (editorCenter - recordCenter)) < 0.5f)

        assertEquals(1, expandRequests)
        assertEquals(1, hideRequests)
        assertEquals(1, openEditorRequests)
        assertEquals(
            listOf(
                RecorderUiAction.PauseRecording,
                RecorderUiAction.StopRecording,
                RecorderUiAction.MarkImportantFrame,
            ),
            actions,
        )
    }

    @Test
    fun miniControllerShowsImportantFrameCaptureFeedbackAfterShortcut() = runComposeUiTest {
        var captureSequence by mutableStateOf(0L)
        setContent {
            MiniRecorderController(
                state = RecorderUiState(
                    status = RecorderStatus.Recording,
                    importantFrameCaptureSequence = captureSequence,
                ),
                onAction = {},
            )
        }

        captureSequence += 1L

        onAllNodesWithTag("mini-important-frame-capture-effect").assertCountEquals(1)
    }

    @Test
    fun recordingShortcutTooltipIncludesActionAndGesture() {
        assertEquals("Stop · Ctrl+Shift+F9", shortcutTooltipLabel("Stop", "Ctrl+Shift+F9"))
        assertEquals("Pause · Ctrl+Shift+F10", shortcutTooltipLabel("Pause", "Ctrl+Shift+F10"))
    }

    @Test
    fun miniControllerDisablesRecordingOnlyActionsWithoutActiveRecording() = runComposeUiTest {
        setContent {
            MiniRecorderController(
                state = RecorderUiState(
                    sources = listOf(
                        RecorderSourceUi("screen:test", "Test screen", RecorderSourceKind.Screen),
                    ),
                    selectedSourceId = "screen:test",
                    outputPath = "recordings/test.mp4",
                ),
                onAction = {},
            )
        }

        onNodeWithTag("mini-pause-toggle").assertIsNotEnabled()
        onNodeWithTag("mini-mark-important-frame").assertIsNotEnabled()
        onNodeWithTag("mini-open-editor").assertIsNotEnabled()
        onNodeWithTag("mini-record-toggle").assertIsEnabled()
    }

    @Test
    fun miniControllerOpensLastRecordingInEditor() = runComposeUiTest {
        var openEditorRequests = 0
        setContent {
            MiniRecorderController(
                state = RecorderUiState(
                    status = RecorderStatus.Completed,
                    lastOutputPath = "recordings/finished.mp4",
                ),
                onAction = {},
                onOpenEditor = { openEditorRequests++ },
            )
        }

        onNodeWithTag("mini-open-editor").assertIsEnabled().performClick()

        assertEquals(1, openEditorRequests)
    }

    @Test
    fun miniControllerDisplaysPreviewFrame() = runComposeUiTest {
        val previewImage = mutableStateOf<ImageBitmap?>(ImageBitmap(width = 2, height = 2))
        setContent {
            MiniRecorderController(
                state = RecorderUiState(status = RecorderStatus.Recording),
                onAction = {},
                previewImage = previewImage,
            )
        }

        onAllNodesWithTag("mini-preview-image").assertCountEquals(1)
    }

    @Test
    fun miniControllerHoistsReplayActions() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MiniRecorderController(
                state = RecorderUiState(
                    replayStatus = ReplayUiStatus.Buffering,
                    replayVideoFrames = 12,
                ),
                onAction = actions::add,
            )
        }

        onNodeWithTag("mini-save-replay").assertIsEnabled().performClick()
        onNodeWithTag("mini-stop-replay").assertIsEnabled().performClick()

        assertEquals(
            listOf(RecorderUiAction.SaveReplayBuffer, RecorderUiAction.StopReplayBuffer),
            actions,
        )
    }

    @Test
    fun mainAudioControlsHoistIndependentSoloActions() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(
                    microphones = listOf(RecorderMicrophoneUi("mic:test", "Test microphone")),
                    selectedMicrophoneId = "mic:test",
                    systemAudioSources = listOf(RecorderSystemAudioUi("system:test", "Speakers")),
                    selectedSystemAudioId = "system:test",
                    systemAudioAvailable = true,
                    systemAudioEnabled = true,
                    microphoneSolo = true,
                ),
                onAction = actions::add,
            )
        }

        onNodeWithTag("microphone-solo").performScrollTo().assertIsEnabled().performClick()
        onNodeWithTag("system-audio-solo").performScrollTo().assertIsEnabled().performClick()

        assertEquals(
            listOf(
                RecorderUiAction.SetMicrophoneSolo(false),
                RecorderUiAction.SetSystemAudioSolo(true),
            ),
            actions,
        )
    }

    @Test
    fun pausedMainTransportResumesOrStopsTheActiveRecording() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(
                    sources = listOf(
                        RecorderSourceUi("screen:test", "Test screen", RecorderSourceKind.Screen),
                    ),
                    selectedSourceId = "screen:test",
                    outputPath = "recordings/test.mp4",
                    status = RecorderStatus.Paused,
                ),
                onAction = actions::add,
            )
        }

        onNodeWithTag("pause-toggle").assertIsEnabled().performClick()
        onNodeWithTag("record-toggle").assertIsEnabled().performClick()

        assertEquals(
            listOf(RecorderUiAction.ResumeRecording, RecorderUiAction.StopRecording),
            actions,
        )
    }

    @Test
    fun requestsOutputFileSelectionFromFolderButton() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(outputPath = "recordings/default.mp4"),
                onAction = actions::add,
            )
        }

        onNodeWithTag("choose-output-file").performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf<RecorderUiAction>(RecorderUiAction.ChooseOutputFile), actions)
    }

    @Test
    fun requestsOutputNamingEditor() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(outputPath = "recordings/default.mp4"),
                onAction = actions::add,
            )
        }

        onNodeWithTag("output-naming").performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf<RecorderUiAction>(RecorderUiAction.ShowOutputNamingDialog), actions)
    }

    @Test
    fun outputNamingDialogSubmitsOnlyValidMp4Pattern() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(
                    showOutputNamingDialog = true,
                    outputDirectory = "recordings",
                    outputFileNamePattern = "mission-{timestamp}.mp4",
                ),
                onAction = actions::add,
            )
        }

        onNodeWithTag("output-file-name-pattern").performTextClearance()
        onNodeWithTag("apply-output-naming").assertIsNotEnabled()
        onNodeWithTag("output-file-name-pattern").performTextInput("work-{profile}-{timestamp}.mp4")
        onNodeWithTag("apply-output-naming").assertIsEnabled().performClick()

        assertEquals(
            listOf<RecorderUiAction>(
                RecorderUiAction.ApplyOutputNaming("recordings", "work-{profile}-{timestamp}.mp4"),
            ),
            actions,
        )
    }

    @Test
    fun rendersMicrophoneAndSystemAudioLevels() = runComposeUiTest {
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(
                    microphones = listOf(RecorderMicrophoneUi("mic:test", "Test microphone")),
                    selectedMicrophoneId = "mic:test",
                    systemAudioAvailable = true,
                    systemAudioEnabled = true,
                    microphoneLevel = 0.8f,
                    systemAudioLevel = 0.35f,
                ),
                onAction = {},
            )
        }

        onNodeWithTag("microphone-level")
            .performScrollTo()
            .assertRangeInfoEquals(ProgressBarRangeInfo(0.8f, 0f..1f, 0))
        onNodeWithTag("system-audio-level")
            .assertRangeInfoEquals(ProgressBarRangeInfo(0.35f, 0f..1f, 0))
    }

    @Test
    fun selectsSystemAudioOutputFromAvailableEndpoints() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(
                    systemAudioAvailable = true,
                    systemAudioSources = listOf(
                        RecorderSystemAudioUi("system:speakers", "Speakers"),
                        RecorderSystemAudioUi("system:headset", "Headset"),
                    ),
                    selectedSystemAudioId = "system:speakers",
                ),
                onAction = actions::add,
            )
        }

        onNodeWithTag("system-audio-selector").performScrollTo().performClick()
        onNodeWithText("Headset").performClick()

        assertEquals(
            listOf<RecorderUiAction>(RecorderUiAction.SelectSystemAudio("system:headset")),
            actions,
        )
    }

    @Test
    fun requestsRegionSelectionOnlyFromDedicatedControl() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(
                    sources = listOf(
                        RecorderSourceUi("screen:test", "Test screen", RecorderSourceKind.Screen),
                    ),
                    selectedSourceId = "screen:test",
                    outputPath = "recordings/test.mp4",
                ),
                onAction = actions::add,
            )
        }

        onNodeWithTag("select-region").assertIsEnabled().performClick()

        assertEquals(listOf<RecorderUiAction>(RecorderUiAction.SelectRegion), actions)
    }

    @Test
    fun rendersSelectedSourceAndHoistsRecordAction() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(
                    sources = listOf(
                        RecorderSourceUi(
                            id = "screen:test",
                            displayName = "Test screen",
                            kind = RecorderSourceKind.Screen,
                        ),
                    ),
                    selectedSourceId = "screen:test",
                    outputPath = "recordings/test.mp4",
                ),
                onAction = actions::add,
            )
        }

        onAllNodesWithText("Test screen", useUnmergedTree = true).assertCountEquals(2)
        onNodeWithTag("record-toggle").assertIsEnabled().performClick()

        assertEquals(listOf<RecorderUiAction>(RecorderUiAction.StartRecording), actions)
    }

    @Test
    fun enablesScreenshotForActivePreviewAndHoistsAction() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(previewStatus = PreviewUiStatus.Active),
                onAction = actions::add,
            )
        }

        onNodeWithTag("take-screenshot").assertIsEnabled().performClick()

        assertEquals(listOf<RecorderUiAction>(RecorderUiAction.TakeScreenshot), actions)
    }

    @Test
    fun hoistsExplicitCursorVisibilityToggle() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(captureCursor = true),
                onAction = actions::add,
            )
        }

        onNodeWithTag("capture-cursor").performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf<RecorderUiAction>(RecorderUiAction.SetCaptureCursor(false)), actions)
    }

    @Test
    fun hoistsInputOverlayToggle() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(showInputOverlay = false),
                onAction = actions::add,
            )
        }

        onNodeWithTag("show-input-overlay").performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf<RecorderUiAction>(RecorderUiAction.SetShowInputOverlay(true)), actions)
    }

    @Test
    fun hoistsApplicationVisibilityToggle() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(showApplicationInRecording = false),
                onAction = actions::add,
            )
        }

        onNodeWithTag("show-application-in-recording").performScrollTo().assertIsEnabled().performClick()

        assertEquals(
            listOf<RecorderUiAction>(RecorderUiAction.SetShowApplicationInRecording(true)),
            actions,
        )
    }

    @Test
    fun hoistsCaptureBorderVisibilityToggle() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(showCaptureBorder = true),
                onAction = actions::add,
            )
        }

        onNodeWithTag("show-capture-border").performScrollTo().assertIsEnabled().performClick()

        assertEquals(
            listOf<RecorderUiAction>(RecorderUiAction.SetShowCaptureBorder(false)),
            actions,
        )
    }

    @Test
    fun hoistsExplicitOutputOverwriteToggle() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(outputPath = "recordings/test.mp4", overwriteOutput = false),
                onAction = actions::add,
            )
        }

        onNodeWithTag("overwrite-output").performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf<RecorderUiAction>(RecorderUiAction.SetOverwriteOutput(true)), actions)
    }

    @Test
    fun hoistsSupportedVideoBitrateFromSlider() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(videoBitrateMbps = 41),
                onAction = actions::add,
            )
        }

        onNodeWithTag("video-bitrate")
            .performScrollTo()
            .assertRangeInfoEquals(ProgressBarRangeInfo(41f, 2f..80f, 77))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(42f) }

        assertEquals(listOf<RecorderUiAction>(RecorderUiAction.SetVideoBitrateMbps(42)), actions)
    }

    @Test
    fun hoistsIndependentAudioGainControls() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(
                    microphones = listOf(RecorderMicrophoneUi("mic:test", "Test microphone")),
                    selectedMicrophoneId = "mic:test",
                    systemAudioAvailable = true,
                    microphoneGainPercent = 100,
                    systemAudioGainPercent = 100,
                ),
                onAction = actions::add,
            )
        }

        onNodeWithTag("microphone-gain")
            .performScrollTo()
            .assertRangeInfoEquals(ProgressBarRangeInfo(100f, 0f..200f, 39))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(75f) }
        onNodeWithTag("system-audio-gain")
            .performScrollTo()
            .assertRangeInfoEquals(ProgressBarRangeInfo(100f, 0f..200f, 39))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(40f) }

        assertEquals(
            listOf(
                RecorderUiAction.SetMicrophoneGainPercent(75),
                RecorderUiAction.SetSystemAudioGainPercent(40),
            ),
            actions,
        )
    }

    @Test
    fun opensEditorOnlyFromDedicatedControl() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(
                    outputPath = "recordings/next.mp4",
                    lastOutputPath = "recordings/finished.mp4",
                ),
                onAction = actions::add,
            )
        }

        onNodeWithTag("open-video-editor").performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf<RecorderUiAction>(RecorderUiAction.OpenEditor), actions)
    }

    @Test
    fun opensSavedRecordingFolderFromStatusNotice() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(lastOutputPath = "recordings/finished.mp4"),
                onAction = actions::add,
            )
        }

        onNodeWithTag("open-saved-recording-folder").assertIsEnabled().performClick()

        assertEquals(listOf<RecorderUiAction>(RecorderUiAction.OpenRecordingsFolder), actions)
    }

    @Test
    fun startsReplayOnlyFromDedicatedControl() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(
                    sources = listOf(
                        RecorderSourceUi("screen:test", "Test screen", RecorderSourceKind.Screen),
                    ),
                    selectedSourceId = "screen:test",
                    outputPath = "recordings/next.mp4",
                ),
                onAction = actions::add,
            )
        }

        onNodeWithTag("start-replay").performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf<RecorderUiAction>(RecorderUiAction.StartReplayBuffer), actions)
    }

    @Test
    fun savesAndStopsActiveReplayFromSeparateControls() = runComposeUiTest {
        val actions = mutableListOf<RecorderUiAction>()
        setContent {
            MissionRecorderScreen(
                state = RecorderUiState(
                    replayStatus = ReplayUiStatus.Buffering,
                    replayRetainedMilliseconds = 15_000,
                    replayVideoFrames = 30,
                ),
                onAction = actions::add,
            )
        }

        onNodeWithTag("save-replay").performScrollTo().assertIsEnabled().performClick()
        onNodeWithTag("stop-replay").assertIsEnabled().performClick()

        assertEquals(
            listOf(RecorderUiAction.SaveReplayBuffer, RecorderUiAction.StopReplayBuffer),
            actions,
        )
    }
}
