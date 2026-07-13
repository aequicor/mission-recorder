package io.aequicor.desktop

import io.aequicor.compose.ui.VideoEditorAction
import io.aequicor.editor.EditorExportRequest
import io.aequicor.editor.EditorProject
import io.aequicor.editor.ImportantFrameLayout
import io.aequicor.media.desktop.ffmpeg.EditorExportProgress
import io.aequicor.media.desktop.ffmpeg.EditorExportResult
import io.aequicor.media.desktop.ffmpeg.EditorMediaProbe
import io.aequicor.media.desktop.ffmpeg.EditorMediaService
import io.aequicor.media.desktop.ffmpeg.EditorPreviewAudio
import io.aequicor.media.desktop.ffmpeg.EditorPreviewFrame
import io.aequicor.media.desktop.ffmpeg.EditorPreviewSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VideoEditorViewModelTest {
    @Test
    fun importsImportantFramesMarkedDuringRecordingWithoutResettingSavedSelection() = runTest {
        val media = FakeEditorMediaService(
            recordedImportantFrameTimestamps = listOf(7_000_000L, 2_000_000L, 7_000_000L),
        )
        val store = FakeEditorProjectStore()
        val viewModel = viewModel(media = media, store = store)

        viewModel.open("recording.mp4")
        runCurrent()

        assertEquals(
            listOf(2_000_000L, 7_000_000L),
            viewModel.state.value.project.importantFrames.map { it.timelineMicros },
        )
        val excludedMarker = viewModel.state.value.project.importantFrames.first()
        viewModel.onAction(VideoEditorAction.SetImportantFrameIncluded(excludedMarker.id, included = false))
        advanceTimeBy(500)
        runCurrent()

        val reopened = viewModel(
            media = media,
            store = FakeEditorProjectStore(loadedProject = store.saved.last()),
        )
        reopened.open("recording.mp4")
        runCurrent()

        assertEquals(2, reopened.state.value.project.importantFrames.size)
        assertTrue(!reopened.state.value.project.importantFrames.first().included)
    }

    @Test
    fun opensPrimaryMediaEditsTimelineAndAutosavesAfterDebounce() = runTest {
        val store = FakeEditorProjectStore()
        val viewModel = viewModel(store = store)

        viewModel.open("recording.mp4")
        runCurrent()
        val clip = assertIs<io.aequicor.editor.EditorClip.Media>(
            viewModel.state.value.project.tracks.single().clips.single(),
        )
        viewModel.onAction(VideoEditorAction.Seek(5_000_000))
        runCurrent()
        viewModel.onAction(VideoEditorAction.SelectClip(clip.id))
        viewModel.onAction(VideoEditorAction.SplitSelectedClip)
        viewModel.onAction(VideoEditorAction.MarkImportantFrame)

        assertEquals(2, viewModel.state.value.project.tracks.single().clips.size)
        assertEquals(listOf(5_000_000L), viewModel.state.value.project.importantFrames.map { it.timelineMicros })
        assertTrue(viewModel.state.value.canUndo)
        advanceTimeBy(500)
        runCurrent()

        assertEquals(1, store.saved.size)
        assertEquals(2, store.saved.single().tracks.single().clips.size)
    }

    @Test
    fun requestsFullHdBoundedMainPreview() = runTest {
        val media = FakeEditorMediaService()
        val viewModel = viewModel(media = media)

        viewModel.open("recording.mp4")
        runCurrent()

        assertTrue(media.previewRequests.any { it.maxWidth == 1920 && it.maxHeight == 1080 })
    }

    @Test
    fun playbackReusesOneDecoderSessionAndAdvancesAsVideo() = runTest {
        val media = FakeEditorMediaService(previewRenderDelayMillis = 20L)
        val viewModel = viewModel(media = media)
        viewModel.open("recording.mp4")
        runCurrent()

        viewModel.onAction(VideoEditorAction.TogglePlayback)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()

        assertEquals(listOf(PreviewSessionRequest(maxWidth = 960, maxHeight = 540)), media.previewSessionRequests)
        assertTrue(media.previewSessionTimestamps.size >= 3)
        assertTrue(viewModel.state.value.isPlaying)

        viewModel.onAction(VideoEditorAction.TogglePlayback)
        runCurrent()

        assertEquals(1, media.closedPreviewSessions)
        assertTrue(!viewModel.state.value.isPlaying)
    }

    @Test
    fun undoRedoAndStoryboardExportUseCurrentSelection() = runTest {
        val media = FakeEditorMediaService(storyboardFrameTimestamps = listOf(0L, 4_000_000L))
        val viewModel = viewModel(media = media)
        viewModel.open("recording.mp4")
        runCurrent()
        viewModel.onAction(VideoEditorAction.Seek(2_000_000))
        runCurrent()
        viewModel.onAction(VideoEditorAction.MarkImportantFrame)
        viewModel.onAction(VideoEditorAction.Undo)
        assertTrue(viewModel.state.value.project.importantFrames.isEmpty())
        viewModel.onAction(VideoEditorAction.Redo)

        assertEquals(
            listOf(0L, 2_000_000L, 4_000_000L),
            viewModel.state.value.frameExportCandidates.map { it.timelineMicros },
        )
        assertEquals(3, viewModel.state.value.includedFrameExportCount)
        assertTrue(media.exportRequests.isEmpty())
        val firstDefault = viewModel.state.value.frameExportCandidates.first()
        viewModel.onAction(
            VideoEditorAction.SetFrameExportCandidateIncluded(firstDefault.id, included = false),
        )

        viewModel.onAction(VideoEditorAction.ExportStoryboard(ImportantFrameLayout.ContactSheet))
        runCurrent()
        advanceUntilIdle()

        val request = assertIs<EditorExportRequest.Frames>(media.exportRequests.single())
        assertEquals(ImportantFrameLayout.ContactSheet, request.layout)
        assertEquals(listOf(2_000_000L, 4_000_000L), request.timestampsMicros)
        assertNotNull(viewModel.state.value.lastExportPath)
    }

    @Test
    fun persistentStoryboardAddsCurrentFrameAndReincludesExistingMarker() = runTest {
        val media = FakeEditorMediaService()
        val viewModel = viewModel(media = media)
        viewModel.open("recording.mp4")
        runCurrent()
        viewModel.onAction(VideoEditorAction.Seek(3_000_000))
        runCurrent()
        viewModel.onAction(VideoEditorAction.MarkImportantFrame)
        val markerId = viewModel.state.value.project.importantFrames.single().id
        viewModel.onAction(VideoEditorAction.SetImportantFrameIncluded(markerId, included = false))

        viewModel.onAction(VideoEditorAction.AddCurrentFrameForExport)

        val marker = viewModel.state.value.project.importantFrames.single()
        val candidate = viewModel.state.value.frameExportCandidates.single { it.timelineMicros == marker.timelineMicros }
        assertEquals(markerId, marker.id)
        assertTrue(marker.included)
        assertTrue(candidate.included)
        assertTrue(candidate.important)
        assertEquals(candidate.id, viewModel.state.value.selectedFrameExportCandidateId)
        assertTrue(media.exportRequests.isEmpty())
    }

    @Test
    fun copiesCurrentStoryboardSelectionToClipboardWithoutLeavingTemporaryFile() = runTest {
        val media = FakeEditorMediaService(storyboardFrameTimestamps = listOf(0L, 4_000_000L))
        val clipboard = FakeImageClipboard()
        val viewModel = viewModel(media = media, clipboard = clipboard)
        viewModel.open("recording.mp4")
        runCurrent()
        val excluded = viewModel.state.value.frameExportCandidates.first()
        viewModel.onAction(VideoEditorAction.SetFrameExportCandidateIncluded(excluded.id, included = false))

        viewModel.onAction(VideoEditorAction.CopyStoryboardToClipboard)
        runCurrent()

        val request = assertIs<EditorExportRequest.Frames>(media.exportRequests.single())
        assertEquals(ImportantFrameLayout.ContactSheet, request.layout)
        assertEquals(listOf(4_000_000L), request.timestampsMicros)
        assertEquals(request.outputPath, clipboard.copiedPaths.single())
        assertTrue(!Files.exists(Path.of(request.outputPath)))
        assertEquals(null, viewModel.state.value.lastExportPath)
    }

    private fun kotlinx.coroutines.test.TestScope.viewModel(
        media: FakeEditorMediaService = FakeEditorMediaService(),
        store: FakeEditorProjectStore = FakeEditorProjectStore(),
        clipboard: DesktopImageClipboard = FakeImageClipboard(),
    ): VideoEditorViewModel = VideoEditorViewModel(
        scope = backgroundScope,
        mediaService = media,
        projectStore = store,
        fileSelector = FakeEditorFileSelector(),
        audioPlayer = FakeEditorAudioPlayer(),
        imageClipboard = clipboard,
        idFactory = generateSequence(1) { it + 1 }.iterator().let { ids -> { ids.next().toString() } },
        nanoTime = { testScheduler.currentTime * 1_000_000L },
    )
}

private class FakeEditorMediaService(
    private val recordedImportantFrameTimestamps: List<Long> = emptyList(),
    private val storyboardFrameTimestamps: List<Long> = listOf(0L, 5_000_000L),
    private val previewRenderDelayMillis: Long = 0L,
) : EditorMediaService {
    val exportRequests = mutableListOf<EditorExportRequest>()
    val previewRequests = mutableListOf<PreviewRequest>()
    val previewSessionRequests = mutableListOf<PreviewSessionRequest>()
    val previewSessionTimestamps = mutableListOf<Long>()
    var closedPreviewSessions = 0

    override suspend fun probe(path: String): EditorMediaProbe = EditorMediaProbe(
        kind = io.aequicor.editor.MediaAssetKind.Video,
        durationMicros = 10_000_000,
        width = 1280,
        height = 720,
        frameRate = 30.0,
        hasAudio = true,
    )

    override suspend fun findImportantFrameTimestamps(path: String): List<Long> =
        recordedImportantFrameTimestamps

    override suspend fun createStoryboardFrameTimestamps(
        project: EditorProject,
        intervalMicros: Long,
        maxFrames: Int,
    ): List<Long> = storyboardFrameTimestamps

    override suspend fun renderPreview(
        project: EditorProject,
        timelineMicros: Long,
        maxWidth: Int,
        maxHeight: Int,
    ): EditorPreviewFrame {
        previewRequests += PreviewRequest(
            timelineMicros = timelineMicros,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
        )
        return EditorPreviewFrame(1, 1, byteArrayOf(0, 0, 0, -1))
    }

    override suspend fun createPreviewSession(
        project: EditorProject,
        maxWidth: Int,
        maxHeight: Int,
    ): EditorPreviewSession {
        previewSessionRequests += PreviewSessionRequest(maxWidth, maxHeight)
        return object : EditorPreviewSession {
            override suspend fun render(timelineMicros: Long): EditorPreviewFrame {
                previewSessionTimestamps += timelineMicros
                delay(previewRenderDelayMillis)
                return EditorPreviewFrame(1, 1, byteArrayOf(0, 0, 0, -1))
            }

            override suspend fun close() {
                closedPreviewSessions += 1
            }
        }
    }

    override suspend fun renderPreviewAudio(project: EditorProject): EditorPreviewAudio =
        EditorPreviewAudio(48_000, 2, FloatArray(0))

    override suspend fun export(
        request: EditorExportRequest,
        onProgress: (EditorExportProgress) -> Unit,
    ): EditorExportResult {
        exportRequests += request
        onProgress(EditorExportProgress(1, 1))
        return EditorExportResult(request.outputPath, 1)
    }
}

private data class PreviewRequest(
    val timelineMicros: Long,
    val maxWidth: Int,
    val maxHeight: Int,
)

private data class PreviewSessionRequest(
    val maxWidth: Int,
    val maxHeight: Int,
)

private class FakeEditorProjectStore(
    private val loadedProject: EditorProject? = null,
) : DesktopEditorProjectStore {
    val saved = mutableListOf<EditorProject>()
    override suspend fun load(primaryMediaPath: String): EditorProject? = loadedProject
    override suspend fun save(primaryMediaPath: String, project: EditorProject): String {
        saved += project
        return "$primaryMediaPath.mission-recorder-edit.json"
    }
}

private class FakeEditorFileSelector : DesktopEditorFileSelector {
    override suspend fun chooseMediaFiles(): List<String> = emptyList()
    override suspend fun chooseReplacementFile(currentPath: String): String? = null
    override suspend fun chooseVideoOutput(primaryMediaPath: String): String = "edited.mp4"
    override suspend fun chooseFrameOutput(primaryMediaPath: String, layout: ImportantFrameLayout): String =
        if (layout == ImportantFrameLayout.ContactSheet) "frames.png" else "frames"
}

private class FakeEditorAudioPlayer : DesktopEditorAudioPlayer {
    override suspend fun play(audio: EditorPreviewAudio, startMicros: Long) = Unit
    override fun stop() = Unit
}

private class FakeImageClipboard : DesktopImageClipboard {
    val copiedPaths = mutableListOf<String>()

    override suspend fun copyPng(path: String) {
        copiedPaths += path
    }
}
