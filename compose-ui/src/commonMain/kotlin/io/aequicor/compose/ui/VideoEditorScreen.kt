package io.aequicor.compose.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aequicor.compose.resources.Res
import io.aequicor.compose.resources.add_current_frame
import io.aequicor.compose.resources.all_frames
import io.aequicor.compose.resources.all_or_no_frames
import io.aequicor.compose.resources.cancel
import io.aequicor.compose.resources.compression_high
import io.aequicor.compose.resources.compression_low
import io.aequicor.compose.resources.compression_medium
import io.aequicor.compose.resources.compression_none
import io.aequicor.compose.resources.copy_multiple_files_to_clipboard
import io.aequicor.compose.resources.copy_single_file_to_clipboard
import io.aequicor.compose.resources.delete
import io.aequicor.compose.resources.dismiss
import io.aequicor.compose.resources.editor_montage_title
import io.aequicor.compose.resources.editor_preview
import io.aequicor.compose.resources.editor_project_meta
import io.aequicor.compose.resources.editor_recording
import io.aequicor.compose.resources.editor_video_sequence
import io.aequicor.compose.resources.empty_editor
import io.aequicor.compose.resources.export_section
import io.aequicor.compose.resources.export_separate_files
import io.aequicor.compose.resources.export_single_file
import io.aequicor.compose.resources.frame_export_destination
import io.aequicor.compose.resources.frame_output_format
import io.aequicor.compose.resources.frame_jpeg_compression
import io.aequicor.compose.resources.frame_resolution
import io.aequicor.compose.resources.forward_ten_seconds
import io.aequicor.compose.resources.important_frame
import io.aequicor.compose.resources.important_frames_filter
import io.aequicor.compose.resources.include_in_storyboard
import io.aequicor.compose.resources.mark_important_frame
import io.aequicor.compose.resources.media_library
import io.aequicor.compose.resources.next_frame
import io.aequicor.compose.resources.no_export_frames
import io.aequicor.compose.resources.no_recordings_in_history
import io.aequicor.compose.resources.ordinary_frame
import io.aequicor.compose.resources.pause
import io.aequicor.compose.resources.play
import io.aequicor.compose.resources.preparing_storyboard_frames
import io.aequicor.compose.resources.previous_frame
import io.aequicor.compose.resources.redo
import io.aequicor.compose.resources.recording_history
import io.aequicor.compose.resources.remove_important_frame
import io.aequicor.compose.resources.rewind_ten_seconds
import io.aequicor.compose.resources.selected_frames_count
import io.aequicor.compose.resources.speed
import io.aequicor.compose.resources.storyboard
import io.aequicor.compose.resources.storyboard_selection_count
import io.aequicor.compose.resources.undo
import io.aequicor.compose.resources.video_editor
import io.aequicor.editor.EditorClip
import io.aequicor.editor.EditorProject
import io.aequicor.editor.EditorTrackKind
import io.aequicor.editor.FrameImageFormat
import io.aequicor.editor.ImportantFrameId
import io.aequicor.editor.ImportantFrameLayout
import io.aequicor.editor.JpegCompression
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max

private object EditorColors {
    val Background = Color(0xFF111519)
    val Surface = Color(0xFF1A2026)
    val RaisedSurface = Color(0xFF242B32)
    val RecessedSurface = Color(0xFF161C21)
    val TimelineTrack = Color(0xFF20272D)
    val Outline = Color(0xFF37414B)
    val Primary = Color(0xFF4C8CCA)
    val PrimarySoft = Color(0x334C8CCA)
    val Text = Color(0xFFE7ECF0)
    val MutedText = Color(0xFFA8B2BA)
    val SubtleText = Color(0xFF7F8A93)
    val Important = Color(0xFFF3C64D)
    val Playhead = Color(0xFFFF5B64)
    val Error = Color(0xFFFF747B)
    val SegmentColors = listOf(
        Color(0xFF344A5C),
        Color(0xFF273C4B),
        Color(0xFF5A3040),
        Color(0xFF2F4B44),
        Color(0xFF3C3658),
        Color(0xFF36526A),
    )
}

private object EditorDimens {
    val HeaderHeight = 72.dp
    val WorkspacePadding = 24.dp
    val WorkspaceGap = 24.dp
    val PanelRadius = 12.dp
    val ControlRadius = 8.dp
    val StoryboardWidth = 416.dp
    val CompactStoryboardWidth = 360.dp
    val RecordingHistoryWidth = 228.dp
    val TimelineHorizontalInset = 16.dp
    val TimelinePlayheadRadius = 10.dp
    val TimelinePlayheadStrokeWidth = 3.dp
}

@Composable
fun VideoEditorScreen(
    state: VideoEditorUiState,
    playheadMicros: State<Long>,
    previewImage: State<ImageBitmap?>,
    onAction: (VideoEditorAction) -> Unit,
    modifier: Modifier = Modifier,
    importantFrameImages: Map<ImportantFrameId, ImageBitmap> = emptyMap(),
) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = EditorColors.Primary,
            background = EditorColors.Background,
            surface = EditorColors.Surface,
            surfaceVariant = EditorColors.RaisedSurface,
            onSurface = EditorColors.Text,
            onSurfaceVariant = EditorColors.MutedText,
            outline = EditorColors.Outline,
            error = EditorColors.Error,
        ),
    ) {
        state.errorMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { onAction(VideoEditorAction.DismissError) },
                title = { Text(stringResource(Res.string.video_editor)) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { onAction(VideoEditorAction.DismissError) }) {
                        Text(stringResource(Res.string.dismiss))
                    }
                },
            )
        }
        Surface(
            modifier = modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event -> handleEditorKey(event, state, onAction) },
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                EditorHeader(state = state, onAction = onAction)
                StoryboardWorkspace(
                    state = state,
                    playheadMicros = { playheadMicros.value },
                    previewImage = { previewImage.value },
                    importantFrameImages = importantFrameImages,
                    onAction = onAction,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EditorHeader(state: VideoEditorUiState, onAction: (VideoEditorAction) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(EditorDimens.HeaderHeight),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = EditorDimens.WorkspacePadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = { onAction(VideoEditorAction.BackToRecorder) },
                modifier = Modifier.width(108.dp).testTag("editor-back"),
            ) {
                MaterialIcon(MaterialIcons.ArrowBack, null, color = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.editor_recording), maxLines = 1)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.editor_montage_title, state.project.name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        Res.string.editor_project_meta,
                        formatProjectDuration(state.project.durationMicros),
                        state.project.canvasWidth,
                        state.project.canvasHeight,
                        state.project.frameRate,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
            HeaderIconButton(
                icon = MaterialIcons.Undo,
                description = stringResource(Res.string.undo),
                enabled = state.canUndo && !state.isExporting,
                tag = "editor-undo",
                onClick = { onAction(VideoEditorAction.Undo) },
            )
            HeaderIconButton(
                icon = MaterialIcons.Redo,
                description = stringResource(Res.string.redo),
                enabled = state.canRedo && !state.isExporting,
                tag = "editor-redo",
                onClick = { onAction(VideoEditorAction.Redo) },
            )
            TextButton(
                onClick = { onAction(VideoEditorAction.AddMedia) },
                enabled = !state.isImportingMedia && !state.isExporting,
                modifier = Modifier.width(100.dp).testTag("editor-add-media"),
            ) {
                MaterialIcon(MaterialIcons.AddCircle, null, color = MaterialTheme.colorScheme.primary, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.media_library), maxLines = 1)
            }
        }
    }
}

@Composable
private fun HeaderIconButton(
    icon: DrawableResource,
    description: String,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp).testTag(tag).semantics { contentDescription = description },
    ) {
        MaterialIcon(icon, null, color = MaterialTheme.colorScheme.onSurfaceVariant, size = 20.dp)
    }
}

@Composable
private fun StoryboardWorkspace(
    state: VideoEditorUiState,
    playheadMicros: () -> Long,
    previewImage: () -> ImageBitmap?,
    importantFrameImages: Map<ImportantFrameId, ImageBitmap>,
    onAction: (VideoEditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val storyboardWidth = if (maxWidth >= 1_280.dp) {
            EditorDimens.StoryboardWidth
        } else {
            EditorDimens.CompactStoryboardWidth
        }
        Row(
            modifier = Modifier.fillMaxSize().padding(EditorDimens.WorkspacePadding),
            horizontalArrangement = Arrangement.spacedBy(EditorDimens.WorkspaceGap),
        ) {
            RecordingHistoryPanel(
                state = state,
                onAction = onAction,
                modifier = Modifier.width(EditorDimens.RecordingHistoryWidth).fillMaxHeight(),
            )
            VideoReviewPanel(
                state = state,
                playheadMicros = playheadMicros,
                previewImage = previewImage,
                onAction = onAction,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            StoryboardPanel(
                state = state,
                importantFrameImages = importantFrameImages,
                onAction = onAction,
                modifier = Modifier.width(storyboardWidth).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun RecordingHistoryPanel(
    state: VideoEditorUiState,
    onAction: (VideoEditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primaryPath = state.project.primaryAssetId
        ?.let { assetId -> state.project.assets.firstOrNull { it.id == assetId } }
        ?.path
    EditorPanel(modifier) {
        Text(
            text = stringResource(Res.string.recording_history),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("editor-recording-history"),
            shape = RoundedCornerShape(EditorDimens.ControlRadius),
            color = EditorColors.RecessedSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            if (state.recentMediaPaths.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(Res.string.no_recordings_in_history),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(
                        items = state.recentMediaPaths,
                        key = { _, path -> path },
                    ) { index, path ->
                        RecentRecordingRow(
                            path = path,
                            selected = path == primaryPath,
                            enabled = !state.isImportingMedia && !state.isExporting,
                            onClick = { onAction(VideoEditorAction.OpenRecentMedia(path)) },
                            modifier = Modifier.testTag("editor-recording-history-item-$index"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentRecordingRow(
    path: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(7.dp),
        color = EditorColors.RaisedSurface,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text(
                text = recentMediaDisplayName(path),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = path,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun recentMediaDisplayName(path: String): String = path
    .replace('\\', '/')
    .substringAfterLast('/')
    .ifBlank { path }

@Composable
private fun VideoReviewPanel(
    state: VideoEditorUiState,
    playheadMicros: () -> Long,
    previewImage: () -> ImageBitmap?,
    onAction: (VideoEditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    EditorPanel(modifier) {
        Text(
            text = stringResource(Res.string.editor_video_sequence),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(28.dp))
        PreviewSurface(
            state = state,
            previewImage = previewImage,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Spacer(Modifier.height(16.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compactTransport = maxWidth < 600.dp
            PlaybackTransport(
                state = state,
                playheadMicros = playheadMicros,
                onAction = onAction,
                compact = compactTransport,
                modifier = Modifier.fillMaxWidth().height(if (compactTransport) 100.dp else 52.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        ReviewTimeline(
            state = state,
            playheadMicros = playheadMicros,
            onSeek = { onAction(VideoEditorAction.Seek(it)) },
            modifier = Modifier.fillMaxWidth().height(90.dp),
        )
    }
}

@Composable
private fun PreviewSurface(
    state: VideoEditorUiState,
    previewImage: () -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(EditorDimens.ControlRadius),
        color = Color(0xFF0C1116),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val image = previewImage()
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = stringResource(Res.string.editor_preview),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                )
            } else {
                Text(
                    text = if (state.project.durationMicros > 0L) {
                        stringResource(Res.string.editor_preview)
                    } else {
                        stringResource(Res.string.empty_editor)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.previewStatus == EditorPreviewStatus.Rendering) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun PlaybackTransport(
    state: VideoEditorUiState,
    playheadMicros: () -> Long,
    onAction: (VideoEditorAction) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(EditorDimens.ControlRadius),
        color = EditorColors.RecessedSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        if (compact) {
            CompactPlaybackTransport(state = state, playheadMicros = playheadMicros, onAction = onAction)
        } else {
            FullPlaybackTransport(state = state, playheadMicros = playheadMicros, onAction = onAction)
        }
    }
}

@Composable
private fun FullPlaybackTransport(
    state: VideoEditorUiState,
    playheadMicros: () -> Long,
    onAction: (VideoEditorAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatEditorTimestamp(playheadMicros()),
            modifier = Modifier.width(96.dp),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.weight(1f))
        TransportButton(
            icon = MaterialIcons.SkipPrevious,
            description = stringResource(Res.string.previous_frame),
            tag = "editor-previous-frame",
            enabled = state.project.durationMicros > 0L && !state.isExporting,
            onClick = { onAction(VideoEditorAction.StepFrames(-1)) },
        )
        TransportButton(
            icon = MaterialIcons.ReplayTen,
            description = stringResource(Res.string.rewind_ten_seconds),
            tag = "editor-rewind-10",
            enabled = state.project.durationMicros > 0L && !state.isExporting,
            onClick = { onAction(VideoEditorAction.Seek(playheadMicros() - TEN_SECONDS_MICROS)) },
        )
        TransportButton(
            icon = if (state.isPlaying) MaterialIcons.Pause else MaterialIcons.Play,
            description = stringResource(if (state.isPlaying) Res.string.pause else Res.string.play),
            tag = "editor-playback",
            enabled = state.project.durationMicros > 0L && !state.isExporting,
            primary = true,
            onClick = { onAction(VideoEditorAction.TogglePlayback) },
        )
        TransportButton(
            icon = MaterialIcons.ForwardTen,
            description = stringResource(Res.string.forward_ten_seconds),
            tag = "editor-forward-10",
            enabled = state.project.durationMicros > 0L && !state.isExporting,
            onClick = { onAction(VideoEditorAction.Seek(playheadMicros() + TEN_SECONDS_MICROS)) },
        )
        TransportButton(
            icon = MaterialIcons.SkipNext,
            description = stringResource(Res.string.next_frame),
            tag = "editor-next-frame",
            enabled = state.project.durationMicros > 0L && !state.isExporting,
            onClick = { onAction(VideoEditorAction.StepFrames(1)) },
        )
        Spacer(Modifier.width(8.dp))
        PlaybackSpeedMenu(
            speed = state.playbackSpeed,
            enabled = state.project.durationMicros > 0L && !state.isExporting,
            onSpeedSelected = { onAction(VideoEditorAction.SetPlaybackSpeed(it)) },
        )
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = { onAction(VideoEditorAction.MarkImportantFrame) },
            enabled = state.project.durationMicros > 0L && !state.isExporting,
            modifier = Modifier.width(152.dp).testTag("editor-mark-frame"),
        ) {
            MaterialIcon(MaterialIcons.Star, null, color = EditorColors.Important, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.mark_important_frame), color = EditorColors.Important, maxLines = 1)
        }
    }
}

@Composable
private fun CompactPlaybackTransport(
    state: VideoEditorUiState,
    playheadMicros: () -> Long,
    onAction: (VideoEditorAction) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatEditorTimestamp(playheadMicros()),
                modifier = Modifier.width(72.dp),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
            )
            TransportButton(
                icon = MaterialIcons.SkipPrevious,
                description = stringResource(Res.string.previous_frame),
                tag = "editor-previous-frame",
                enabled = state.project.durationMicros > 0L && !state.isExporting,
                onClick = { onAction(VideoEditorAction.StepFrames(-1)) },
            )
            TransportButton(
                icon = MaterialIcons.ReplayTen,
                description = stringResource(Res.string.rewind_ten_seconds),
                tag = "editor-rewind-10",
                enabled = state.project.durationMicros > 0L && !state.isExporting,
                onClick = { onAction(VideoEditorAction.Seek(playheadMicros() - TEN_SECONDS_MICROS)) },
            )
            TransportButton(
                icon = if (state.isPlaying) MaterialIcons.Pause else MaterialIcons.Play,
                description = stringResource(if (state.isPlaying) Res.string.pause else Res.string.play),
                tag = "editor-playback",
                enabled = state.project.durationMicros > 0L && !state.isExporting,
                primary = true,
                onClick = { onAction(VideoEditorAction.TogglePlayback) },
            )
            TransportButton(
                icon = MaterialIcons.ForwardTen,
                description = stringResource(Res.string.forward_ten_seconds),
                tag = "editor-forward-10",
                enabled = state.project.durationMicros > 0L && !state.isExporting,
                onClick = { onAction(VideoEditorAction.Seek(playheadMicros() + TEN_SECONDS_MICROS)) },
            )
            TransportButton(
                icon = MaterialIcons.SkipNext,
                description = stringResource(Res.string.next_frame),
                tag = "editor-next-frame",
                enabled = state.project.durationMicros > 0L && !state.isExporting,
                onClick = { onAction(VideoEditorAction.StepFrames(1)) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaybackSpeedMenu(
                speed = state.playbackSpeed,
                enabled = state.project.durationMicros > 0L && !state.isExporting,
                onSpeedSelected = { onAction(VideoEditorAction.SetPlaybackSpeed(it)) },
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { onAction(VideoEditorAction.MarkImportantFrame) },
                enabled = state.project.durationMicros > 0L && !state.isExporting,
                modifier = Modifier.height(44.dp).testTag("editor-mark-frame"),
            ) {
                MaterialIcon(MaterialIcons.Star, null, color = EditorColors.Important, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.mark_important_frame), color = EditorColors.Important, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PlaybackSpeedMenu(
    speed: EditorPlaybackSpeed,
    enabled: Boolean,
    onSpeedSelected: (EditorPlaybackSpeed) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val value = formatPlaybackSpeed(speed)
    val description = "${stringResource(Res.string.speed)}: $value"
    Box {
        TextButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier
                .height(44.dp)
                .widthIn(min = 64.dp)
                .testTag("editor-playback-speed")
                .semantics { contentDescription = description },
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            Text(
                text = value,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            EditorPlaybackSpeed.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(formatPlaybackSpeed(option)) },
                    onClick = {
                        expanded = false
                        onSpeedSelected(option)
                    },
                    trailingIcon = {
                        RadioButton(
                            selected = option == speed,
                            onClick = null,
                        )
                    },
                    modifier = Modifier.testTag("editor-playback-speed-${option.name.lowercase()}"),
                )
            }
        }
    }
}

private fun formatPlaybackSpeed(speed: EditorPlaybackSpeed): String = when (speed) {
    EditorPlaybackSpeed.HALF -> "0.5\u00d7"
    EditorPlaybackSpeed.NORMAL -> "1\u00d7"
    EditorPlaybackSpeed.ONE_AND_HALF -> "1.5\u00d7"
    EditorPlaybackSpeed.DOUBLE -> "2\u00d7"
}

@Composable
private fun TransportButton(
    icon: DrawableResource,
    description: String,
    tag: String,
    enabled: Boolean,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(if (primary) 48.dp else 44.dp).testTag(tag).semantics {
            contentDescription = description
        },
    ) {
        MaterialIcon(
            icon = icon,
            description = null,
            color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            size = if (primary) 26.dp else 22.dp,
        )
    }
}

@Composable
private fun ReviewTimeline(
    state: VideoEditorUiState,
    playheadMicros: () -> Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = state.project.durationMicros
    val segments = remember(state.project) { buildReviewSegments(state.project) }
    val currentOnSeek = rememberUpdatedState(onSeek)
    Surface(
        modifier = modifier
            .testTag("editor-timeline")
            .pointerInput(duration) {
                if (duration <= 0L) return@pointerInput
                val horizontalInset = EditorDimens.TimelineHorizontalInset.toPx()
                val trackWidth = (size.width - horizontalInset * 2f).coerceAtLeast(1f)

                fun seekTo(pointerX: Float) {
                    val progress = ((pointerX - horizontalInset) / trackWidth).coerceIn(0f, 1f)
                    currentOnSeek.value((progress * duration).toLong())
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastPointerX = down.position.x
                    seekTo(lastPointerX)
                    down.consume()

                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (change.position.x != lastPointerX) {
                            lastPointerX = change.position.x
                            seekTo(lastPointerX)
                        }
                        change.consume()
                        if (!change.pressed) break
                    }
                }
            },
        shape = RoundedCornerShape(EditorDimens.ControlRadius),
        color = EditorColors.RecessedSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                val trackLeft = EditorDimens.TimelineHorizontalInset.toPx()
                val trackRight = size.width - EditorDimens.TimelineHorizontalInset.toPx()
                val trackTop = 12.dp.toPx()
                val trackHeight = 48.dp.toPx()
                val trackWidth = (trackRight - trackLeft).coerceAtLeast(1f)
                drawRoundRect(
                    color = EditorColors.TimelineTrack,
                    topLeft = Offset(trackLeft, trackTop),
                    size = androidx.compose.ui.geometry.Size(trackWidth, trackHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()),
                )
                if (duration > 0L) {
                    segments.forEach { segment ->
                        val left = trackLeft + segment.startMicros.toFloat() / duration * trackWidth
                        val right = trackLeft + segment.endMicros.toFloat() / duration * trackWidth
                        drawRoundRect(
                            color = segment.color,
                            topLeft = Offset(left + 2.dp.toPx(), trackTop + 6.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size(
                                (right - left - 4.dp.toPx()).coerceAtLeast(2.dp.toPx()),
                                trackHeight - 12.dp.toPx(),
                            ),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                        )
                    }
                    state.project.importantFrames.forEach { marker ->
                        if (marker.timelineMicros <= duration) {
                            val x = trackLeft + marker.timelineMicros.toFloat() / duration * trackWidth
                            drawRoundRect(
                                color = EditorColors.Important,
                                topLeft = Offset(x - 2.dp.toPx(), trackTop),
                                size = androidx.compose.ui.geometry.Size(4.dp.toPx(), trackHeight),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                            )
                        }
                    }
                    val playheadX = trackLeft + playheadMicros().coerceIn(0L, duration).toFloat() / duration * trackWidth
                    drawLine(
                        color = EditorColors.Playhead,
                        start = Offset(playheadX, trackTop - 5.dp.toPx()),
                        end = Offset(playheadX, trackTop + trackHeight + 5.dp.toPx()),
                        strokeWidth = EditorDimens.TimelinePlayheadStrokeWidth.toPx(),
                    )
                    drawCircle(
                        color = EditorColors.Playhead,
                        radius = EditorDimens.TimelinePlayheadRadius.toPx(),
                        center = Offset(playheadX, trackTop - 2.dp.toPx()),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                timelineLabels(duration).forEach { label ->
                    Text(
                        text = label,
                        color = EditorColors.SubtleText,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun StoryboardPanel(
    state: VideoEditorUiState,
    importantFrameImages: Map<ImportantFrameId, ImageBitmap>,
    onAction: (VideoEditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val availableFrames = state.frameExportCandidates.filter { it.timelineMicros <= state.project.durationMicros }
    val visibleFrames = if (state.showOnlyImportantFrames) availableFrames.filter(FrameExportCandidate::important) else availableFrames
    val importantFrameIdsByTimestamp = state.project.importantFrames.associate { it.timelineMicros to it.id }
    EditorPanel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.storyboard),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(
                    Res.string.storyboard_selection_count,
                    state.includedFrameExportCount,
                    state.availableFrameExportCount,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterPill(
                text = stringResource(Res.string.all_frames),
                selected = !state.showOnlyImportantFrames,
                tag = "editor-storyboard-filter-all",
                width = 72.dp,
                onClick = { onAction(VideoEditorAction.SetShowOnlyImportantFrames(false)) },
            )
            Spacer(Modifier.width(8.dp))
            FilterPill(
                text = stringResource(Res.string.important_frames_filter),
                selected = state.showOnlyImportantFrames,
                tag = "editor-storyboard-filter-important",
                width = 104.dp,
                onClick = { onAction(VideoEditorAction.SetShowOnlyImportantFrames(true)) },
            )
            Spacer(Modifier.weight(1f))
            Checkbox(
                checked = state.allFrameExportCandidatesIncluded,
                onCheckedChange = { onAction(VideoEditorAction.SetAllFrameExportCandidatesIncluded(it)) },
                enabled = visibleFrames.isNotEmpty() && !state.isExporting,
                modifier = Modifier.size(24.dp).testTag("editor-storyboard-toggle-all-checkbox"),
            )
            TextButton(
                onClick = {
                    onAction(VideoEditorAction.SetAllFrameExportCandidatesIncluded(!state.allFrameExportCandidatesIncluded))
                },
                enabled = visibleFrames.isNotEmpty() && !state.isExporting,
                modifier = Modifier.widthIn(min = 0.dp).testTag("editor-storyboard-toggle-all"),
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text(stringResource(Res.string.all_or_no_frames), maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(16.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(EditorDimens.ControlRadius),
            color = EditorColors.RecessedSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            when {
                state.isPreparingFrameExport -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(Res.string.preparing_storyboard_frames),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                visibleFrames.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(Res.string.no_export_frames),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(8.dp).testTag("editor-storyboard-list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visibleFrames, key = { it.id.value }, contentType = FrameExportCandidate::important) { candidate ->
                        StoryboardFrameRow(
                            candidate = candidate,
                            image = importantFrameImages[candidate.id],
                            selected = candidate.id == state.selectedFrameExportCandidateId,
                            enabled = !state.isExporting,
                            onSelect = { onAction(VideoEditorAction.SelectFrameExportCandidate(candidate.id)) },
                            onIncludedChange = {
                                onAction(VideoEditorAction.SetFrameExportCandidateIncluded(candidate.id, it))
                            },
                            onRemoveImportant = importantFrameIdsByTimestamp[candidate.timelineMicros]?.let { markerId ->
                                { onAction(VideoEditorAction.RemoveImportantFrame(markerId)) }
                            },
                        )
                    }
                }
            }
        }
        StoryboardExportFooter(state = state, onAction = onAction)
    }
}

@Composable
private fun FilterPill(
    text: String,
    selected: Boolean,
    tag: String,
    width: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(width).height(32.dp).testTag(tag),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) EditorColors.PrimarySoft else Color.Transparent,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StoryboardFrameRow(
    candidate: FrameExportCandidate,
    image: ImageBitmap?,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onIncludedChange: (Boolean) -> Unit,
    onRemoveImportant: (() -> Unit)?,
) {
    Surface(
        onClick = onSelect,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(88.dp).testTag("editor-storyboard-row-${candidate.id.value}"),
        shape = RoundedCornerShape(7.dp),
        color = EditorColors.RaisedSurface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) EditorColors.Primary else EditorColors.Outline),
    ) {
        Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.width(124.dp).height(70.dp),
                shape = RoundedCornerShape(5.dp),
                color = EditorColors.TimelineTrack,
            ) {
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = formatEditorTimestamp(candidate.timelineMicros),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(if (candidate.important) Res.string.important_frame else Res.string.ordinary_frame),
                    color = if (candidate.important) EditorColors.Important else EditorColors.SubtleText,
                    fontWeight = if (candidate.important) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = candidate.included,
                        onCheckedChange = onIncludedChange,
                        enabled = enabled,
                        modifier = Modifier.size(24.dp).testTag("editor-storyboard-select-${candidate.id.value}"),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(Res.string.include_in_storyboard),
                        color = if (candidate.included) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
            onRemoveImportant?.let { onRemove ->
                val description = stringResource(Res.string.remove_important_frame)
                IconButton(
                    onClick = onRemove,
                    enabled = enabled,
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("editor-storyboard-remove-important-${candidate.id.value}")
                        .semantics { contentDescription = description },
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.delete),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StoryboardExportFooter(state: VideoEditorUiState, onAction: (VideoEditorAction) -> Unit) {
    Spacer(Modifier.height(8.dp))
    TextButton(
        onClick = { onAction(VideoEditorAction.AddCurrentFrameForExport) },
        enabled = state.project.durationMicros > 0L && !state.isExporting,
        modifier = Modifier.fillMaxWidth().height(40.dp).testTag("editor-add-current-frame"),
    ) {
        MaterialIcon(MaterialIcons.Add, null, color = EditorColors.Important, size = 18.dp)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(Res.string.add_current_frame), color = EditorColors.Important)
    }
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(Res.string.export_section),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(Res.string.selected_frames_count, state.includedFrameExportCount),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
    Spacer(Modifier.height(8.dp))
    val exportEnabled = state.includedFrameExportCount > 0 && !state.isPreparingFrameExport && !state.isExporting
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ExportOptionSelector(
            label = stringResource(Res.string.frame_output_format),
            options = listOf(
                ExportOption(FrameImageFormat.Png, "PNG", "png"),
                ExportOption(FrameImageFormat.Jpeg, "JPEG", "jpeg"),
            ),
            selected = state.frameOutputFormat,
            enabled = !state.isExporting,
            testTag = "editor-export-format",
            onSelected = { format -> onAction(VideoEditorAction.SetFrameOutputFormat(format)) },
            modifier = Modifier.weight(1f),
        )
        ExportOptionSelector(
            label = stringResource(Res.string.frame_resolution),
            options = FRAME_RESOLUTION_PERCENT_OPTIONS.map { percent ->
                ExportOption(percent, "$percent%", percent.toString())
            },
            selected = state.frameResolutionPercent,
            enabled = !state.isExporting,
            testTag = "editor-export-resolution",
            onSelected = { percent -> onAction(VideoEditorAction.SetFrameResolutionPercent(percent)) },
            modifier = Modifier.weight(1f),
        )
        if (state.frameOutputFormat == FrameImageFormat.Jpeg) {
            ExportOptionSelector(
                label = stringResource(Res.string.frame_jpeg_compression),
                options = listOf(
                    ExportOption(JpegCompression.None, stringResource(Res.string.compression_none), "none"),
                    ExportOption(JpegCompression.Low, stringResource(Res.string.compression_low), "low"),
                    ExportOption(JpegCompression.Medium, stringResource(Res.string.compression_medium), "medium"),
                    ExportOption(JpegCompression.High, stringResource(Res.string.compression_high), "high"),
                ),
                selected = state.frameJpegCompression,
                enabled = !state.isExporting,
                testTag = "editor-export-compression",
                onSelected = { compression -> onAction(VideoEditorAction.SetFrameJpegCompression(compression)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { onAction(VideoEditorAction.ExportStoryboard(ImportantFrameLayout.ContactSheet)) },
            enabled = exportEnabled,
            modifier = Modifier.weight(1f).height(48.dp).testTag("editor-export-contact-sheet"),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            MaterialIcon(MaterialIcons.Download, null, color = Color.White, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(Res.string.export_single_file), maxLines = 2, style = MaterialTheme.typography.labelMedium)
        }
        OutlinedButton(
            onClick = {
                onAction(VideoEditorAction.CopyStoryboardToClipboard(ImportantFrameLayout.ContactSheet))
            },
            enabled = exportEnabled,
            modifier = Modifier.weight(1f).height(48.dp).testTag("editor-copy-contact-sheet"),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            MaterialIcon(MaterialIcons.ContentCopy, null, color = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(Res.string.copy_single_file_to_clipboard),
                maxLines = 2,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { onAction(VideoEditorAction.ExportStoryboard(ImportantFrameLayout.SeparatePngFiles)) },
            enabled = exportEnabled,
            modifier = Modifier.weight(1f).height(48.dp).testTag("editor-export-frames"),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            MaterialIcon(MaterialIcons.Download, null, color = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(Res.string.export_separate_files),
                maxLines = 2,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        OutlinedButton(
            onClick = {
                onAction(VideoEditorAction.CopyStoryboardToClipboard(ImportantFrameLayout.SeparatePngFiles))
            },
            enabled = exportEnabled,
            modifier = Modifier.weight(1f).height(48.dp).testTag("editor-copy-frames"),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            MaterialIcon(MaterialIcons.ContentCopy, null, color = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(Res.string.copy_multiple_files_to_clipboard),
                maxLines = 2,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
    if (state.isExporting) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(progress = { state.exportProgress }, modifier = Modifier.weight(1f))
            TextButton(onClick = { onAction(VideoEditorAction.CancelExport) }) {
                Text(stringResource(Res.string.cancel))
            }
        }
    } else {
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(
                Res.string.frame_export_destination,
                state.frameResolutionPercent,
                when (state.frameOutputFormat) {
                    FrameImageFormat.Png -> "PNG"
                    FrameImageFormat.Jpeg -> "JPEG"
                },
            ),
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun <T> ExportOptionSelector(
    label: String,
    options: List<ExportOption<T>>,
    selected: T,
    enabled: Boolean,
    testTag: String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val expanded = remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { option -> option.value == selected }?.label.orEmpty()
    Column(modifier.testTag(testTag)) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Box {
            OutlinedButton(
                onClick = { expanded.value = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(44.dp).testTag("$testTag-toggle"),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(selectedLabel, modifier = Modifier.weight(1f), maxLines = 1)
                MaterialIcon(MaterialIcons.ExpandMore, null, color = MaterialTheme.colorScheme.onSurfaceVariant, size = 16.dp)
            }
            DropdownMenu(
                expanded = expanded.value,
                onDismissRequest = { expanded.value = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label, maxLines = 1) },
                        onClick = {
                            expanded.value = false
                            onSelected(option.value)
                        },
                        modifier = Modifier.testTag("$testTag-${option.testTagSuffix}"),
                    )
                }
            }
        }
    }
}

private data class ExportOption<T>(
    val value: T,
    val label: String,
    val testTagSuffix: String,
)

@Composable
private fun EditorPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(EditorDimens.PanelRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.fillMaxSize().padding(24.dp), content = content)
    }
}

private fun buildReviewSegments(project: EditorProject): List<ReviewSegment> {
    val duration = project.durationMicros
    if (duration <= 0L) return emptyList()
    val mediaClips = project.tracks
        .filter { it.kind == EditorTrackKind.Video && it.visible }
        .flatMap { track -> track.clips.filterIsInstance<EditorClip.Media>() }
        .sortedBy(EditorClip.Media::timelineStartMicros)
    return mediaClips.mapIndexed { index, clip ->
        ReviewSegment(
            startMicros = clip.timelineStartMicros.coerceIn(0L, duration),
            endMicros = clip.timelineEndMicros.coerceIn(0L, duration),
            color = EditorColors.SegmentColors[index % EditorColors.SegmentColors.size],
        )
    }
}

private fun timelineLabels(durationMicros: Long): List<String> = listOf(0L, 1L, 2L, 3L, 4L).map { step ->
    formatTimelineLabel(durationMicros * step / 4L)
}

private fun formatProjectDuration(micros: Long): String {
    val totalSeconds = micros.coerceAtLeast(0L) / MICROS_PER_SECOND
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds / 60L % 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

private fun formatTimelineLabel(micros: Long): String = formatProjectDuration(micros)

private fun formatEditorTimestamp(micros: Long): String {
    val safeMicros = max(0L, micros)
    val totalMillis = safeMicros / 1_000L
    val hours = totalMillis / 3_600_000L
    val minutes = totalMillis / 60_000L % 60L
    val seconds = totalMillis / 1_000L % 60L
    val millis = totalMillis % 1_000L
    val prefix = if (hours > 0L) "${hours.toString().padStart(2, '0')}:" else ""
    return "$prefix${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}.${millis.toString().padStart(3, '0')}"
}

private fun handleEditorKey(
    event: KeyEvent,
    state: VideoEditorUiState,
    onAction: (VideoEditorAction) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown || state.isExporting) return false
    return when {
        event.isCtrlPressed && event.key == Key.Z -> {
            onAction(VideoEditorAction.Undo)
            true
        }
        event.isCtrlPressed && event.key == Key.Y -> {
            onAction(VideoEditorAction.Redo)
            true
        }
        event.key == Key.Spacebar -> {
            onAction(VideoEditorAction.TogglePlayback)
            true
        }
        event.key == Key.DirectionLeft -> {
            onAction(VideoEditorAction.StepFrames(-1))
            true
        }
        event.key == Key.DirectionRight -> {
            onAction(VideoEditorAction.StepFrames(1))
            true
        }
        event.key == Key.M -> {
            onAction(VideoEditorAction.MarkImportantFrame)
            true
        }
        else -> false
    }
}

private data class ReviewSegment(
    val startMicros: Long,
    val endMicros: Long,
    val color: Color,
)

private const val MICROS_PER_SECOND = 1_000_000L
private const val TEN_SECONDS_MICROS = 10L * MICROS_PER_SECOND
private val FRAME_RESOLUTION_PERCENT_OPTIONS = listOf(25, 50, 75, 100)
