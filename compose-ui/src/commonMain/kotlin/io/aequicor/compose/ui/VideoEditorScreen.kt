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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
import io.aequicor.compose.resources.copy_storyboard_to_clipboard
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
import io.aequicor.compose.resources.forward_ten_seconds
import io.aequicor.compose.resources.important_frame
import io.aequicor.compose.resources.important_frames_filter
import io.aequicor.compose.resources.include_in_storyboard
import io.aequicor.compose.resources.mark_important_frame
import io.aequicor.compose.resources.media_library
import io.aequicor.compose.resources.next_frame
import io.aequicor.compose.resources.no_export_frames
import io.aequicor.compose.resources.ordinary_frame
import io.aequicor.compose.resources.original_size_frames_destination
import io.aequicor.compose.resources.pause
import io.aequicor.compose.resources.play
import io.aequicor.compose.resources.preparing_storyboard_frames
import io.aequicor.compose.resources.previous_frame
import io.aequicor.compose.resources.redo
import io.aequicor.compose.resources.rewind_ten_seconds
import io.aequicor.compose.resources.selected_frames_count
import io.aequicor.compose.resources.storyboard
import io.aequicor.compose.resources.storyboard_selection_count
import io.aequicor.compose.resources.undo
import io.aequicor.compose.resources.video_editor
import io.aequicor.editor.EditorClip
import io.aequicor.editor.EditorProject
import io.aequicor.editor.EditorTrackKind
import io.aequicor.editor.ImportantFrameId
import io.aequicor.editor.ImportantFrameLayout
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
                MaterialSymbol("arrow_back", null, color = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.sp)
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
                symbol = "undo",
                description = stringResource(Res.string.undo),
                enabled = state.canUndo && !state.isExporting,
                tag = "editor-undo",
                onClick = { onAction(VideoEditorAction.Undo) },
            )
            HeaderIconButton(
                symbol = "redo",
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
                MaterialSymbol("add_circle", null, color = MaterialTheme.colorScheme.primary, size = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.media_library), maxLines = 1)
            }
        }
    }
}

@Composable
private fun HeaderIconButton(
    symbol: String,
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
        MaterialSymbol(symbol, null, color = MaterialTheme.colorScheme.onSurfaceVariant, size = 20.sp)
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
        PlaybackTransport(
            state = state,
            playheadMicros = playheadMicros,
            onAction = onAction,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        )
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
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(EditorDimens.ControlRadius),
        color = EditorColors.RecessedSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
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
                symbol = "skip_previous",
                description = stringResource(Res.string.previous_frame),
                tag = "editor-previous-frame",
                enabled = state.project.durationMicros > 0L && !state.isExporting,
                onClick = { onAction(VideoEditorAction.StepFrames(-1)) },
            )
            TransportButton(
                symbol = "replay_10",
                description = stringResource(Res.string.rewind_ten_seconds),
                tag = "editor-rewind-10",
                enabled = state.project.durationMicros > 0L && !state.isExporting,
                onClick = { onAction(VideoEditorAction.Seek(playheadMicros() - TEN_SECONDS_MICROS)) },
            )
            TransportButton(
                symbol = if (state.isPlaying) "pause" else "play_arrow",
                description = stringResource(if (state.isPlaying) Res.string.pause else Res.string.play),
                tag = "editor-playback",
                enabled = state.project.durationMicros > 0L && !state.isExporting,
                primary = true,
                onClick = { onAction(VideoEditorAction.TogglePlayback) },
            )
            TransportButton(
                symbol = "forward_10",
                description = stringResource(Res.string.forward_ten_seconds),
                tag = "editor-forward-10",
                enabled = state.project.durationMicros > 0L && !state.isExporting,
                onClick = { onAction(VideoEditorAction.Seek(playheadMicros() + TEN_SECONDS_MICROS)) },
            )
            TransportButton(
                symbol = "skip_next",
                description = stringResource(Res.string.next_frame),
                tag = "editor-next-frame",
                enabled = state.project.durationMicros > 0L && !state.isExporting,
                onClick = { onAction(VideoEditorAction.StepFrames(1)) },
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { onAction(VideoEditorAction.MarkImportantFrame) },
                enabled = state.project.durationMicros > 0L && !state.isExporting,
                modifier = Modifier.width(152.dp).testTag("editor-mark-frame"),
            ) {
                MaterialSymbol("star", null, color = EditorColors.Important, size = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.mark_important_frame), color = EditorColors.Important, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TransportButton(
    symbol: String,
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
        MaterialSymbol(
            symbol = symbol,
            description = null,
            color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            size = if (primary) 26.sp else 22.sp,
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
                enabled = availableFrames.isNotEmpty() && !state.isExporting,
                modifier = Modifier.size(24.dp).testTag("editor-storyboard-toggle-all-checkbox"),
            )
            TextButton(
                onClick = {
                    onAction(VideoEditorAction.SetAllFrameExportCandidatesIncluded(!state.allFrameExportCandidatesIncluded))
                },
                enabled = availableFrames.isNotEmpty() && !state.isExporting,
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
        }
    }
}

@Composable
private fun StoryboardExportFooter(state: VideoEditorUiState, onAction: (VideoEditorAction) -> Unit) {
    Spacer(Modifier.height(16.dp))
    TextButton(
        onClick = { onAction(VideoEditorAction.AddCurrentFrameForExport) },
        enabled = state.project.durationMicros > 0L && !state.isExporting,
        modifier = Modifier.fillMaxWidth().height(40.dp).testTag("editor-add-current-frame"),
    ) {
        MaterialSymbol("add", null, color = EditorColors.Important, size = 18.sp)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(Res.string.add_current_frame), color = EditorColors.Important)
    }
    Spacer(Modifier.height(16.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(16.dp))
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
    Spacer(Modifier.height(12.dp))
    val exportEnabled = state.includedFrameExportCount > 0 && !state.isPreparingFrameExport && !state.isExporting
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
            MaterialSymbol("download", null, color = Color.White, size = 18.sp)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(Res.string.export_single_file), maxLines = 2, style = MaterialTheme.typography.labelMedium)
        }
        OutlinedButton(
            onClick = { onAction(VideoEditorAction.CopyStoryboardToClipboard) },
            enabled = exportEnabled,
            modifier = Modifier.weight(1f).height(48.dp).testTag("editor-copy-storyboard"),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            MaterialSymbol("content_copy", null, color = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(Res.string.copy_storyboard_to_clipboard),
                maxLines = 2,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { onAction(VideoEditorAction.ExportStoryboard(ImportantFrameLayout.SeparatePngFiles)) },
        enabled = exportEnabled,
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("editor-export-frames"),
    ) {
        MaterialSymbol("download", null, color = MaterialTheme.colorScheme.onSurfaceVariant, size = 20.sp)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(Res.string.export_separate_files), maxLines = 1)
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
            text = stringResource(Res.string.original_size_frames_destination),
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

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

@Composable
private fun MaterialSymbol(
    symbol: String,
    description: String?,
    color: Color,
    size: androidx.compose.ui.unit.TextUnit,
) {
    val iconSize = with(LocalDensity.current) { size.toDp() }
    val iconModifier = Modifier
        .size(iconSize)
        .let { current ->
            if (description == null) current else current.semantics { contentDescription = description }
        }
    if (symbol == "replay_10" || symbol == "forward_10") {
        Box(modifier = iconModifier, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) { drawTenSecondIcon(symbol = symbol, color = color) }
            Text(
                text = "10",
                color = color,
                fontSize = size * 0.36f,
                lineHeight = size * 0.36f,
                fontWeight = FontWeight.Bold,
            )
        }
    } else {
        Canvas(iconModifier) { drawEditorIcon(symbol = symbol, color = color) }
    }
}

private fun DrawScope.drawEditorIcon(symbol: String, color: Color) {
    val unit = size.minDimension
    val stroke = Stroke(width = unit * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun point(x: Float, y: Float) = Offset(unit * x, unit * y)
    when (symbol) {
        "arrow_back" -> {
            drawLine(color, point(0.18f, 0.5f), point(0.82f, 0.5f), stroke.width, StrokeCap.Round)
            drawLine(color, point(0.18f, 0.5f), point(0.42f, 0.26f), stroke.width, StrokeCap.Round)
            drawLine(color, point(0.18f, 0.5f), point(0.42f, 0.74f), stroke.width, StrokeCap.Round)
        }
        "add_circle" -> {
            drawCircle(color, radius = unit * 0.4f, style = stroke)
            drawLine(color, point(0.3f, 0.5f), point(0.7f, 0.5f), stroke.width, StrokeCap.Round)
            drawLine(color, point(0.5f, 0.3f), point(0.5f, 0.7f), stroke.width, StrokeCap.Round)
        }
        "undo", "redo" -> drawHistoryIcon(symbol = symbol, color = color, stroke = stroke)
        "skip_previous", "skip_next" -> {
            val previous = symbol == "skip_previous"
            val barX = if (previous) 0.22f else 0.78f
            drawLine(color, point(barX, 0.22f), point(barX, 0.78f), stroke.width, StrokeCap.Round)
            val path = Path().apply {
                if (previous) {
                    moveTo(unit * 0.72f, unit * 0.2f)
                    lineTo(unit * 0.34f, unit * 0.5f)
                    lineTo(unit * 0.72f, unit * 0.8f)
                } else {
                    moveTo(unit * 0.28f, unit * 0.2f)
                    lineTo(unit * 0.66f, unit * 0.5f)
                    lineTo(unit * 0.28f, unit * 0.8f)
                }
                close()
            }
            drawPath(path, color)
        }
        "play_arrow" -> drawPath(
            path = Path().apply {
                moveTo(unit * 0.3f, unit * 0.18f)
                lineTo(unit * 0.78f, unit * 0.5f)
                lineTo(unit * 0.3f, unit * 0.82f)
                close()
            },
            color = color,
        )
        "pause" -> {
            drawLine(color, point(0.35f, 0.2f), point(0.35f, 0.8f), unit * 0.16f, StrokeCap.Butt)
            drawLine(color, point(0.65f, 0.2f), point(0.65f, 0.8f), unit * 0.16f, StrokeCap.Butt)
        }
        "star" -> drawPath(
            path = Path().apply {
                moveTo(unit * 0.5f, unit * 0.08f)
                lineTo(unit * 0.62f, unit * 0.36f)
                lineTo(unit * 0.92f, unit * 0.38f)
                lineTo(unit * 0.69f, unit * 0.58f)
                lineTo(unit * 0.76f, unit * 0.9f)
                lineTo(unit * 0.5f, unit * 0.72f)
                lineTo(unit * 0.24f, unit * 0.9f)
                lineTo(unit * 0.31f, unit * 0.58f)
                lineTo(unit * 0.08f, unit * 0.38f)
                lineTo(unit * 0.38f, unit * 0.36f)
                close()
            },
            color = color,
        )
        "download" -> {
            drawLine(color, point(0.5f, 0.12f), point(0.5f, 0.66f), stroke.width, StrokeCap.Round)
            drawLine(color, point(0.5f, 0.66f), point(0.28f, 0.44f), stroke.width, StrokeCap.Round)
            drawLine(color, point(0.5f, 0.66f), point(0.72f, 0.44f), stroke.width, StrokeCap.Round)
            drawLine(color, point(0.2f, 0.86f), point(0.8f, 0.86f), stroke.width, StrokeCap.Round)
        }
        "content_copy" -> {
            drawRect(
                color = color,
                topLeft = point(0.34f, 0.14f),
                size = Size(unit * 0.5f, unit * 0.58f),
                style = stroke,
            )
            drawRect(
                color = color,
                topLeft = point(0.16f, 0.32f),
                size = Size(unit * 0.5f, unit * 0.58f),
                style = stroke,
            )
        }
        "add" -> {
            drawLine(color, point(0.18f, 0.5f), point(0.82f, 0.5f), stroke.width, StrokeCap.Round)
            drawLine(color, point(0.5f, 0.18f), point(0.5f, 0.82f), stroke.width, StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawHistoryIcon(symbol: String, color: Color, stroke: Stroke) {
    val unit = size.minDimension
    val undo = symbol == "undo"
    val path = Path().apply {
        val startX = if (undo) 0.28f else 0.72f
        moveTo(unit * startX, unit * 0.32f)
        cubicTo(
            unit * if (undo) 0.72f else 0.28f,
            unit * 0.18f,
            unit * if (undo) 0.86f else 0.14f,
            unit * 0.5f,
            unit * if (undo) 0.76f else 0.24f,
            unit * 0.76f,
        )
    }
    drawPath(path, color, style = stroke)
    val arrowX = if (undo) 0.28f else 0.72f
    val outerX = if (undo) 0.48f else 0.52f
    drawLine(color, Offset(unit * arrowX, unit * 0.32f), Offset(unit * outerX, unit * 0.14f), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(unit * arrowX, unit * 0.32f), Offset(unit * outerX, unit * 0.48f), stroke.width, StrokeCap.Round)
}

private fun DrawScope.drawTenSecondIcon(symbol: String, color: Color) {
    val unit = size.minDimension
    val forward = symbol == "forward_10"
    val stroke = Stroke(width = unit * 0.09f, cap = StrokeCap.Round)
    drawArc(
        color = color,
        startAngle = if (forward) 205f else -25f,
        sweepAngle = if (forward) 270f else -270f,
        useCenter = false,
        topLeft = Offset(unit * 0.14f, unit * 0.14f),
        size = Size(unit * 0.72f, unit * 0.72f),
        style = stroke,
    )
    val arrowX = if (forward) 0.78f else 0.22f
    val outerX = if (forward) 0.58f else 0.42f
    drawLine(color, Offset(unit * arrowX, unit * 0.16f), Offset(unit * outerX, unit * 0.1f), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(unit * arrowX, unit * 0.16f), Offset(unit * arrowX, unit * 0.36f), stroke.width, StrokeCap.Round)
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
