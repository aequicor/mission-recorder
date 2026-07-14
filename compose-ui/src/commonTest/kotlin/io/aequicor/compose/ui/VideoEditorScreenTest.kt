package io.aequicor.compose.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.dragAndDrop
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.aequicor.editor.EditorClip
import io.aequicor.editor.EditorClipId
import io.aequicor.editor.EditorProject
import io.aequicor.editor.EditorTrack
import io.aequicor.editor.EditorTrackId
import io.aequicor.editor.EditorTrackKind
import io.aequicor.editor.FrameImageFormat
import io.aequicor.editor.ImportantFrameId
import io.aequicor.editor.ImportantFrameMarker
import io.aequicor.editor.JpegCompression
import io.aequicor.editor.MediaAsset
import io.aequicor.editor.MediaAssetId
import io.aequicor.editor.MediaAssetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class VideoEditorScreenTest {
    @Test
    fun exposesNavigationMediaAndFourStoryboardExportActions() = runComposeUiTest {
        val actions = mutableListOf<VideoEditorAction>()
        val playhead = mutableStateOf(0L)
        val preview = mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        setContent {
            VideoEditorScreen(
                state = VideoEditorUiState(
                    project = project(),
                    frameExportCandidates = listOf(
                        FrameExportCandidate(ImportantFrameId("storyboard:0"), 0L, included = true),
                    ),
                    frameOutputFormat = FrameImageFormat.Jpeg,
                ),
                playheadMicros = playhead,
                previewImage = preview,
                onAction = actions::add,
            )
        }

        onNodeWithTag("editor-add-media").assertIsEnabled().performClick()
        val singleFile = onNodeWithTag("editor-export-contact-sheet").assertIsEnabled()
        val singleFileClipboard = onNodeWithTag("editor-copy-contact-sheet").assertIsEnabled()
        val multipleFiles = onNodeWithTag("editor-export-frames").assertIsEnabled()
        val multipleFilesClipboard = onNodeWithTag("editor-copy-frames").assertIsEnabled()
        val singleFileBounds = singleFile.fetchSemanticsNode().boundsInRoot
        val singleFileClipboardBounds = singleFileClipboard.fetchSemanticsNode().boundsInRoot
        val multipleFilesBounds = multipleFiles.fetchSemanticsNode().boundsInRoot
        val multipleFilesClipboardBounds = multipleFilesClipboard.fetchSemanticsNode().boundsInRoot
        assertTrue(singleFileBounds.right <= singleFileClipboardBounds.left)
        assertTrue(multipleFilesBounds.right <= multipleFilesClipboardBounds.left)
        assertTrue(
            multipleFilesBounds.top >= maxOf(singleFileBounds.bottom, singleFileClipboardBounds.bottom),
        )
        onNodeWithTag("editor-export-format-toggle").performClick()
        onNodeWithTag("editor-export-format-jpeg").performClick()
        onNodeWithTag("editor-export-resolution-toggle").performClick()
        onNodeWithTag("editor-export-resolution-50").performClick()
        onNodeWithTag("editor-export-compression-toggle").performClick()
        onNodeWithTag("editor-export-compression-high").performClick()
        singleFile.performClick()
        singleFileClipboard.performClick()
        multipleFiles.performClick()
        multipleFilesClipboard.performClick()
        onNodeWithTag("editor-back").performClick()

        assertEquals(
            listOf(
                VideoEditorAction.AddMedia,
                VideoEditorAction.SetFrameOutputFormat(FrameImageFormat.Jpeg),
                VideoEditorAction.SetFrameResolutionPercent(50),
                VideoEditorAction.SetFrameJpegCompression(JpegCompression.High),
                VideoEditorAction.ExportStoryboard(io.aequicor.editor.ImportantFrameLayout.ContactSheet),
                VideoEditorAction.CopyStoryboardToClipboard(io.aequicor.editor.ImportantFrameLayout.ContactSheet),
                VideoEditorAction.ExportStoryboard(io.aequicor.editor.ImportantFrameLayout.SeparatePngFiles),
                VideoEditorAction.CopyStoryboardToClipboard(
                    io.aequicor.editor.ImportantFrameLayout.SeparatePngFiles,
                ),
                VideoEditorAction.BackToRecorder,
            ),
            actions,
        )
    }

    @Test
    fun hidesJpegCompressionForPngOutput() = runComposeUiTest {
        setContent {
            VideoEditorScreen(
                state = VideoEditorUiState(
                    project = project(),
                    frameOutputFormat = FrameImageFormat.Png,
                ),
                playheadMicros = mutableStateOf(0L),
                previewImage = mutableStateOf(null),
                onAction = {},
            )
        }

        onNodeWithTag("editor-export-format").assertIsDisplayed()
        onNodeWithTag("editor-export-resolution").assertIsDisplayed()
        onAllNodesWithTag("editor-export-compression").assertCountEquals(0)
    }

    @Test
    fun transportHoistsPlaybackSeekAndFrameActions() = runComposeUiTest {
        val actions = mutableListOf<VideoEditorAction>()
        setContent {
            VideoEditorScreen(
                state = VideoEditorUiState(project()),
                playheadMicros = mutableStateOf(1_000_000),
                previewImage = mutableStateOf(null),
                onAction = actions::add,
            )
        }

        onNodeWithTag("editor-playback").performClick()
        onNodeWithTag("editor-previous-frame").performClick()
        onNodeWithTag("editor-rewind-10").performClick()
        onNodeWithTag("editor-forward-10").performClick()
        onNodeWithTag("editor-next-frame").performClick()
        onNodeWithTag("editor-mark-frame").performClick()

        assertEquals(
            listOf(
                VideoEditorAction.TogglePlayback,
                VideoEditorAction.StepFrames(-1),
                VideoEditorAction.Seek(-9_000_000),
                VideoEditorAction.Seek(11_000_000),
                VideoEditorAction.StepFrames(1),
                VideoEditorAction.MarkImportantFrame,
            ),
            actions,
        )
    }

    @Test
    fun togglesAllStoryboardFramesFromToolbar() = runComposeUiTest {
        val actions = mutableListOf<VideoEditorAction>()
        setContent {
            VideoEditorScreen(
                state = VideoEditorUiState(
                    project = project(),
                    frameExportCandidates = listOf(
                        FrameExportCandidate(ImportantFrameId("storyboard:0"), 0L, included = false),
                    ),
                ),
                playheadMicros = mutableStateOf(0L),
                previewImage = mutableStateOf(null),
                onAction = actions::add,
            )
        }

        onNodeWithTag("editor-storyboard-toggle-all").performClick()

        assertEquals(
            listOf<VideoEditorAction>(VideoEditorAction.SetAllFrameExportCandidatesIncluded(true)),
            actions,
        )
    }

    @Test
    fun togglesOnlyVisibleImportantFramesFromToolbar() = runComposeUiTest {
        val actions = mutableListOf<VideoEditorAction>()
        setContent {
            VideoEditorScreen(
                state = VideoEditorUiState(
                    project = project(),
                    frameExportCandidates = listOf(
                        FrameExportCandidate(ImportantFrameId("storyboard:0"), 0L, included = false),
                        FrameExportCandidate(
                            ImportantFrameId("frame:1"),
                            1_000_000L,
                            included = true,
                            important = true,
                        ),
                    ),
                    showOnlyImportantFrames = true,
                ),
                playheadMicros = mutableStateOf(0L),
                previewImage = mutableStateOf(null),
                onAction = actions::add,
            )
        }

        onNodeWithTag("editor-storyboard-toggle-all-checkbox").assertIsOn()
        onNodeWithTag("editor-storyboard-toggle-all").performClick()

        assertEquals(
            listOf<VideoEditorAction>(VideoEditorAction.SetAllFrameExportCandidatesIncluded(false)),
            actions,
        )
    }

    @Test
    fun storyboardFiltersSelectsAddsAndExportsFrames() = runComposeUiTest {
        val actions = mutableListOf<VideoEditorAction>()
        val storyboardCandidate = FrameExportCandidate(
            id = ImportantFrameId("storyboard:0"),
            timelineMicros = 0L,
            included = true,
        )
        val importantCandidate = FrameExportCandidate(
            id = ImportantFrameId("frame:1"),
            timelineMicros = 1_000_000L,
            included = true,
            important = true,
        )
        setContent {
            VideoEditorScreen(
                state = VideoEditorUiState(
                    project = project(),
                    frameExportCandidates = listOf(storyboardCandidate, importantCandidate),
                ),
                playheadMicros = mutableStateOf(1_000_000),
                previewImage = mutableStateOf(null),
                onAction = actions::add,
            )
        }

        onNodeWithTag("editor-storyboard-list").assertIsDisplayed()
        onNodeWithTag("editor-storyboard-filter-important").performClick()
        onNodeWithTag("editor-storyboard-select-frame:1").performClick()
        onNodeWithTag("editor-add-current-frame").performClick()
        onNodeWithTag("editor-export-contact-sheet").performClick()

        assertEquals(
            listOf(
                VideoEditorAction.SetShowOnlyImportantFrames(true),
                VideoEditorAction.SetFrameExportCandidateIncluded(
                    ImportantFrameId("frame:1"),
                    included = false,
                ),
                VideoEditorAction.AddCurrentFrameForExport,
                VideoEditorAction.ExportStoryboard(io.aequicor.editor.ImportantFrameLayout.ContactSheet),
            ),
            actions,
        )
    }

    @Test
    fun rendersDenseTenTrackTimeline() = runComposeUiTest {
        val project = denseProject()
        setContent {
            VideoEditorScreen(
                state = VideoEditorUiState(project),
                playheadMicros = mutableStateOf(0L),
                previewImage = mutableStateOf(null),
                onAction = {},
            )
        }

        onNodeWithTag("editor-timeline").assertIsDisplayed()
    }

    @Test
    fun draggingTimelineWithMouseSeeksContinuously() = runComposeUiTest {
        val actions = mutableListOf<VideoEditorAction>()
        setContent {
            VideoEditorScreen(
                state = VideoEditorUiState(project()),
                playheadMicros = mutableStateOf(1_000_000L),
                previewImage = mutableStateOf(null),
                onAction = actions::add,
            )
        }

        onNodeWithTag("editor-timeline").performMouseInput {
            dragAndDrop(
                start = Offset(width * 0.2f, height * 0.15f),
                end = Offset(width * 0.8f, height * 0.15f),
                durationMillis = 160L,
            )
        }

        val seekActions = actions.filterIsInstance<VideoEditorAction.Seek>()
        assertTrue(seekActions.size > 2)
        assertTrue(seekActions.zipWithNext().all { (previous, next) -> previous.timelineMicros <= next.timelineMicros })
        assertTrue(seekActions.last().timelineMicros in 3_500_000L..4_500_000L)
    }

    @Test
    fun exposesImportantFramesInStoryboard() = runComposeUiTest {
        setContent {
            VideoEditorScreen(
                state = VideoEditorUiState(
                    project = project(),
                    frameExportCandidates = listOf(
                        FrameExportCandidate(ImportantFrameId("frame:1"), 1_000_000, important = true),
                    ),
                ),
                playheadMicros = mutableStateOf(1_000_000),
                previewImage = mutableStateOf(null),
                onAction = {},
            )
        }

        onNodeWithTag("editor-storyboard-row-frame:1").assertIsDisplayed()
    }

    @Test
    fun storyboardFrameSelectionIsHoisted() = runComposeUiTest {
        val actions = mutableListOf<VideoEditorAction>()
        setContent {
            VideoEditorScreen(
                state = VideoEditorUiState(
                    project = project(),
                    frameExportCandidates = listOf(
                        FrameExportCandidate(ImportantFrameId("frame:1"), 1_000_000, important = true),
                    ),
                ),
                playheadMicros = mutableStateOf(0L),
                previewImage = mutableStateOf(null),
                onAction = actions::add,
            )
        }

        onNodeWithTag("editor-storyboard-row-frame:1").performClick()

        assertEquals(
            listOf<VideoEditorAction>(VideoEditorAction.SelectFrameExportCandidate(ImportantFrameId("frame:1"))),
            actions,
        )
    }

    @Test
    fun importantFrameDeleteButtonRemovesTheUnderlyingMarker() = runComposeUiTest {
        val actions = mutableListOf<VideoEditorAction>()
        setContent {
            VideoEditorScreen(
                state = VideoEditorUiState(
                    project = project(),
                    frameExportCandidates = listOf(
                        FrameExportCandidate(
                            id = ImportantFrameId("storyboard:1000000"),
                            timelineMicros = 1_000_000,
                            important = true,
                        ),
                    ),
                ),
                playheadMicros = mutableStateOf(0L),
                previewImage = mutableStateOf(null),
                onAction = actions::add,
            )
        }

        onNodeWithTag("editor-storyboard-remove-important-storyboard:1000000").performClick()

        assertEquals(
            listOf<VideoEditorAction>(VideoEditorAction.RemoveImportantFrame(ImportantFrameId("frame:1"))),
            actions,
        )
    }

    @Test
    fun displaysAndOpensRecordingsFromTheHistoryPanel() = runComposeUiTest {
        val actions = mutableListOf<VideoEditorAction>()
        setContent {
            VideoEditorScreen(
                state = VideoEditorUiState(
                    project = project(),
                    recentMediaPaths = listOf("recordings/last.mp4", "recordings/older.mp4"),
                ),
                playheadMicros = mutableStateOf(0L),
                previewImage = mutableStateOf(null),
                onAction = actions::add,
            )
        }

        onNodeWithTag("editor-recording-history").assertIsDisplayed()
        onNodeWithTag("editor-recording-history-item-0").assertIsEnabled().performClick()

        assertEquals(
            listOf<VideoEditorAction>(VideoEditorAction.OpenRecentMedia("recordings/last.mp4")),
            actions,
        )
    }

    private fun project(): EditorProject {
        val assetId = MediaAssetId("asset:1")
        return EditorProject(
            name = "Test edit",
            primaryAssetId = assetId,
            canvasWidth = 1280,
            canvasHeight = 720,
            frameRate = 30,
            assets = listOf(
                MediaAsset(
                    id = assetId,
                    path = "recording.mp4",
                    displayName = "recording.mp4",
                    kind = MediaAssetKind.Video,
                    durationMicros = 5_000_000,
                    width = 1280,
                    height = 720,
                    frameRate = 30.0,
                ),
            ),
            tracks = listOf(
                EditorTrack(
                    id = EditorTrackId("track:1"),
                    name = "Video 1",
                    kind = EditorTrackKind.Video,
                    clips = listOf(EditorClip.Media(EditorClipId("clip:1"), assetId, 0L, 5_000_000L)),
                ),
            ),
            importantFrames = listOf(ImportantFrameMarker(ImportantFrameId("frame:1"), 1_000_000)),
        )
    }

    private fun denseProject(): EditorProject {
        val assetId = MediaAssetId("asset:dense")
        return EditorProject(
            name = "Dense edit",
            primaryAssetId = assetId,
            canvasWidth = 1920,
            canvasHeight = 1080,
            frameRate = 30,
            assets = listOf(
                MediaAsset(
                    id = assetId,
                    path = "dense.mp4",
                    displayName = "dense.mp4",
                    kind = MediaAssetKind.Video,
                    durationMicros = 5_000_000,
                    width = 1920,
                    height = 1080,
                    frameRate = 30.0,
                ),
            ),
            tracks = List(10) { trackIndex ->
                EditorTrack(
                    id = EditorTrackId("track:$trackIndex"),
                    name = "Video ${trackIndex + 1}",
                    kind = EditorTrackKind.Video,
                    clips = List(50) { clipIndex ->
                        EditorClip.Media(
                            id = EditorClipId("clip:$trackIndex:$clipIndex"),
                            assetId = assetId,
                            timelineStartMicros = clipIndex * 100_000L,
                            durationMicros = 100_000L,
                            sourceStartMicros = clipIndex * 100_000L,
                        )
                    },
                )
            },
        )
    }
}
