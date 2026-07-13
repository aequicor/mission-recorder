package io.aequicor.compose.ui

import io.aequicor.editor.ClipEffects
import io.aequicor.editor.ClipTransform
import io.aequicor.editor.EditorClipId
import io.aequicor.editor.EditorClip
import io.aequicor.editor.EditorProject
import io.aequicor.editor.EditorTrackId
import io.aequicor.editor.ImportantFrameId
import io.aequicor.editor.ImportantFrameLayout
import io.aequicor.editor.MediaAssetId
import io.aequicor.editor.Transition

enum class EditorPreviewStatus {
    Empty,
    Rendering,
    Ready,
    Failed,
}

enum class EditorAutosaveStatus {
    Saved,
    Pending,
    Saving,
    Failed,
}

data class FrameExportCandidate(
    val id: ImportantFrameId,
    val timelineMicros: Long,
    val included: Boolean = true,
    val important: Boolean = false,
)

data class VideoEditorUiState(
    val project: EditorProject,
    val selectedAssetId: MediaAssetId? = project.primaryAssetId,
    val selectedClipId: EditorClipId? = null,
    val selectedImportantFrameId: ImportantFrameId? = null,
    val selectedFrameExportCandidateId: ImportantFrameId? = null,
    val previewStatus: EditorPreviewStatus = EditorPreviewStatus.Empty,
    val autosaveStatus: EditorAutosaveStatus = EditorAutosaveStatus.Saved,
    val isPlaying: Boolean = false,
    val isImportingMedia: Boolean = false,
    val isExporting: Boolean = false,
    val showVideoExportDialog: Boolean = false,
    val showFrameExportDialog: Boolean = false,
    val isPreparingFrameExport: Boolean = false,
    val frameExportCandidates: List<FrameExportCandidate> = emptyList(),
    val showOnlyImportantFrames: Boolean = false,
    val exportProgress: Float = 0f,
    val timelinePixelsPerSecond: Float = 80f,
    val frameLayout: ImportantFrameLayout = ImportantFrameLayout.SeparatePngFiles,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val lastExportPath: String? = null,
    val errorMessage: String? = null,
) {
    val availableFrameExportCount: Int
        get() = frameExportCandidates.count { it.timelineMicros <= project.durationMicros }

    val includedFrameExportCount: Int
        get() = frameExportCandidates.count { it.included && it.timelineMicros <= project.durationMicros }

    val allFrameExportCandidatesIncluded: Boolean
        get() = availableFrameExportCount > 0 && includedFrameExportCount == availableFrameExportCount
}

sealed interface VideoEditorAction {
    data object BackToRecorder : VideoEditorAction
    data object AddMedia : VideoEditorAction
    data class SelectAsset(val assetId: MediaAssetId) : VideoEditorAction
    data class RelinkAsset(val assetId: MediaAssetId) : VideoEditorAction
    data class RemoveAsset(val assetId: MediaAssetId) : VideoEditorAction
    data class AddAssetToTimeline(val assetId: MediaAssetId) : VideoEditorAction
    data object AddVideoTrack : VideoEditorAction
    data object AddAudioTrack : VideoEditorAction
    data object AddTextTrack : VideoEditorAction
    data object AddTextClip : VideoEditorAction
    data class SelectClip(val clipId: EditorClipId?) : VideoEditorAction
    data class SelectImportantFrame(val markerId: ImportantFrameId?) : VideoEditorAction
    data class SelectFrameExportCandidate(val candidateId: ImportantFrameId) : VideoEditorAction
    data class Seek(val timelineMicros: Long) : VideoEditorAction
    data object TogglePlayback : VideoEditorAction
    data class StepFrames(val frames: Int) : VideoEditorAction
    data object SplitSelectedClip : VideoEditorAction
    data object DeleteSelectedClip : VideoEditorAction
    data object MarkImportantFrame : VideoEditorAction
    data object AddCurrentFrameForExport : VideoEditorAction
    data class SetFrameExportCandidateIncluded(
        val candidateId: ImportantFrameId,
        val included: Boolean,
    ) : VideoEditorAction
    data class SetAllFrameExportCandidatesIncluded(val included: Boolean) : VideoEditorAction
    data class SetShowOnlyImportantFrames(val showOnlyImportant: Boolean) : VideoEditorAction
    data class SetImportantFrameIncluded(val markerId: ImportantFrameId, val included: Boolean) : VideoEditorAction
    data class RemoveImportantFrame(val markerId: ImportantFrameId) : VideoEditorAction
    data class MoveClip(
        val clipId: EditorClipId,
        val trackId: EditorTrackId,
        val timelineStartMicros: Long,
    ) : VideoEditorAction
    data class TrimClip(
        val clipId: EditorClipId,
        val timelineStartMicros: Long,
        val sourceStartMicros: Long,
        val durationMicros: Long,
    ) : VideoEditorAction
    data class UpdateMediaClip(
        val clipId: EditorClipId,
        val speed: Float,
        val transform: ClipTransform,
        val effects: ClipEffects,
        val transition: Transition,
    ) : VideoEditorAction
    data class UpdateTextClip(val clip: EditorClip.Text) : VideoEditorAction
    data class SetTrackVisibility(val trackId: EditorTrackId, val visible: Boolean) : VideoEditorAction
    data class SetTrackLocked(val trackId: EditorTrackId, val locked: Boolean) : VideoEditorAction
    data class SetTrackMuted(val trackId: EditorTrackId, val muted: Boolean) : VideoEditorAction
    data class SetTimelineZoom(val pixelsPerSecond: Float) : VideoEditorAction
    data class SetFrameLayout(val layout: ImportantFrameLayout) : VideoEditorAction
    data object Undo : VideoEditorAction
    data object Redo : VideoEditorAction
    data object ExportVideo : VideoEditorAction
    data class ConfirmVideoExport(val width: Int, val height: Int, val frameRate: Int) : VideoEditorAction
    data object DismissVideoExportDialog : VideoEditorAction
    data object ExportFrames : VideoEditorAction
    data class ExportStoryboard(val layout: ImportantFrameLayout) : VideoEditorAction
    data object CopyStoryboardToClipboard : VideoEditorAction
    data object ConfirmFrameExport : VideoEditorAction
    data object DismissFrameExportDialog : VideoEditorAction
    data object CancelExport : VideoEditorAction
    data object DismissError : VideoEditorAction
}
