package io.aequicor.desktop

import io.aequicor.compose.ui.FrameExportCandidate
import io.aequicor.compose.ui.VideoEditorAction
import io.aequicor.editor.EditorExportRequest
import io.aequicor.editor.EditorProject
import io.aequicor.editor.FrameImageFormat
import io.aequicor.editor.ImportantFrameLayout
import io.aequicor.editor.JpegCompression
import io.aequicor.media.desktop.ffmpeg.EditorExportProgress
import io.aequicor.media.desktop.ffmpeg.EditorExportResult
import io.aequicor.media.desktop.ffmpeg.EditorExportedFrame
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
import kotlinx.coroutines.test.StandardTestDispatcher
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
        assertEquals(0, viewModel.state.value.includedFrameExportCount)
        val includedMarker = viewModel.state.value.project.importantFrames.first()
        viewModel.onAction(VideoEditorAction.SetImportantFrameIncluded(includedMarker.id, included = true))
        advanceTimeBy(500)
        runCurrent()

        val reopened = viewModel(
            media = media,
            store = FakeEditorProjectStore(loadedProject = store.saved.last()),
        )
        reopened.open("recording.mp4")
        runCurrent()

        assertEquals(2, reopened.state.value.project.importantFrames.size)
        assertTrue(reopened.state.value.project.importantFrames.first().included)
        assertTrue(!reopened.state.value.project.importantFrames.last().included)
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
    fun sortsRecentMediaPathsByVideoCreationTimeInsteadOfLastOpenTime() = runTest {
        val persistedPaths = mutableListOf<String>()
        val newer = Path.of("newer.mp4").toAbsolutePath().normalize().toString()
        val older = Path.of("older.mp4").toAbsolutePath().normalize().toString()
        val creationTimes = mapOf(newer to 2_000L, older to 1_000L)
        val viewModel = viewModel(
            initialRecentMediaPaths = listOf(older, newer),
            onRecentMediaPath = persistedPaths::add,
            mediaCreationTimeMillis = creationTimes::get,
        )

        assertEquals(listOf(newer, older), viewModel.state.value.recentMediaPaths)

        viewModel.onAction(VideoEditorAction.OpenRecentMedia(older))
        runCurrent()

        assertEquals(listOf(newer, older), viewModel.state.value.recentMediaPaths)
        assertEquals(listOf(older), persistedPaths)
    }

    @Test
    fun reusesFullHdSessionForMainPreviewAndRapidSeeking() = runTest {
        val media = FakeEditorMediaService()
        val viewModel = viewModel(media = media)

        viewModel.open("recording.mp4")
        runCurrent()
        viewModel.onAction(VideoEditorAction.Seek(1_000_000L))
        viewModel.onAction(VideoEditorAction.Seek(2_000_000L))
        viewModel.onAction(VideoEditorAction.Seek(3_000_000L))
        runCurrent()

        assertEquals(listOf(PreviewSessionRequest(maxWidth = 1920, maxHeight = 1080)), media.previewSessionRequests)
        assertEquals(0L, media.previewSessionTimestamps.first())
        assertEquals(3_000_000L, media.previewSessionTimestamps.last())
        assertTrue(2_000_000L !in media.previewSessionTimestamps)
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

        assertEquals(
            listOf(
                PreviewSessionRequest(maxWidth = 1920, maxHeight = 1080),
                PreviewSessionRequest(maxWidth = 1920, maxHeight = 1080),
            ),
            media.previewSessionRequests,
        )
        assertTrue(media.previewSessionTimestamps.size >= 3)
        assertTrue(viewModel.state.value.isPlaying)

        viewModel.onAction(VideoEditorAction.TogglePlayback)
        runCurrent()

        assertEquals(2, media.closedPreviewSessions)
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
        assertEquals(0, viewModel.state.value.includedFrameExportCount)
        assertTrue(media.exportRequests.isEmpty())
        viewModel.state.value.frameExportCandidates
            .filter { it.timelineMicros > 0L }
            .forEach { candidate ->
                viewModel.onAction(
                    VideoEditorAction.SetFrameExportCandidateIncluded(candidate.id, included = true),
                )
            }

        viewModel.onAction(VideoEditorAction.SetFrameOutputFormat(FrameImageFormat.Jpeg))
        viewModel.onAction(VideoEditorAction.SetFrameResolutionPercent(50))
        viewModel.onAction(VideoEditorAction.SetFrameJpegCompression(JpegCompression.High))
        viewModel.onAction(VideoEditorAction.ExportStoryboard(ImportantFrameLayout.ContactSheet))
        runCurrent()
        advanceUntilIdle()

        val request = assertIs<EditorExportRequest.Frames>(media.exportRequests.single())
        assertEquals(ImportantFrameLayout.ContactSheet, request.layout)
        assertEquals(FrameImageFormat.Jpeg, request.outputFormat)
        assertEquals(50, request.resolutionPercent)
        assertEquals(JpegCompression.High, request.jpegCompression)
        assertEquals(listOf(2_000_000L, 4_000_000L), request.timestampsMicros)
        assertNotNull(viewModel.state.value.lastExportPath)
    }

    @Test
    fun bulkSelectionChangesOnlyFramesVisibleThroughTheImportantFilter() = runTest {
        val media = FakeEditorMediaService(storyboardFrameTimestamps = listOf(0L, 4_000_000L))
        val viewModel = viewModel(media = media)
        viewModel.open("recording.mp4")
        runCurrent()
        viewModel.onAction(VideoEditorAction.Seek(2_000_000L))
        runCurrent()
        viewModel.onAction(VideoEditorAction.MarkImportantFrame)
        viewModel.onAction(VideoEditorAction.SetAllFrameExportCandidatesIncluded(true))
        viewModel.onAction(VideoEditorAction.SetShowOnlyImportantFrames(true))

        viewModel.onAction(VideoEditorAction.SetAllFrameExportCandidatesIncluded(false))

        val excludedState = viewModel.state.value
        assertTrue(excludedState.frameExportCandidates.filter(FrameExportCandidate::important).none { it.included })
        assertTrue(excludedState.frameExportCandidates.filterNot(FrameExportCandidate::important).all { it.included })

        viewModel.onAction(VideoEditorAction.SetAllFrameExportCandidatesIncluded(true))

        assertTrue(viewModel.state.value.frameExportCandidates.all { it.included })
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
    fun removesAnImportantFrameFromTheProjectAndStoryboard() = runTest {
        val viewModel = viewModel()
        viewModel.open("recording.mp4")
        runCurrent()
        viewModel.onAction(VideoEditorAction.Seek(3_000_000))
        runCurrent()
        viewModel.onAction(VideoEditorAction.MarkImportantFrame)
        val markerId = viewModel.state.value.project.importantFrames.single().id

        viewModel.onAction(VideoEditorAction.RemoveImportantFrame(markerId))

        assertTrue(viewModel.state.value.project.importantFrames.isEmpty())
        assertTrue(viewModel.state.value.frameExportCandidates.none(FrameExportCandidate::important))
        assertEquals(null, viewModel.state.value.selectedImportantFrameId)
    }

    @Test
    fun copiesCurrentStoryboardSelectionAsSeparateFilesWithoutLeavingTemporaryFile() = runTest {
        val media = FakeEditorMediaService(storyboardFrameTimestamps = listOf(0L, 4_000_000L))
        val clipboard = FakeImageClipboard()
        val viewModel = viewModel(media = media, clipboard = clipboard)
        viewModel.open("recording.mp4")
        runCurrent()
        viewModel.onAction(VideoEditorAction.SetAllFrameExportCandidatesIncluded(true))
        val excluded = viewModel.state.value.frameExportCandidates.first()
        viewModel.onAction(VideoEditorAction.SetFrameExportCandidateIncluded(excluded.id, included = false))
        viewModel.onAction(VideoEditorAction.SetFrameOutputFormat(FrameImageFormat.Jpeg))
        viewModel.onAction(VideoEditorAction.SetFrameResolutionPercent(50))

        viewModel.onAction(
            VideoEditorAction.CopyStoryboardToClipboard(ImportantFrameLayout.SeparatePngFiles),
        )
        runCurrent()

        val request = assertIs<EditorExportRequest.Frames>(media.exportRequests.single())
        assertEquals(ImportantFrameLayout.SeparatePngFiles, request.layout)
        assertEquals(FrameImageFormat.Jpeg, request.outputFormat)
        assertEquals(50, request.resolutionPercent)
        assertEquals(listOf(4_000_000L), request.timestampsMicros)
        val copiedPaths = clipboard.copiedPathBatches.single()
        assertEquals(1, copiedPaths.size)
        assertTrue(copiedPaths.single().endsWith("frame-000001.jpg"))
        assertTrue(!Files.exists(Path.of(request.outputPath).parent))
        assertEquals(null, viewModel.state.value.lastExportPath)
    }

    @Test
    fun copiesSeparateFramesToClipboardInTimelineOrder() = runTest {
        val media = FakeEditorMediaService(
            storyboardFrameTimestamps = listOf(4_000_000L, 0L, 2_000_000L),
            reverseFrameOutputPaths = true,
        )
        val clipboard = FakeImageClipboard()
        val viewModel = viewModel(media = media, clipboard = clipboard)
        viewModel.open("recording.mp4")
        runCurrent()
        viewModel.onAction(VideoEditorAction.SetAllFrameExportCandidatesIncluded(true))

        viewModel.onAction(
            VideoEditorAction.CopyStoryboardToClipboard(ImportantFrameLayout.SeparatePngFiles),
        )
        runCurrent()

        val request = assertIs<EditorExportRequest.Frames>(media.exportRequests.single())
        assertEquals(listOf(0L, 2_000_000L, 4_000_000L), request.timestampsMicros)
        assertEquals(
            listOf("frame-000001.png", "frame-000002.png", "frame-000003.png"),
            clipboard.copiedPathBatches.single().map { path -> Path.of(path).fileName.toString() },
        )
    }

    @Test
    fun copiesCurrentStoryboardSelectionAsOneContactSheet() = runTest {
        val media = FakeEditorMediaService(storyboardFrameTimestamps = listOf(0L, 4_000_000L))
        val clipboard = FakeImageClipboard()
        val viewModel = viewModel(media = media, clipboard = clipboard)
        viewModel.open("recording.mp4")
        runCurrent()
        viewModel.onAction(VideoEditorAction.SetAllFrameExportCandidatesIncluded(true))

        viewModel.onAction(
            VideoEditorAction.CopyStoryboardToClipboard(ImportantFrameLayout.ContactSheet),
        )
        runCurrent()

        val request = assertIs<EditorExportRequest.Frames>(media.exportRequests.single())
        assertEquals(ImportantFrameLayout.ContactSheet, request.layout)
        assertEquals(listOf(0L, 4_000_000L), request.timestampsMicros)
        val copiedPaths = clipboard.copiedPathBatches.single()
        assertEquals(1, copiedPaths.size)
        assertTrue(copiedPaths.single().endsWith("contact-sheet.png"))
        assertTrue(!Files.exists(Path.of(request.outputPath).parent))
        assertEquals(null, viewModel.state.value.lastExportPath)
    }

    private fun kotlinx.coroutines.test.TestScope.viewModel(
        media: FakeEditorMediaService = FakeEditorMediaService(),
        store: FakeEditorProjectStore = FakeEditorProjectStore(),
        clipboard: DesktopImageClipboard = FakeImageClipboard(),
        initialRecentMediaPaths: List<String> = emptyList(),
        onRecentMediaPath: (String) -> Unit = {},
        mediaCreationTimeMillis: (String) -> Long? = { null },
    ): VideoEditorViewModel = VideoEditorViewModel(
        scope = backgroundScope,
        mediaService = media,
        projectStore = store,
        fileSelector = FakeEditorFileSelector(),
        audioPlayer = FakeEditorAudioPlayer(),
        imageClipboard = clipboard,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        initialRecentMediaPaths = initialRecentMediaPaths,
        onRecentMediaPath = onRecentMediaPath,
        mediaCreationTimeMillis = mediaCreationTimeMillis,
        idFactory = generateSequence(1) { it + 1 }.iterator().let { ids -> { ids.next().toString() } },
        nanoTime = { testScheduler.currentTime * 1_000_000L },
    )
}

private class FakeEditorMediaService(
    private val recordedImportantFrameTimestamps: List<Long> = emptyList(),
    private val storyboardFrameTimestamps: List<Long> = listOf(0L, 5_000_000L),
    private val previewRenderDelayMillis: Long = 0L,
    private val reverseFrameOutputPaths: Boolean = false,
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
        val exportedFrames = if (
            request is EditorExportRequest.Frames && request.layout == ImportantFrameLayout.SeparatePngFiles
        ) {
            val extension = when (request.outputFormat) {
                FrameImageFormat.Png -> "png"
                FrameImageFormat.Jpeg -> "jpg"
            }
            request.timestampsMicros.distinct().sorted().mapIndexed { index, timestamp ->
                EditorExportedFrame(
                    timelineMicros = timestamp,
                    outputPath = Path.of(request.outputPath)
                        .resolve("frame-${(index + 1).toString().padStart(6, '0')}.$extension")
                        .toString(),
                )
            }
        } else {
            emptyList()
        }.let { frames -> if (reverseFrameOutputPaths) frames.reversed() else frames }
        val outputPaths = if (exportedFrames.isEmpty()) {
            listOf(request.outputPath)
        } else {
            exportedFrames.map(EditorExportedFrame::outputPath)
        }
        return EditorExportResult(
            outputPath = request.outputPath,
            renderedFrames = if (exportedFrames.isEmpty()) 1 else exportedFrames.size,
            outputPaths = outputPaths,
            exportedFrames = exportedFrames,
        )
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
    override suspend fun chooseVideoFile(initialPath: String?): String? = null
    override suspend fun chooseMediaFiles(): List<String> = emptyList()
    override suspend fun chooseReplacementFile(currentPath: String): String? = null
    override suspend fun chooseVideoOutput(primaryMediaPath: String): String = "edited.mp4"
    override suspend fun chooseFrameOutput(
        primaryMediaPath: String,
        layout: ImportantFrameLayout,
        outputFormat: FrameImageFormat,
    ): String = if (layout == ImportantFrameLayout.ContactSheet) {
        when (outputFormat) {
            FrameImageFormat.Png -> "frames.png"
            FrameImageFormat.Jpeg -> "frames.jpg"
        }
    } else {
        "frames"
    }
}

private class FakeEditorAudioPlayer : DesktopEditorAudioPlayer {
    override suspend fun play(audio: EditorPreviewAudio, startMicros: Long) = Unit
    override fun stop() = Unit
}

private class FakeImageClipboard : DesktopImageClipboard {
    val copiedPathBatches = mutableListOf<List<String>>()

    override suspend fun copyImages(paths: List<String>) {
        copiedPathBatches += paths
    }
}
