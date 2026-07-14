package io.aequicor.desktop

import io.aequicor.compose.ui.EditorAutosaveStatus
import io.aequicor.compose.ui.EditorPreviewStatus
import io.aequicor.compose.ui.FrameExportCandidate
import io.aequicor.compose.ui.VideoEditorAction
import io.aequicor.compose.ui.VideoEditorUiState
import io.aequicor.editor.EditorAction
import io.aequicor.editor.EditorClip
import io.aequicor.editor.EditorClipId
import io.aequicor.editor.EditorHistory
import io.aequicor.editor.EditorProject
import io.aequicor.editor.EditorReduceResult
import io.aequicor.editor.EditorReducer
import io.aequicor.editor.EditorTrack
import io.aequicor.editor.EditorTrackId
import io.aequicor.editor.EditorTrackKind
import io.aequicor.editor.FrameImageFormat
import io.aequicor.editor.ImportantFrameId
import io.aequicor.editor.ImportantFrameLayout
import io.aequicor.editor.ImportantFrameMarker
import io.aequicor.editor.MediaAsset
import io.aequicor.editor.MediaAssetId
import io.aequicor.editor.MediaAssetKind
import io.aequicor.media.desktop.ffmpeg.EditorExportProgress
import io.aequicor.media.desktop.ffmpeg.EditorExportResult
import io.aequicor.media.desktop.ffmpeg.EditorMediaProbe
import io.aequicor.media.desktop.ffmpeg.EditorMediaService
import io.aequicor.media.desktop.ffmpeg.EditorPreviewAudio
import io.aequicor.media.desktop.ffmpeg.EditorPreviewFrame
import io.aequicor.media.desktop.ffmpeg.EditorPreviewSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Comparator
import java.util.UUID
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

internal class VideoEditorViewModel(
    private val scope: CoroutineScope,
    private val mediaService: EditorMediaService,
    private val projectStore: DesktopEditorProjectStore,
    private val fileSelector: DesktopEditorFileSelector,
    private val audioPlayer: DesktopEditorAudioPlayer,
    private val imageClipboard: DesktopImageClipboard,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    initialFrameLayout: ImportantFrameLayout = ImportantFrameLayout.SeparatePngFiles,
    initialRecentMediaPaths: List<String> = emptyList(),
    private val onRecentMediaPath: (String) -> Unit = {},
    private val mediaCreationTimeMillis: (String) -> Long? = ::readMediaCreationTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var history = EditorHistory(emptyEditorProject())
    private val mutableState = MutableStateFlow(
        VideoEditorUiState(
            project = history.project,
            frameLayout = initialFrameLayout,
            recentMediaPaths = sortMediaPathsByCreationTime(
                paths = initialRecentMediaPaths,
                creationTimeMillis = mediaCreationTimeMillis,
            ),
        ),
    )
    private val mutablePlayheadMicros = MutableStateFlow(0L)
    private val mutablePreviewFrame = MutableStateFlow<EditorPreviewFrame?>(null)
    private val mutableImportantFramePreviews = MutableStateFlow<Map<ImportantFrameId, EditorPreviewFrame>>(emptyMap())
    private var previewJob: Job? = null
    private var thumbnailJob: Job? = null
    private var playbackJob: Job? = null
    private var audioJob: Job? = null
    private var exportJob: Job? = null
    private var frameExportPreparationJob: Job? = null
    private var autosaveJob: Job? = null
    private var cachedAudioProject: EditorProject? = null
    private var cachedAudio: EditorPreviewAudio? = null
    private var previewProject: EditorProject? = null
    private var previewRequests: Channel<Long>? = null

    val state: StateFlow<VideoEditorUiState> = mutableState.asStateFlow()
    val playheadMicros: StateFlow<Long> = mutablePlayheadMicros.asStateFlow()
    val previewFrame: StateFlow<EditorPreviewFrame?> = mutablePreviewFrame.asStateFlow()
    val importantFramePreviews: StateFlow<Map<ImportantFrameId, EditorPreviewFrame>> =
        mutableImportantFramePreviews.asStateFlow()

    fun open(primaryMediaPath: String?) {
        stopPlayback()
        exportJob?.cancel()
        val normalized = primaryMediaPath?.trim()?.takeIf(String::isNotEmpty)
        if (normalized == null) {
            replaceProject(emptyEditorProject())
            return
        }
        scope.launch {
            mutableState.update { it.copy(previewStatus = EditorPreviewStatus.Rendering, errorMessage = null) }
            try {
                val project = projectStore.load(normalized) ?: createProjectFromPrimaryMedia(normalized)
                replaceProject(project)
                registerRecentMediaPath(normalized)
                renderPreview(0L)
                importRecordedImportantFrames()
                prepareStoryboard()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportFailure(failure)
            }
        }
    }

    fun onAction(action: VideoEditorAction) {
        when (action) {
            VideoEditorAction.BackToRecorder -> Unit
            is VideoEditorAction.OpenRecentMedia -> open(action.path)
            VideoEditorAction.AddMedia -> addMedia()
            is VideoEditorAction.SelectAsset -> mutableState.update { it.copy(selectedAssetId = action.assetId) }
            is VideoEditorAction.RelinkAsset -> relinkAsset(action.assetId)
            is VideoEditorAction.RemoveAsset -> removeAsset(action.assetId)
            is VideoEditorAction.AddAssetToTimeline -> addAssetToTimeline(action.assetId)
            VideoEditorAction.AddVideoTrack -> addTrack(EditorTrackKind.Video)
            VideoEditorAction.AddAudioTrack -> addTrack(EditorTrackKind.Audio)
            VideoEditorAction.AddTextTrack -> addTrack(EditorTrackKind.Text)
            VideoEditorAction.AddTextClip -> addTextClip()
            is VideoEditorAction.SelectClip -> mutableState.update {
                it.copy(selectedClipId = action.clipId, selectedImportantFrameId = null)
            }
            is VideoEditorAction.SelectImportantFrame -> mutableState.update {
                it.copy(selectedImportantFrameId = action.markerId, selectedClipId = null)
            }
            is VideoEditorAction.SelectFrameExportCandidate -> selectFrameExportCandidate(action.candidateId)
            is VideoEditorAction.Seek -> seek(action.timelineMicros)
            VideoEditorAction.TogglePlayback -> if (mutableState.value.isPlaying) stopPlayback() else startPlayback()
            is VideoEditorAction.StepFrames -> stepFrames(action.frames)
            VideoEditorAction.SplitSelectedClip -> splitSelectedClip()
            VideoEditorAction.DeleteSelectedClip -> mutableState.value.selectedClipId?.let {
                apply(EditorAction.DeleteClip(it))
                mutableState.update { state -> state.copy(selectedClipId = null) }
            }
            VideoEditorAction.MarkImportantFrame -> markImportantFrame()
            VideoEditorAction.AddCurrentFrameForExport -> addCurrentFrameForExport()
            is VideoEditorAction.SetFrameExportCandidateIncluded -> setFrameExportCandidateIncluded(
                candidateId = action.candidateId,
                included = action.included,
            )
            is VideoEditorAction.SetAllFrameExportCandidatesIncluded ->
                setAllFrameExportCandidatesIncluded(action.included)
            is VideoEditorAction.SetShowOnlyImportantFrames -> mutableState.update {
                it.copy(showOnlyImportantFrames = action.showOnlyImportant)
            }
            is VideoEditorAction.SetImportantFrameIncluded -> apply(
                EditorAction.SetImportantFrameIncluded(action.markerId, action.included),
            )
            is VideoEditorAction.RemoveImportantFrame -> removeImportantFrame(action.markerId)
            is VideoEditorAction.MoveClip -> apply(
                EditorAction.MoveClip(action.clipId, action.trackId, action.timelineStartMicros),
            )
            is VideoEditorAction.TrimClip -> apply(
                EditorAction.TrimMediaClip(
                    action.clipId,
                    action.timelineStartMicros,
                    action.sourceStartMicros,
                    action.durationMicros,
                ),
            )
            is VideoEditorAction.UpdateMediaClip -> apply(
                EditorAction.UpdateMediaClip(
                    action.clipId,
                    action.speed,
                    action.transform,
                    action.effects,
                    action.transition,
                ),
            )
            is VideoEditorAction.UpdateTextClip -> apply(EditorAction.UpdateTextClip(action.clip))
            is VideoEditorAction.SetTrackVisibility -> apply(
                EditorAction.SetTrackVisibility(action.trackId, action.visible),
            )
            is VideoEditorAction.SetTrackLocked -> apply(EditorAction.SetTrackLocked(action.trackId, action.locked))
            is VideoEditorAction.SetTrackMuted -> apply(EditorAction.SetTrackMuted(action.trackId, action.muted))
            is VideoEditorAction.SetTimelineZoom -> mutableState.update {
                it.copy(timelinePixelsPerSecond = action.pixelsPerSecond.coerceIn(30f, 240f))
            }
            is VideoEditorAction.SetFrameLayout -> mutableState.update { it.copy(frameLayout = action.layout) }
            is VideoEditorAction.SetFrameOutputFormat -> mutableState.update {
                it.copy(frameOutputFormat = action.format)
            }
            is VideoEditorAction.SetFrameResolutionPercent -> mutableState.update {
                it.copy(frameResolutionPercent = action.percent.coerceIn(1, 100))
            }
            is VideoEditorAction.SetFrameJpegCompression -> mutableState.update {
                it.copy(frameJpegCompression = action.compression)
            }
            VideoEditorAction.Undo -> undo()
            VideoEditorAction.Redo -> redo()
            VideoEditorAction.ExportVideo -> mutableState.update { it.copy(showVideoExportDialog = true) }
            is VideoEditorAction.ConfirmVideoExport -> {
                mutableState.update { it.copy(showVideoExportDialog = false) }
                exportVideo(action.width, action.height, action.frameRate)
            }
            VideoEditorAction.DismissVideoExportDialog -> mutableState.update { it.copy(showVideoExportDialog = false) }
            VideoEditorAction.ExportFrames -> prepareStoryboard()
            is VideoEditorAction.ExportStoryboard -> {
                mutableState.update { it.copy(frameLayout = action.layout) }
                exportFrames(
                    mutableState.value.frameExportCandidates
                        .filter { it.included && it.timelineMicros <= history.project.durationMicros }
                    .map(FrameExportCandidate::timelineMicros),
                )
            }
            is VideoEditorAction.CopyStoryboardToClipboard -> copyStoryboardToClipboard(
                timestampsMicros = mutableState.value.frameExportCandidates
                    .filter { it.included && it.timelineMicros <= history.project.durationMicros }
                    .map(FrameExportCandidate::timelineMicros),
                layout = action.layout,
            )
            VideoEditorAction.ConfirmFrameExport -> {
                val timestamps = mutableState.value.frameExportCandidates
                    .filter(FrameExportCandidate::included)
                    .map(FrameExportCandidate::timelineMicros)
                dismissFrameExportDialog()
                exportFrames(timestamps)
            }
            VideoEditorAction.DismissFrameExportDialog -> dismissFrameExportDialog()
            VideoEditorAction.CancelExport -> cancelExport()
            VideoEditorAction.DismissError -> mutableState.update { it.copy(errorMessage = null) }
        }
    }

    fun registerRecentMediaPath(path: String) {
        val normalized = path.trim().takeIf(String::isNotEmpty)
            ?.let { value -> runCatching { normalizedPath(value) }.getOrNull() }
            ?: return
        mutableState.update { state ->
            state.copy(
                recentMediaPaths = sortMediaPathsByCreationTime(
                    paths = state.recentMediaPaths + normalized,
                    creationTimeMillis = mediaCreationTimeMillis,
                ),
            )
        }
        onRecentMediaPath(normalized)
    }

    fun requestClose(onClosed: () -> Unit) {
        stopPlayback()
        stopPreviewRendering()
        exportJob?.cancel()
        frameExportPreparationJob?.cancel()
        scope.launch {
            saveNow()
            onClosed()
        }
    }

    fun shutdown() {
        stopPlayback()
        stopPreviewRendering()
        exportJob?.cancel()
        frameExportPreparationJob?.cancel()
        autosaveJob?.cancel()
        thumbnailJob?.cancel()
    }

    private suspend fun createProjectFromPrimaryMedia(path: String): EditorProject {
        val probe = mediaService.probe(path)
        val asset = mediaAsset(path, probe)
        val kind = if (probe.kind == MediaAssetKind.Audio) EditorTrackKind.Audio else EditorTrackKind.Video
        val track = EditorTrack(EditorTrackId(newId("track")), defaultTrackName(kind, 1), kind)
        val duration = asset.durationMicros.takeIf { it > 0L } ?: DEFAULT_IMAGE_DURATION_MICROS
        return EditorProject(
            name = Path.of(path).nameWithoutExtension,
            primaryAssetId = asset.id,
            canvasWidth = asset.width.takeIf { it > 0 } ?: DEFAULT_EDITOR_WIDTH,
            canvasHeight = asset.height.takeIf { it > 0 } ?: DEFAULT_EDITOR_HEIGHT,
            frameRate = nearestFrameRate(asset.frameRate),
            assets = listOf(asset),
            tracks = listOf(
                track.copy(
                    clips = listOf(
                        EditorClip.Media(
                            id = EditorClipId(newId("clip")),
                            assetId = asset.id,
                            timelineStartMicros = 0L,
                            durationMicros = duration,
                        ),
                    ),
                ),
            ),
        )
    }

    private suspend fun importRecordedImportantFrames() {
        val openedProject = history.project
        val primaryAsset = openedProject.primaryAssetId
            ?.let { id -> openedProject.assets.firstOrNull { it.id == id } }
            ?.takeIf { it.kind == MediaAssetKind.Video }
            ?: return
        val timestamps = try {
            mediaService.findImportantFrameTimestamps(primaryAsset.path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update {
                it.copy(errorMessage = failure.message ?: "Could not inspect recorded important frames.")
            }
            return
        }
        val currentProject = history.project
        val currentPrimaryPath = currentProject.primaryAssetId
            ?.let { id -> currentProject.assets.firstOrNull { it.id == id } }
            ?.path
        if (currentPrimaryPath != primaryAsset.path) {
            return
        }
        val existingTimestamps = currentProject.importantFrames.mapTo(mutableSetOf()) { it.timelineMicros }
        val imported = timestamps.asSequence()
            .filter { it in 0L..currentProject.durationMicros }
            .distinct()
            .filterNot(existingTimestamps::contains)
            .map { timestamp ->
                ImportantFrameMarker(
                    id = ImportantFrameId(newId("recorded-frame")),
                    timelineMicros = timestamp,
                    included = false,
                )
            }
            .toList()
        if (imported.isEmpty()) {
            return
        }
        history = history.copy(
            project = currentProject.copy(
                importantFrames = (currentProject.importantFrames + imported)
                    .sortedBy(ImportantFrameMarker::timelineMicros),
            ),
        )
        publishHistory(EditorAutosaveStatus.Pending)
        scheduleAutosave()
        renderImportantFramePreviews()
    }

    private fun addMedia() {
        if (mutableState.value.isImportingMedia) return
        scope.launch {
            mutableState.update { it.copy(isImportingMedia = true, errorMessage = null) }
            try {
                val paths = fileSelector.chooseMediaFiles()
                paths.filterNot { path -> history.project.assets.any { it.path == normalizedPath(path) } }
                    .forEach { path ->
                        val probe = mediaService.probe(path)
                        val asset = mediaAsset(path, probe)
                        val wasEmpty = history.project.assets.isEmpty()
                        apply(EditorAction.AddAsset(asset), render = false)
                        if (wasEmpty) {
                            mutateProject(
                                history.project.copy(
                                    name = Path.of(path).nameWithoutExtension,
                                    primaryAssetId = asset.id,
                                    canvasWidth = asset.width.takeIf { it > 0 } ?: DEFAULT_EDITOR_WIDTH,
                                    canvasHeight = asset.height.takeIf { it > 0 } ?: DEFAULT_EDITOR_HEIGHT,
                                    frameRate = nearestFrameRate(asset.frameRate),
                                ),
                            )
                        }
                        addAssetToTimeline(asset.id)
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportFailure(failure)
            } finally {
                mutableState.update { it.copy(isImportingMedia = false) }
            }
        }
    }

    private fun relinkAsset(assetId: MediaAssetId) {
        val asset = history.project.assets.firstOrNull { it.id == assetId } ?: return
        scope.launch {
            val replacement = fileSelector.chooseReplacementFile(asset.path) ?: return@launch
            try {
                val probe = mediaService.probe(replacement)
                require(probe.kind == asset.kind || asset.kind == MediaAssetKind.Video && probe.kind == MediaAssetKind.Image) {
                    "Replacement media type does not match the original asset."
                }
                mutateProject(
                    history.project.copy(
                        assets = history.project.assets.map { current ->
                            if (current.id == assetId) mediaAsset(replacement, probe, current.id) else current
                        },
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportFailure(failure)
            }
        }
    }

    private fun removeAsset(assetId: MediaAssetId) {
        var project = history.project
        val usedClips = project.tracks.flatMap(EditorTrack::clips)
            .filterIsInstance<EditorClip.Media>()
            .filter { it.assetId == assetId }
        usedClips.forEach { clip ->
            val result = EditorReducer.reduce(project, EditorAction.DeleteClip(clip.id))
            if (result is EditorReduceResult.Applied) project = result.project
        }
        val result = EditorReducer.reduce(project, EditorAction.RemoveAsset(assetId))
        if (result is EditorReduceResult.Applied) mutateProject(result.project)
        else if (result is EditorReduceResult.Rejected) reportFailure(IllegalArgumentException(result.message))
    }

    private fun addAssetToTimeline(assetId: MediaAssetId) {
        val asset = history.project.assets.firstOrNull { it.id == assetId && it.available } ?: return
        val kind = if (asset.kind == MediaAssetKind.Audio) EditorTrackKind.Audio else EditorTrackKind.Video
        var target = history.project.tracks.firstOrNull { it.kind == kind && !it.locked }
        if (target == null) {
            val track = newTrack(kind)
            apply(EditorAction.AddTrack(track), render = false)
            target = track
        }
        apply(
            EditorAction.AddClip(
                target.id,
                EditorClip.Media(
                    id = EditorClipId(newId("clip")),
                    assetId = asset.id,
                    timelineStartMicros = history.project.durationMicros,
                    durationMicros = asset.durationMicros.takeIf { it > 0L } ?: DEFAULT_IMAGE_DURATION_MICROS,
                ),
            ),
        )
    }

    private fun addTrack(kind: EditorTrackKind) = apply(EditorAction.AddTrack(newTrack(kind)))

    private fun newTrack(kind: EditorTrackKind): EditorTrack {
        val number = history.project.tracks.count { it.kind == kind } + 1
        return EditorTrack(EditorTrackId(newId("track")), defaultTrackName(kind, number), kind)
    }

    private fun addTextClip() {
        var target = history.project.tracks.firstOrNull { it.kind == EditorTrackKind.Text && !it.locked }
        if (target == null) {
            val track = newTrack(EditorTrackKind.Text)
            apply(EditorAction.AddTrack(track), render = false)
            target = track
        }
        val clip = EditorClip.Text(
            id = EditorClipId(newId("text")),
            timelineStartMicros = mutablePlayheadMicros.value.coerceAtMost(history.project.durationMicros),
            durationMicros = DEFAULT_TEXT_DURATION_MICROS,
            text = "Text",
        )
        apply(EditorAction.AddClip(target.id, clip))
        mutableState.update { it.copy(selectedClipId = clip.id) }
    }

    private fun splitSelectedClip() {
        val clipId = mutableState.value.selectedClipId ?: return
        apply(EditorAction.SplitClip(clipId, mutablePlayheadMicros.value, EditorClipId(newId("clip"))))
    }

    private fun markImportantFrame(): ImportantFrameId {
        val timestamp = mutablePlayheadMicros.value.coerceIn(0L, history.project.durationMicros)
        val existing = history.project.importantFrames.firstOrNull { it.timelineMicros == timestamp }
        if (existing != null) {
            mutableState.update { it.copy(selectedImportantFrameId = existing.id, selectedClipId = null) }
            upsertImportantFrameCandidate(existing)
            return existing.id
        }
        val marker = ImportantFrameMarker(
            id = ImportantFrameId(newId("frame")),
            timelineMicros = timestamp,
            included = false,
        )
        apply(EditorAction.AddImportantFrame(marker), render = false)
        mutableState.update { it.copy(selectedImportantFrameId = marker.id, selectedClipId = null) }
        upsertImportantFrameCandidate(marker)
        return marker.id
    }

    private fun removeImportantFrame(markerId: ImportantFrameId) {
        if (history.project.importantFrames.none { it.id == markerId }) return
        apply(EditorAction.RemoveImportantFrame(markerId))
        mutableState.update { state ->
            state.copy(
                selectedImportantFrameId = state.selectedImportantFrameId?.takeUnless { it == markerId },
            )
        }
    }

    private fun addCurrentFrameForExport() {
        val markerId = markImportantFrame()
        val marker = history.project.importantFrames.firstOrNull { it.id == markerId } ?: return
        if (!marker.included) {
            apply(EditorAction.SetImportantFrameIncluded(markerId, included = true), render = false)
        }
        upsertImportantFrameCandidate(marker.copy(included = true))
        renderImportantFramePreviews()
    }

    private fun selectFrameExportCandidate(candidateId: ImportantFrameId) {
        val candidate = mutableState.value.frameExportCandidates.firstOrNull { it.id == candidateId } ?: return
        val importantFrameId = history.project.importantFrames
            .firstOrNull { it.timelineMicros == candidate.timelineMicros }
            ?.id
        mutableState.update {
            it.copy(
                selectedFrameExportCandidateId = candidate.id,
                selectedImportantFrameId = importantFrameId,
                selectedClipId = null,
            )
        }
        seek(candidate.timelineMicros)
    }

    private fun setFrameExportCandidateIncluded(candidateId: ImportantFrameId, included: Boolean) {
        val candidate = mutableState.value.frameExportCandidates.firstOrNull { it.id == candidateId } ?: return
        history.project.importantFrames
            .firstOrNull { it.timelineMicros == candidate.timelineMicros && it.included != included }
            ?.let { marker ->
                apply(EditorAction.SetImportantFrameIncluded(marker.id, included), render = false)
            }
        mutableState.update { state ->
            state.copy(
                frameExportCandidates = state.frameExportCandidates.map { current ->
                    if (current.id == candidateId) current.copy(included = included) else current
                },
            )
        }
    }

    private fun setAllFrameExportCandidatesIncluded(included: Boolean) {
        val currentState = mutableState.value
        val visibleCandidates = currentState.frameExportCandidates
            .filter { it.timelineMicros <= history.project.durationMicros }
            .filter { !currentState.showOnlyImportantFrames || it.important }
        val visibleCandidateIds = visibleCandidates.mapTo(mutableSetOf(), FrameExportCandidate::id)
        val visibleTimestamps = visibleCandidates.mapTo(mutableSetOf(), FrameExportCandidate::timelineMicros)
        val updatedProject = history.project.copy(
            importantFrames = history.project.importantFrames.map { marker ->
                if (marker.timelineMicros in visibleTimestamps) marker.copy(included = included) else marker
            },
        )
        if (updatedProject != history.project) mutateProject(updatedProject)
        mutableState.update { state ->
            state.copy(
                frameExportCandidates = state.frameExportCandidates.map { candidate ->
                    if (candidate.id in visibleCandidateIds) {
                        candidate.copy(included = included)
                    } else {
                        candidate
                    }
                },
            )
        }
    }

    private fun upsertImportantFrameCandidate(marker: ImportantFrameMarker) {
        mutableState.update { state ->
            val existing = state.frameExportCandidates.firstOrNull { it.timelineMicros == marker.timelineMicros }
            val candidate = FrameExportCandidate(
                id = existing?.id ?: marker.id,
                timelineMicros = marker.timelineMicros,
                included = marker.included,
                important = true,
            )
            val candidates = if (existing == null) {
                state.frameExportCandidates + candidate
            } else {
                state.frameExportCandidates.map { current -> if (current.id == existing.id) candidate else current }
            }.sortedBy(FrameExportCandidate::timelineMicros)
            state.copy(
                frameExportCandidates = candidates,
                selectedFrameExportCandidateId = candidate.id,
            )
        }
    }

    private fun synchronizeStoryboardCandidatesWithProject() {
        val markersByTimestamp = history.project.importantFrames.associateBy(ImportantFrameMarker::timelineMicros)
        mutableState.update { state ->
            val retainedCandidates = state.frameExportCandidates.mapNotNull { candidate ->
                val marker = markersByTimestamp[candidate.timelineMicros]
                when {
                    marker != null -> candidate.copy(important = true, included = marker.included)
                    candidate.id.value.startsWith(STORYBOARD_CANDIDATE_ID_PREFIX) -> candidate.copy(important = false)
                    else -> null
                }
            }
            val retainedTimestamps = retainedCandidates.mapTo(mutableSetOf(), FrameExportCandidate::timelineMicros)
            val missingImportantCandidates = history.project.importantFrames
                .filter { it.timelineMicros <= history.project.durationMicros && it.timelineMicros !in retainedTimestamps }
                .map { marker ->
                    FrameExportCandidate(
                        id = marker.id,
                        timelineMicros = marker.timelineMicros,
                        included = marker.included,
                        important = true,
                    )
                }
            val candidates = (retainedCandidates + missingImportantCandidates)
                .sortedBy(FrameExportCandidate::timelineMicros)
            state.copy(
                frameExportCandidates = candidates,
                selectedFrameExportCandidateId = state.selectedFrameExportCandidateId
                    ?.takeIf { selected -> candidates.any { it.id == selected } },
            )
        }
    }

    private fun prepareStoryboard() {
        frameExportPreparationJob?.cancel()
        val project = history.project
        mutableState.update {
            it.copy(
                isPreparingFrameExport = true,
                frameExportCandidates = emptyList(),
                selectedFrameExportCandidateId = null,
                errorMessage = null,
            )
        }
        frameExportPreparationJob = scope.launch {
            try {
                val storyboardTimestamps = mediaService.createStoryboardFrameTimestamps(project)
                if (history.project != project) return@launch
                val candidatesByTimestamp = linkedMapOf<Long, FrameExportCandidate>()
                storyboardTimestamps.forEach { timestamp ->
                    if (timestamp in 0L..project.durationMicros) {
                        candidatesByTimestamp[timestamp] = FrameExportCandidate(
                            id = ImportantFrameId("$STORYBOARD_CANDIDATE_ID_PREFIX$timestamp"),
                            timelineMicros = timestamp,
                        )
                    }
                }
                project.importantFrames.forEach { marker ->
                    val available = marker.timelineMicros <= project.durationMicros
                    if (available) {
                        val storyboardCandidate = candidatesByTimestamp[marker.timelineMicros]
                        candidatesByTimestamp[marker.timelineMicros] = FrameExportCandidate(
                            id = storyboardCandidate?.id ?: marker.id,
                            timelineMicros = marker.timelineMicros,
                            included = marker.included,
                            important = true,
                        )
                    }
                }
                val candidates = candidatesByTimestamp.values.sortedBy(FrameExportCandidate::timelineMicros)
                mutableState.update {
                    it.copy(
                        isPreparingFrameExport = false,
                        frameExportCandidates = candidates,
                        selectedFrameExportCandidateId = candidates.firstOrNull()?.id,
                    )
                }
                renderImportantFramePreviews()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { it.copy(isPreparingFrameExport = false) }
                reportFailure(failure)
            } finally {
                frameExportPreparationJob = null
            }
        }
    }

    private fun dismissFrameExportDialog() {
        frameExportPreparationJob?.cancel()
        frameExportPreparationJob = null
        mutableState.update {
            it.copy(
                showFrameExportDialog = false,
                isPreparingFrameExport = false,
            )
        }
    }

    private fun seek(timestampMicros: Long) {
        if (playbackJob != null) stopPlayback()
        val timestamp = timestampMicros.coerceIn(0L, history.project.durationMicros)
        mutablePlayheadMicros.value = timestamp
        renderPreview(timestamp)
    }

    private fun stepFrames(frames: Int) {
        val frameDuration = 1_000_000L / history.project.frameRate
        seek(mutablePlayheadMicros.value + frames * frameDuration)
    }

    private fun startPlayback() {
        if (history.project.durationMicros <= 0L || playbackJob != null) return
        stopPreviewRendering()
        if (mutablePlayheadMicros.value >= history.project.durationMicros) mutablePlayheadMicros.value = 0L
        val project = history.project
        playbackJob = scope.launch {
            mutableState.update {
                it.copy(
                    isPlaying = true,
                    previewStatus = EditorPreviewStatus.Rendering,
                    errorMessage = null,
                )
            }
            try {
                val audio = audioFor(project)
                audioJob = if (audio.samples.isNotEmpty()) {
                    scope.launch { audioPlayer.play(audio, mutablePlayheadMicros.value) }
                } else null
                val initialPlayhead = mutablePlayheadMicros.value
                val startedAt = nanoTime()
                val frameIntervalNanos = NANOS_PER_SECOND / project.frameRate
                val previewSession = mediaService.createPreviewSession(
                    project = project,
                    maxWidth = EDITOR_PREVIEW_WIDTH,
                    maxHeight = EDITOR_PREVIEW_HEIGHT,
                )
                try {
                    while (isActive) {
                        val frameStartedAt = nanoTime()
                        val elapsed = (frameStartedAt - startedAt).coerceAtLeast(0L) / NANOS_PER_MICROSECOND
                        val timestamp = initialPlayhead + elapsed
                        if (timestamp >= project.durationMicros) break
                        mutablePlayheadMicros.value = timestamp
                        mutablePreviewFrame.value = previewSession.render(timestamp)
                        if (mutableState.value.previewStatus != EditorPreviewStatus.Ready) {
                            mutableState.update { it.copy(previewStatus = EditorPreviewStatus.Ready) }
                        }
                        val renderNanos = (nanoTime() - frameStartedAt).coerceAtLeast(0L)
                        val remainingNanos = frameIntervalNanos - renderNanos
                        if (remainingNanos > 0L) {
                            delay((remainingNanos / NANOS_PER_MILLISECOND).coerceAtLeast(1L))
                        }
                    }
                } finally {
                    previewSession.close()
                }
                mutablePlayheadMicros.value = project.durationMicros
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportFailure(failure)
            } finally {
                audioPlayer.stop()
                audioJob?.cancel()
                audioJob = null
                playbackJob = null
                mutableState.update {
                    it.copy(
                        isPlaying = false,
                        previewStatus = if (it.previewStatus == EditorPreviewStatus.Rendering) {
                            if (mutablePreviewFrame.value == null) {
                                EditorPreviewStatus.Empty
                            } else {
                                EditorPreviewStatus.Ready
                            }
                        } else {
                            it.previewStatus
                        },
                    )
                }
            }
        }
    }

    private suspend fun audioFor(project: EditorProject): EditorPreviewAudio {
        if (cachedAudioProject == project) return cachedAudio ?: EditorPreviewAudio(48_000, 2, FloatArray(0))
        return mediaService.renderPreviewAudio(project).also {
            cachedAudioProject = project
            cachedAudio = it
        }
    }

    private fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        audioJob?.cancel()
        audioJob = null
        audioPlayer.stop()
        mutableState.update { it.copy(isPlaying = false) }
    }

    private fun renderPreview(timestampMicros: Long) {
        if (history.project.durationMicros <= 0L) {
            stopPreviewRendering()
            mutablePreviewFrame.value = null
            mutableState.update { it.copy(previewStatus = EditorPreviewStatus.Empty) }
            return
        }
        val project = history.project
        if (previewProject != project || previewJob?.isActive != true) {
            startPreviewRendering(project)
        }
        mutableState.update { it.copy(previewStatus = EditorPreviewStatus.Rendering) }
        previewRequests?.trySend(timestampMicros.coerceIn(0L, project.durationMicros))
    }

    private fun startPreviewRendering(project: EditorProject) {
        stopPreviewRendering()
        val requests = Channel<Long>(Channel.CONFLATED)
        previewProject = project
        previewRequests = requests
        previewJob = scope.launch {
            var session: EditorPreviewSession? = null
            try {
                session = mediaService.createPreviewSession(
                    project = project,
                    maxWidth = EDITOR_PREVIEW_WIDTH,
                    maxHeight = EDITOR_PREVIEW_HEIGHT,
                )
                for (timestampMicros in requests) {
                    val frame = session.render(timestampMicros)
                    if (previewProject == project) {
                        mutablePreviewFrame.value = frame
                        mutableState.update { it.copy(previewStatus = EditorPreviewStatus.Ready) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (previewProject == project) reportFailure(failure)
            } finally {
                withContext(NonCancellable) { session?.close() }
            }
        }
    }

    private fun stopPreviewRendering() {
        previewProject = null
        previewRequests?.close()
        previewRequests = null
        previewJob?.cancel()
        previewJob = null
    }

    private fun apply(action: EditorAction, render: Boolean = true) {
        when (val result = EditorReducer.reduce(history.project, action)) {
            is EditorReduceResult.Applied -> {
                history = history.copy(
                    project = result.project,
                    undoStack = (history.undoStack + history.project).takeLast(MAX_UNDO_STEPS),
                    redoStack = emptyList(),
                )
                afterProjectChanged(render)
            }
            is EditorReduceResult.Rejected -> mutableState.update { it.copy(errorMessage = result.message) }
        }
    }

    private fun mutateProject(project: EditorProject) {
        if (project == history.project) return
        history = history.copy(
            project = project,
            undoStack = (history.undoStack + history.project).takeLast(MAX_UNDO_STEPS),
            redoStack = emptyList(),
        )
        afterProjectChanged(render = true)
    }

    private fun undo() {
        val updated = history.undo()
        if (updated == history) return
        history = updated
        afterProjectChanged(render = true)
    }

    private fun redo() {
        val updated = history.redo()
        if (updated == history) return
        history = updated
        afterProjectChanged(render = true)
    }

    private fun afterProjectChanged(render: Boolean) {
        stopPlayback()
        stopPreviewRendering()
        cachedAudioProject = null
        cachedAudio = null
        mutablePlayheadMicros.value = mutablePlayheadMicros.value.coerceAtMost(history.project.durationMicros)
        publishHistory(EditorAutosaveStatus.Pending)
        synchronizeStoryboardCandidatesWithProject()
        scheduleAutosave()
        renderImportantFramePreviews()
        if (render) renderPreview(mutablePlayheadMicros.value)
    }

    private fun replaceProject(project: EditorProject) {
        stopPreviewRendering()
        history = EditorHistory(project)
        cachedAudioProject = null
        cachedAudio = null
        mutablePlayheadMicros.value = 0L
        mutablePreviewFrame.value = null
        mutableState.update {
            it.copy(
                showFrameExportDialog = false,
                isPreparingFrameExport = false,
                frameExportCandidates = emptyList(),
                selectedFrameExportCandidateId = null,
                showOnlyImportantFrames = false,
            )
        }
        publishHistory(EditorAutosaveStatus.Saved)
        renderImportantFramePreviews()
    }

    private fun renderImportantFramePreviews() {
        thumbnailJob?.cancel()
        val project = history.project
        val exportMarkers = mutableState.value.frameExportCandidates.map { candidate ->
            ImportantFrameMarker(
                id = candidate.id,
                timelineMicros = candidate.timelineMicros,
                included = candidate.included,
            )
        }
        val markers = (project.importantFrames + exportMarkers)
            .filter { it.timelineMicros <= project.durationMicros }
            .distinctBy(ImportantFrameMarker::id)
            .sortedBy(ImportantFrameMarker::timelineMicros)
            .takeLast(MAX_IMPORTANT_FRAME_THUMBNAILS)
        if (markers.isEmpty()) {
            mutableImportantFramePreviews.value = emptyMap()
            return
        }
        thumbnailJob = scope.launch {
            val previews = linkedMapOf<ImportantFrameId, EditorPreviewFrame>()
            try {
                markers.forEach { marker ->
                    previews[marker.id] = mediaService.renderPreview(
                        project = project,
                        timelineMicros = marker.timelineMicros,
                        maxWidth = IMPORTANT_FRAME_THUMBNAIL_WIDTH,
                        maxHeight = IMPORTANT_FRAME_THUMBNAIL_HEIGHT,
                    )
                }
                mutableImportantFramePreviews.value = previews
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportFailure(failure)
            } finally {
                thumbnailJob = null
            }
        }
    }

    private fun publishHistory(autosaveStatus: EditorAutosaveStatus = mutableState.value.autosaveStatus) {
        mutableState.update {
            it.copy(
                project = history.project,
                canUndo = history.undoStack.isNotEmpty(),
                canRedo = history.redoStack.isNotEmpty(),
                autosaveStatus = autosaveStatus,
            )
        }
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = scope.launch {
            delay(AUTOSAVE_DEBOUNCE_MILLIS)
            saveNow()
        }
    }

    private suspend fun saveNow() {
        autosaveJob?.cancel()
        autosaveJob = null
        val project = history.project
        val primary = project.primaryAssetId?.let { id -> project.assets.firstOrNull { it.id == id } } ?: return
        mutableState.update { it.copy(autosaveStatus = EditorAutosaveStatus.Saving) }
        try {
            projectStore.save(primary.path, project)
            mutableState.update { it.copy(autosaveStatus = EditorAutosaveStatus.Saved) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update {
                it.copy(
                    autosaveStatus = EditorAutosaveStatus.Failed,
                    errorMessage = failure.message ?: "Editor autosave failed.",
                )
            }
        }
    }

    private fun exportVideo(width: Int, height: Int, frameRate: Int) {
        export { primary ->
            val path = fileSelector.chooseVideoOutput(primary.path) ?: return@export null
            io.aequicor.editor.EditorExportRequest.Video(
                project = history.project,
                outputPath = path,
                overwrite = Files.exists(Path.of(path)),
                width = width,
                height = height,
                frameRate = frameRate,
            )
        }
    }

    private fun exportFrames(timestampsMicros: List<Long>) {
        if (timestampsMicros.isEmpty()) return
        val exportSettings = mutableState.value
        export { primary ->
            val path = fileSelector.chooseFrameOutput(
                primaryMediaPath = primary.path,
                layout = exportSettings.frameLayout,
                outputFormat = exportSettings.frameOutputFormat,
            ) ?: return@export null
            io.aequicor.editor.EditorExportRequest.Frames(
                project = history.project,
                outputPath = path,
                overwrite = Files.exists(Path.of(path)),
                layout = exportSettings.frameLayout,
                outputFormat = exportSettings.frameOutputFormat,
                resolutionPercent = exportSettings.frameResolutionPercent,
                jpegCompression = exportSettings.frameJpegCompression,
                timestampsMicros = timestampsMicros,
            )
        }
    }

    private fun copyStoryboardToClipboard(
        timestampsMicros: List<Long>,
        layout: ImportantFrameLayout,
    ) {
        if (timestampsMicros.isEmpty() || exportJob != null) return
        val orderedTimestampsMicros = timestampsMicros.distinct().sorted()
        val project = history.project
        if (project.primaryAssetId?.let { id -> project.assets.firstOrNull { it.id == id } } == null) return
        val exportSettings = mutableState.value
        exportJob = scope.launch {
            saveNow()
            mutableState.update { it.copy(isExporting = true, exportProgress = 0f, errorMessage = null) }
            var temporaryDirectory: Path? = null
            try {
                val clipboardDirectory = withContext(ioDispatcher) {
                    Files.createTempDirectory("mission-recorder-clipboard-")
                }
                temporaryDirectory = clipboardDirectory
                val temporaryOutput = when (layout) {
                    ImportantFrameLayout.ContactSheet -> clipboardDirectory.resolve(
                        when (exportSettings.frameOutputFormat) {
                            FrameImageFormat.Png -> "contact-sheet.png"
                            FrameImageFormat.Jpeg -> "contact-sheet.jpg"
                        },
                    )
                    ImportantFrameLayout.SeparatePngFiles -> clipboardDirectory.resolve("frames")
                }
                val request = io.aequicor.editor.EditorExportRequest.Frames(
                    project = project,
                    outputPath = temporaryOutput.toString(),
                    layout = layout,
                    outputFormat = exportSettings.frameOutputFormat,
                    resolutionPercent = exportSettings.frameResolutionPercent,
                    jpegCompression = exportSettings.frameJpegCompression,
                    timestampsMicros = orderedTimestampsMicros,
                )
                val result = mediaService.export(request, ::applyExportProgress)
                val clipboardPaths = result.clipboardPaths(layout, orderedTimestampsMicros)
                imageClipboard.copyImages(clipboardPaths)
                mutableState.update { it.copy(isExporting = false, exportProgress = 1f) }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(isExporting = false, exportProgress = 0f) }
                throw cancelled
            } catch (failure: Exception) {
                reportFailure(failure)
                mutableState.update { it.copy(isExporting = false, exportProgress = 0f) }
            } finally {
                temporaryDirectory?.let { path ->
                    withContext(NonCancellable + ioDispatcher) {
                        runCatching { deleteRecursively(path) }
                    }
                }
                exportJob = null
            }
        }
    }

    private fun export(
        requestFactory: suspend (MediaAsset) -> io.aequicor.editor.EditorExportRequest?,
    ) {
        if (exportJob != null) return
        val project = history.project
        val primary = project.primaryAssetId?.let { id -> project.assets.firstOrNull { it.id == id } } ?: return
        exportJob = scope.launch {
            saveNow()
            val request = requestFactory(primary)
            if (request == null) {
                exportJob = null
                return@launch
            }
            mutableState.update { it.copy(isExporting = true, exportProgress = 0f, errorMessage = null) }
            try {
                val result = mediaService.export(request, ::applyExportProgress)
                mutableState.update {
                    it.copy(isExporting = false, exportProgress = 1f, lastExportPath = result.outputPath)
                }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(isExporting = false, exportProgress = 0f) }
                throw cancelled
            } catch (failure: Exception) {
                reportFailure(failure)
                mutableState.update { it.copy(isExporting = false, exportProgress = 0f) }
            } finally {
                exportJob = null
            }
        }
    }

    private fun applyExportProgress(progress: EditorExportProgress) {
        mutableState.update { it.copy(exportProgress = progress.fraction) }
    }

    private fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        mutableState.update { it.copy(isExporting = false, exportProgress = 0f) }
    }

    private fun mediaAsset(path: String, probe: EditorMediaProbe, id: MediaAssetId = MediaAssetId(newId("asset"))): MediaAsset =
        MediaAsset(
            id = id,
            path = normalizedPath(path),
            displayName = Path.of(path).name,
            kind = probe.kind,
            durationMicros = probe.durationMicros,
            width = probe.width,
            height = probe.height,
            frameRate = probe.frameRate,
            hasAudio = probe.hasAudio,
        )

    private fun reportFailure(failure: Throwable) {
        mutableState.update {
            it.copy(
                previewStatus = if (it.project.durationMicros > 0L) EditorPreviewStatus.Failed else EditorPreviewStatus.Empty,
                errorMessage = failure.message ?: "Video editor operation failed.",
            )
        }
    }

    private fun newId(prefix: String): String = "$prefix:${idFactory()}"
}

private data class MediaPathCreationTime(
    val path: String,
    val originalIndex: Int,
    val creationTimeMillis: Long?,
)

private fun sortMediaPathsByCreationTime(
    paths: List<String>,
    creationTimeMillis: (String) -> Long?,
): List<String> = paths
    .distinct()
    .mapIndexed { index, path ->
        MediaPathCreationTime(
            path = path,
            originalIndex = index,
            creationTimeMillis = runCatching { creationTimeMillis(path) }.getOrNull(),
        )
    }
    .sortedWith(
        compareByDescending<MediaPathCreationTime> { it.creationTimeMillis != null }
            .thenByDescending { it.creationTimeMillis ?: Long.MIN_VALUE }
            .thenBy(MediaPathCreationTime::originalIndex),
    )
    .take(MAX_RECENT_EDITOR_MEDIA_PATHS)
    .map(MediaPathCreationTime::path)

private fun readMediaCreationTimeMillis(path: String): Long? = runCatching {
    Files.readAttributes(Path.of(path), BasicFileAttributes::class.java).creationTime().toMillis()
}.getOrNull()

private fun emptyEditorProject(): EditorProject = EditorProject(
    name = "Untitled edit",
    primaryAssetId = null,
    canvasWidth = DEFAULT_EDITOR_WIDTH,
    canvasHeight = DEFAULT_EDITOR_HEIGHT,
    frameRate = 30,
)

private fun defaultTrackName(kind: EditorTrackKind, number: Int): String = when (kind) {
    EditorTrackKind.Video -> "Video $number"
    EditorTrackKind.Audio -> "Audio $number"
    EditorTrackKind.Text -> "Text $number"
}

private fun nearestFrameRate(value: Double): Int = listOf(24, 30, 60).minBy { kotlin.math.abs(it - value) }
private fun normalizedPath(path: String): String = Path.of(path).toAbsolutePath().normalize().toString()

private fun EditorExportResult.clipboardPaths(
    layout: ImportantFrameLayout,
    orderedTimestampsMicros: List<Long>,
): List<String> = when (layout) {
    ImportantFrameLayout.ContactSheet -> listOf(
        requireNotNull(outputPaths.singleOrNull()) { "Contact-sheet export must produce exactly one file." },
    )
    ImportantFrameLayout.SeparatePngFiles -> {
        val framesByTimestamp = exportedFrames.associateBy { frame -> frame.timelineMicros }
        require(
            framesByTimestamp.size == exportedFrames.size && framesByTimestamp.keys == orderedTimestampsMicros.toSet(),
        ) { "Frame export outputs do not match the requested timeline positions." }
        orderedTimestampsMicros.map { timestamp ->
            requireNotNull(framesByTimestamp[timestamp]) {
                "Frame export did not produce the requested timeline position $timestamp."
            }.outputPath
        }
    }
}

private const val DEFAULT_EDITOR_WIDTH = 1920
private const val DEFAULT_EDITOR_HEIGHT = 1080
private const val DEFAULT_IMAGE_DURATION_MICROS = 5_000_000L
private const val DEFAULT_TEXT_DURATION_MICROS = 3_000_000L
private const val AUTOSAVE_DEBOUNCE_MILLIS = 500L
private const val MAX_UNDO_STEPS = 100
private const val MAX_IMPORTANT_FRAME_THUMBNAILS = 160
private const val IMPORTANT_FRAME_THUMBNAIL_WIDTH = 240
private const val IMPORTANT_FRAME_THUMBNAIL_HEIGHT = 135
private const val EDITOR_PREVIEW_WIDTH = 1920
private const val EDITOR_PREVIEW_HEIGHT = 1080
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val NANOS_PER_MICROSECOND = 1_000L
private const val STORYBOARD_CANDIDATE_ID_PREFIX = "storyboard:"
private const val MAX_RECENT_EDITOR_MEDIA_PATHS = 12

private fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}
