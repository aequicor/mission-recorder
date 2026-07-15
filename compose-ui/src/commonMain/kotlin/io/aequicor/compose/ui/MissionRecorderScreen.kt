package io.aequicor.compose.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aequicor.compose.resources.Res
import io.aequicor.compose.resources.app_name
import io.aequicor.compose.resources.apply
import io.aequicor.compose.resources.audio
import io.aequicor.compose.resources.audio_frames
import io.aequicor.compose.resources.audio_gain_percent
import io.aequicor.compose.resources.capture_source
import io.aequicor.compose.resources.capture_cursor
import io.aequicor.compose.resources.show_capture_border
import io.aequicor.compose.resources.show_application_in_recording
import io.aequicor.compose.resources.show_input_overlay
import io.aequicor.compose.resources.show_mouse_trail
import io.aequicor.compose.resources.choose_output_file
import io.aequicor.compose.resources.choose_video_file
import io.aequicor.compose.resources.close
import io.aequicor.compose.resources.create_storyboard
import io.aequicor.compose.resources.creating_storyboard
import io.aequicor.compose.resources.dismiss
import io.aequicor.compose.resources.dropped_frames
import io.aequicor.compose.resources.effective_fps
import io.aequicor.compose.resources.finish_and_open_editor
import io.aequicor.compose.resources.frame_rate
import io.aequicor.compose.resources.frames_per_second
import io.aequicor.compose.resources.hide_mini_controller
import io.aequicor.compose.resources.important_frame_captured
import io.aequicor.compose.resources.file_name_pattern
import io.aequicor.compose.resources.file_name_pattern_hint
import io.aequicor.compose.resources.configure_hotkeys
import io.aequicor.compose.resources.keyboard
import io.aequicor.compose.resources.mark_important_frame
import io.aequicor.compose.resources.mission_recorder
import io.aequicor.compose.resources.megabits_per_second
import io.aequicor.compose.resources.microphone
import io.aequicor.compose.resources.microphone_gain
import io.aequicor.compose.resources.microphone_off
import io.aequicor.compose.resources.mute_microphone
import io.aequicor.compose.resources.mute_system_audio
import io.aequicor.compose.resources.no_capture_sources
import io.aequicor.compose.resources.output
import io.aequicor.compose.resources.output_directory
import io.aequicor.compose.resources.output_naming
import io.aequicor.compose.resources.open_recordings_folder
import io.aequicor.compose.resources.open_editor
import io.aequicor.compose.resources.output_path
import io.aequicor.compose.resources.output_device
import io.aequicor.compose.resources.overwrite_output
import io.aequicor.compose.resources.pause
import io.aequicor.compose.resources.cancel
import io.aequicor.compose.resources.enable_preview
import io.aequicor.compose.resources.permission_check_again
import io.aequicor.compose.resources.permission_continue
import io.aequicor.compose.resources.permission_microphone_description
import io.aequicor.compose.resources.permission_microphone_settings
import io.aequicor.compose.resources.permission_microphone_title
import io.aequicor.compose.resources.permission_not_now
import io.aequicor.compose.resources.permission_open_settings
import io.aequicor.compose.resources.permission_request_explanation
import io.aequicor.compose.resources.permission_restart_hint
import io.aequicor.compose.resources.permission_screen_description
import io.aequicor.compose.resources.permission_screen_settings
import io.aequicor.compose.resources.permission_screen_title
import io.aequicor.compose.resources.permission_system_audio_description
import io.aequicor.compose.resources.permission_system_audio_settings
import io.aequicor.compose.resources.permission_system_audio_title
import io.aequicor.compose.resources.record
import io.aequicor.compose.resources.record_mouse_trail
import io.aequicor.compose.resources.recording_workspace
import io.aequicor.compose.resources.refresh_sources
import io.aequicor.compose.resources.replay_buffer
import io.aequicor.compose.resources.replay_buffering
import io.aequicor.compose.resources.replay_duration
import io.aequicor.compose.resources.replay_failed
import io.aequicor.compose.resources.replay_minutes
import io.aequicor.compose.resources.replay_preparing
import io.aequicor.compose.resources.replay_saved_to
import io.aequicor.compose.resources.replay_saving
import io.aequicor.compose.resources.replay_stopping
import io.aequicor.compose.resources.resume
import io.aequicor.compose.resources.save_replay
import io.aequicor.compose.resources.saved_to
import io.aequicor.compose.resources.saving_screenshot
import io.aequicor.compose.resources.selected_source
import io.aequicor.compose.resources.preview_preparing
import io.aequicor.compose.resources.preview_image_description
import io.aequicor.compose.resources.preview_permission_required
import io.aequicor.compose.resources.screenshot_saved_to
import io.aequicor.compose.resources.select_area
import io.aequicor.compose.resources.select_region_screenshot
import io.aequicor.compose.resources.selecting_area
import io.aequicor.compose.resources.solo_microphone
import io.aequicor.compose.resources.solo_system_audio
import io.aequicor.compose.resources.starting
import io.aequicor.compose.resources.start_replay_buffer
import io.aequicor.compose.resources.star
import io.aequicor.compose.resources.status_completed
import io.aequicor.compose.resources.status_failed
import io.aequicor.compose.resources.status_idle
import io.aequicor.compose.resources.status_paused
import io.aequicor.compose.resources.status_preparing
import io.aequicor.compose.resources.status_recording
import io.aequicor.compose.resources.status_stopping
import io.aequicor.compose.resources.stop
import io.aequicor.compose.resources.stop_replay_buffer
import io.aequicor.compose.resources.stopping
import io.aequicor.compose.resources.storyboard
import io.aequicor.compose.resources.storyboard_contact_sheet
import io.aequicor.compose.resources.storyboard_input_video
import io.aequicor.compose.resources.storyboard_saved_to
import io.aequicor.compose.resources.storyboard_separate_png
import io.aequicor.compose.resources.system_audio
import io.aequicor.compose.resources.system_audio_gain
import io.aequicor.compose.resources.take_screenshot
import io.aequicor.compose.resources.take_screen_screenshot
import io.aequicor.compose.resources.tray_open
import io.aequicor.compose.resources.unavailable
import io.aequicor.compose.resources.unmute_microphone
import io.aequicor.compose.resources.unmute_system_audio
import io.aequicor.compose.resources.unsolo_microphone
import io.aequicor.compose.resources.unsolo_system_audio
import io.aequicor.compose.resources.video
import io.aequicor.compose.resources.video_bitrate
import io.aequicor.compose.resources.video_frames
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private val LapisLazuli = Color(0xFF26619C)
private val RecordingRed = Color(0xFFC83F49)
private val SuccessGreen = Color(0xFF217A5B)
private val WorkspaceBackground = Color(0xFFF3F5F7)
private val PreviewBackground = Color(0xFF171A1D)
private val Graphite = Color(0xFF20272D)
private val MutedGraphite = Color(0xFF59646D)
private val Hairline = Color(0xFFD8DDE2)
private val PipBackground = Color(0xFFE7F1F8)
private val PipSurface = Color.White
private val PipSurfaceVariant = Color(0xFFF2F6F9)
private val PipWindowActions = Color(0xFFEDF9E4)
private val PipOutline = Color(0xFFD5E0E8)
private val PipPrimary = LapisLazuli
private val PipOnSurface = Graphite
private val PipOnSurfaceVariant = MutedGraphite
private val PipCaution = Color(0xFFB26A00)

/** Shortcut labels displayed by recording controls without coupling the UI to a platform hotkey API. */
data class RecorderShortcutLabels(
    val recording: String = "Ctrl+Shift+F9",
    val pause: String = "Ctrl+Shift+F10",
)

private val MissionRecorderTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
)

private val MissionRecorderShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

@Composable
fun MissionRecorderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = LapisLazuli,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD9E8F7),
            onPrimaryContainer = Color(0xFF102D46),
            secondary = Color(0xFF35675A),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFD8ECE5),
            onSecondaryContainer = Color(0xFF163B32),
            background = WorkspaceBackground,
            onBackground = Graphite,
            surface = Color.White,
            onSurface = Graphite,
            surfaceVariant = Color(0xFFEDF0F2),
            onSurfaceVariant = MutedGraphite,
            outline = Color(0xFF89939B),
            outlineVariant = Hairline,
            error = RecordingRed,
            errorContainer = Color(0xFFFFDADB),
            onErrorContainer = Color(0xFF641E25),
        ),
        typography = MissionRecorderTypography,
        shapes = MissionRecorderShapes,
        content = content,
    )
}

@Composable
private fun MissionRecorderPipTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = PipPrimary,
            onPrimary = PipSurface,
            background = PipBackground,
            onBackground = PipOnSurface,
            surface = PipSurface,
            onSurface = PipOnSurface,
            surfaceVariant = PipSurfaceVariant,
            onSurfaceVariant = PipOnSurfaceVariant,
            outline = PipOutline,
            outlineVariant = PipOutline,
            error = Color(0xFFFF7B84),
        ),
        typography = MissionRecorderTypography,
        shapes = MissionRecorderShapes,
        content = content,
    )
}

@Composable
fun MissionRecorderScreen(
    state: RecorderUiState,
    onAction: (RecorderUiAction) -> Unit,
    previewImage: ImageBitmap? = null,
    onConfigureShortcuts: (() -> Unit)? = null,
    shortcutLabels: RecorderShortcutLabels = RecorderShortcutLabels(),
    modifier: Modifier = Modifier,
) = MissionRecorderScreen(
    state = state,
    onAction = onAction,
    previewImage = { previewImage },
    onConfigureShortcuts = onConfigureShortcuts,
    shortcutLabels = shortcutLabels,
    modifier = modifier,
)

/**
 * Renders the recorder while containing live preview invalidations to the preview pane.
 *
 * The image state is read only where the frame is drawn, so frequent frame updates do not
 * recompose unrelated controls such as text fields.
 */
@Composable
fun MissionRecorderScreen(
    state: RecorderUiState,
    onAction: (RecorderUiAction) -> Unit,
    previewImage: State<ImageBitmap?>,
    onConfigureShortcuts: (() -> Unit)? = null,
    shortcutLabels: RecorderShortcutLabels = RecorderShortcutLabels(),
    modifier: Modifier = Modifier,
) = MissionRecorderScreen(
    state = state,
    onAction = onAction,
    previewImage = { previewImage.value },
    onConfigureShortcuts = onConfigureShortcuts,
    shortcutLabels = shortcutLabels,
    modifier = modifier,
)

@Composable
private fun MissionRecorderScreen(
    state: RecorderUiState,
    onAction: (RecorderUiAction) -> Unit,
    previewImage: () -> ImageBitmap?,
    onConfigureShortcuts: (() -> Unit)?,
    shortcutLabels: RecorderShortcutLabels,
    modifier: Modifier,
) {
    MissionRecorderTheme {
        state.permissionPrompt?.let { prompt ->
            PermissionPromptDialog(prompt = prompt, onAction = onAction)
        }
        if (state.showOutputNamingDialog) {
            OutputNamingDialog(state = state, onAction = onAction)
        }
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                AppHeader(
                    state = state,
                    onAction = onAction,
                    onConfigureShortcuts = onConfigureShortcuts,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (maxWidth < 860.dp) {
                        CompactWorkspace(state = state, onAction = onAction, previewImage = previewImage)
                    } else {
                        WideWorkspace(state = state, onAction = onAction, previewImage = previewImage)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TransportBar(state = state, onAction = onAction, shortcutLabels = shortcutLabels)
            }
        }
    }
}

@Composable
private fun PermissionPromptDialog(
    prompt: RecorderPermissionPrompt,
    onAction: (RecorderUiAction) -> Unit,
) {
    val title = when (prompt.permission) {
        RecorderPermissionKind.ScreenRecording -> stringResource(Res.string.permission_screen_title)
        RecorderPermissionKind.Microphone -> stringResource(Res.string.permission_microphone_title)
        RecorderPermissionKind.SystemAudio -> stringResource(Res.string.permission_system_audio_title)
    }
    val description = when (prompt.permission) {
        RecorderPermissionKind.ScreenRecording -> stringResource(Res.string.permission_screen_description)
        RecorderPermissionKind.Microphone -> stringResource(Res.string.permission_microphone_description)
        RecorderPermissionKind.SystemAudio -> stringResource(Res.string.permission_system_audio_description)
    }
    val instructions = when (prompt.action) {
        RecorderPermissionAction.Request -> stringResource(Res.string.permission_request_explanation)
        RecorderPermissionAction.OpenSettings -> when (prompt.permission) {
            RecorderPermissionKind.ScreenRecording -> stringResource(Res.string.permission_screen_settings)
            RecorderPermissionKind.Microphone -> stringResource(Res.string.permission_microphone_settings)
            RecorderPermissionKind.SystemAudio -> stringResource(Res.string.permission_system_audio_settings)
        }
    }
    AlertDialog(
        onDismissRequest = {
            if (!prompt.isBusy) onAction(RecorderUiAction.DismissPermissionPrompt)
        },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(description)
                Text(instructions, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (prompt.restartMayBeRequired) {
                    Text(
                        text = stringResource(Res.string.permission_restart_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                prompt.errorMessage?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (prompt.isBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAction(
                        when (prompt.action) {
                            RecorderPermissionAction.Request -> RecorderUiAction.ContinuePermissionRequest
                            RecorderPermissionAction.OpenSettings -> RecorderUiAction.OpenPermissionSettings
                        },
                    )
                },
                enabled = !prompt.isBusy,
                modifier = Modifier.testTag("permission-primary-action"),
            ) {
                Text(
                    stringResource(
                        when (prompt.action) {
                            RecorderPermissionAction.Request -> Res.string.permission_continue
                            RecorderPermissionAction.OpenSettings -> Res.string.permission_open_settings
                        },
                    ),
                )
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (prompt.action == RecorderPermissionAction.OpenSettings) {
                    TextButton(
                        onClick = { onAction(RecorderUiAction.RetryPermissionCheck) },
                        enabled = !prompt.isBusy,
                        modifier = Modifier.testTag("permission-check-again"),
                    ) {
                        Text(stringResource(Res.string.permission_check_again))
                    }
                }
                TextButton(
                    onClick = { onAction(RecorderUiAction.DismissPermissionPrompt) },
                    enabled = !prompt.isBusy,
                ) {
                    Text(stringResource(Res.string.permission_not_now))
                }
            }
        },
    )
}

/**
 * Renders the compact recorder controller.
 *
 * [onOpenEditor] requests editing the active recording after it is finalized, or the latest completed recording.
 */
@Composable
fun MiniRecorderController(
    state: RecorderUiState,
    onAction: (RecorderUiAction) -> Unit,
    shortcutLabels: RecorderShortcutLabels = RecorderShortcutLabels(),
    modifier: Modifier = Modifier,
    onExpand: () -> Unit = {},
    onHide: () -> Unit = {},
    onOpenEditor: () -> Unit = {},
    previewImage: ImageBitmap? = null,
) = MiniRecorderController(
    state = state,
    onAction = onAction,
    shortcutLabels = shortcutLabels,
    modifier = modifier,
    onExpand = onExpand,
    onHide = onHide,
    onOpenEditor = onOpenEditor,
)

/**
 * Renders the compact recorder controller.
 *
 * [onOpenEditor] requests editing the active recording after it is finalized, or the latest completed recording.
 */
@Composable
fun MiniRecorderController(
    state: RecorderUiState,
    onAction: (RecorderUiAction) -> Unit,
    previewImage: State<ImageBitmap?>,
    shortcutLabels: RecorderShortcutLabels = RecorderShortcutLabels(),
    modifier: Modifier = Modifier,
    onExpand: () -> Unit = {},
    onHide: () -> Unit = {},
    onOpenEditor: () -> Unit = {},
) = MiniRecorderController(
    state = state,
    onAction = onAction,
    shortcutLabels = shortcutLabels,
    modifier = modifier,
    onExpand = onExpand,
    onHide = onHide,
    onOpenEditor = onOpenEditor,
)

@Composable
private fun MiniRecorderController(
    state: RecorderUiState,
    onAction: (RecorderUiAction) -> Unit,
    shortcutLabels: RecorderShortcutLabels,
    modifier: Modifier,
    onExpand: () -> Unit,
    onHide: () -> Unit,
    onOpenEditor: () -> Unit,
) {
    MissionRecorderPipTheme {
        Box(
            modifier = modifier.fillMaxSize(),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(
                    topStart = 0.dp,
                    topEnd = 0.dp,
                    bottomEnd = 25.dp,
                    bottomStart = 25.dp,
                ),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.fillMaxSize()) {
                    MiniHeader(
                        state = state,
                        onExpand = onExpand,
                        onHide = onHide,
                    )
                    MiniTransportControls(
                        state = state,
                        onAction = onAction,
                        shortcutLabels = shortcutLabels,
                        modifier = Modifier.weight(1f),
                        onOpenEditor = onOpenEditor,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniHeader(
    state: RecorderUiState,
    onExpand: () -> Unit,
    onHide: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().height(57.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(24.dp).background(PipWindowActions),
            horizontalArrangement = Arrangement.spacedBy(space = 4.dp, alignment = Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniWindowButton(
                label = stringResource(Res.string.hide_mini_controller),
                testTag = "mini-hide",
                onClick = onHide,
            ) {
                MaterialIcon(
                    icon = MaterialIcons.Remove,
                    description = null,
                    color = MaterialTheme.colorScheme.onSurface,
                    size = 18.dp,
                )
            }
            MiniWindowButton(
                label = stringResource(Res.string.tray_open),
                testTag = "mini-expand",
                onClick = onExpand,
            ) {
                MaterialIcon(
                    icon = MaterialIcons.FilterNone,
                    description = null,
                    color = MaterialTheme.colorScheme.onSurface,
                    size = 18.dp,
                )
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(33.dp),
            contentAlignment = Alignment.Center,
        ) {
            MiniStatus(state)
        }
    }
}

@Composable
private fun MiniStatus(state: RecorderUiState) {
    val label = if (state.isReplayActive) replayStatusLabel(state.replayStatus) else statusLabel(state.status)
    val color = if (state.isReplayActive) {
        when (state.replayStatus) {
            ReplayUiStatus.Buffering -> MaterialTheme.colorScheme.primary
            ReplayUiStatus.Failed -> MaterialTheme.colorScheme.error
            ReplayUiStatus.Preparing,
            ReplayUiStatus.Saving,
            ReplayUiStatus.Stopping,
                -> PipCaution

            ReplayUiStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    } else {
        when (state.status) {
            RecorderStatus.Recording -> RecordingRed
            RecorderStatus.Paused,
            RecorderStatus.Preparing,
            RecorderStatus.Stopping,
                -> PipCaution

            RecorderStatus.Completed -> SuccessGreen
            RecorderStatus.Failed -> MaterialTheme.colorScheme.error
            RecorderStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Surface(
        modifier = Modifier.width(68.dp).height(26.dp)
            .semantics { contentDescription = label }
            .testTag("mini-status-container"),
        shape = RoundedCornerShape(13.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                modifier = Modifier.testTag("mini-status"),
                color = color,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MiniWindowButton(
    label: String,
    testTag: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    MiniIconButton(
        label = label,
        enabled = true,
        onClick = onClick,
        modifier = Modifier.size(24.dp).testTag(testTag),
    ) {
        content()
    }
}

@Composable
private fun MiniTransportControls(
    state: RecorderUiState,
    onAction: (RecorderUiAction) -> Unit,
    shortcutLabels: RecorderShortcutLabels,
    modifier: Modifier,
    onOpenEditor: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth()
            .testTag("mini-transport-controls"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (state.isReplayActive) {
            MiniReplayControls(state = state, onAction = onAction)
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(top = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                MiniOpenEditorButton(state = state, onClick = onOpenEditor)
                MiniRecordButton(state = state, onAction = onAction, shortcut = shortcutLabels.recording)
                MiniScreenshotButtons(state = state, onAction = onAction)
            }
        }
    }
}

@Composable
private fun MiniReplayControls(state: RecorderUiState, onAction: (RecorderUiAction) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        val saveLabel = stringResource(Res.string.save_replay)
        val canSave = state.canSaveReplay
        MiniIconButton(
            label = saveLabel,
            enabled = canSave,
            onClick = { onAction(RecorderUiAction.SaveReplayBuffer) },
            modifier = Modifier.size(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .testTag("mini-save-replay"),
        ) {
            MaterialIcon(
                icon = MaterialIcons.Capture,
                description = null,
                color = if (canSave) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                size = 24.dp,
            )
        }
        val stopLabel = stringResource(Res.string.stop_replay_buffer)
        val canStop = state.canStopReplay
        MiniIconButton(
            label = stopLabel,
            enabled = canStop,
            onClick = { onAction(RecorderUiAction.StopReplayBuffer) },
            modifier = Modifier.size(24.dp)
                .clip(CircleShape)
                .background(if (canStop) RecordingRed else MaterialTheme.colorScheme.surface)
                .testTag("mini-stop-replay"),
        ) {
            MaterialIcon(
                icon = MaterialIcons.Stop,
                description = null,
                color = if (canStop) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                size = 24.dp,
            )
        }
    }
}

@Composable
private fun AppHeader(
    state: RecorderUiState,
    onAction: (RecorderUiAction) -> Unit,
    onConfigureShortcuts: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp).background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(Res.drawable.mission_recorder),
            contentDescription = null,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = stringResource(Res.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(Res.string.recording_workspace),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        if (onConfigureShortcuts != null) {
            val configureHotkeysLabel = stringResource(Res.string.configure_hotkeys)
            RecorderTooltipIconButton(
                label = configureHotkeysLabel,
                enabled = true,
                onClick = onConfigureShortcuts,
                modifier = Modifier.testTag("configure-hotkeys"),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.keyboard),
                    contentDescription = configureHotkeysLabel,
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        if (state.isReplayActive || state.replayStatus == ReplayUiStatus.Failed) {
            ReplayStatusIndicator(state.replayStatus)
        } else {
            StatusIndicator(state.status)
        }
    }
}

@Composable
private fun OutputNamingDialog(state: RecorderUiState, onAction: (RecorderUiAction) -> Unit) {
    var directory by remember(state.showOutputNamingDialog, state.outputDirectory) {
        mutableStateOf(state.outputDirectory)
    }
    var fileNamePattern by remember(state.showOutputNamingDialog, state.outputFileNamePattern) {
        mutableStateOf(state.outputFileNamePattern)
    }
    val validPattern = fileNamePattern.isNotBlank() &&
            '/' !in fileNamePattern &&
            '\\' !in fileNamePattern &&
            fileNamePattern.endsWith(".mp4", ignoreCase = true)
    AlertDialog(
        onDismissRequest = { onAction(RecorderUiAction.DismissOutputNamingDialog) },
        title = { Text(stringResource(Res.string.output_naming)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = directory,
                    onValueChange = { directory = it },
                    label = { Text(stringResource(Res.string.output_directory)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("output-directory"),
                    isError = directory.isBlank(),
                )
                OutlinedTextField(
                    value = fileNamePattern,
                    onValueChange = { fileNamePattern = it },
                    label = { Text(stringResource(Res.string.file_name_pattern)) },
                    supportingText = { Text(stringResource(Res.string.file_name_pattern_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("output-file-name-pattern"),
                    isError = !validPattern,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAction(RecorderUiAction.ApplyOutputNaming(directory, fileNamePattern))
                },
                enabled = directory.isNotBlank() && validPattern,
                modifier = Modifier.testTag("apply-output-naming"),
            ) {
                Text(stringResource(Res.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(RecorderUiAction.DismissOutputNamingDialog) }) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}

@Composable
private fun WideWorkspace(
    state: RecorderUiState,
    onAction: (RecorderUiAction) -> Unit,
    previewImage: () -> ImageBitmap?,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        SourcePane(
            state = state,
            onAction = onAction,
            modifier = Modifier.width(292.dp).fillMaxHeight(),
        )
        VerticalDivider(
            modifier = Modifier.fillMaxHeight().width(1.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        PreviewPane(
            state = state,
            onAction = onAction,
            previewImage = previewImage,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        VerticalDivider(
            modifier = Modifier.fillMaxHeight().width(1.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        SettingsPane(
            state = state,
            onAction = onAction,
            modifier = Modifier.width(340.dp).fillMaxHeight(),
        )
    }
}

@Composable
private fun CompactWorkspace(
    state: RecorderUiState,
    onAction: (RecorderUiAction) -> Unit,
    previewImage: () -> ImageBitmap?,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        PreviewPane(
            state = state,
            onAction = onAction,
            previewImage = previewImage,
            modifier = Modifier.fillMaxWidth().height(240.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SourcePane(
            state = state,
            onAction = onAction,
            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 360.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsPane(
            state = state,
            onAction = onAction,
            modifier = Modifier.fillMaxWidth(),
            scrollable = false,
        )
    }
}

@Composable
private fun SourcePane(
    state: RecorderUiState,
    onAction: (RecorderUiAction) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface).padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle(stringResource(Res.string.capture_source))
            Spacer(Modifier.weight(1f))
            val refreshDescription = stringResource(Res.string.refresh_sources)
            RecorderTooltipIconButton(
                label = refreshDescription,
                enabled = !state.isBusy && !state.isRefreshingSources,
                onClick = { onAction(RecorderUiAction.RefreshSources) },
                modifier = Modifier.testTag("refresh-sources"),
            ) {
                MaterialIcon(MaterialIcons.Refresh, refreshDescription)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (state.isRefreshingSources) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
        }
        OutlinedButton(
            onClick = { onAction(RecorderUiAction.SelectRegion) },
            enabled = !state.isBusy && !state.isRefreshingSources,
            modifier = Modifier.fillMaxWidth().height(40.dp).testTag("select-region"),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            MaterialIcon(icon = MaterialIcons.Crop, description = null, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (state.isSelectingRegion) {
                    stringResource(Res.string.selecting_area)
                } else {
                    stringResource(Res.string.select_area)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        if (state.sources.isEmpty() && !state.isRefreshingSources) {
            Text(
                text = stringResource(Res.string.no_capture_sources),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.sources, key = { it.id }) { source ->
                    SourceRow(
                        source = source,
                        selected = source.id == state.selectedSourceId,
                        enabled = !state.isBusy,
                        onClick = { onAction(RecorderUiAction.SelectSource(source.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    source: RecorderSourceUi,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics {
                this.selected = selected
                role = Role.RadioButton
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MaterialIcon(
            icon = source.kind.icon,
            description = null,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = source.displayName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        RadioButton(selected = selected, onClick = null, enabled = enabled)
    }
}

@Composable
private fun PreviewPane(
    state: RecorderUiState,
    onAction: (RecorderUiAction) -> Unit,
    previewImage: () -> ImageBitmap?,
    modifier: Modifier,
) {
    Column(modifier = modifier.padding(20.dp)) {
        val selected = state.sources.firstOrNull { it.id == state.selectedSourceId }
        val previewImageDescription = stringResource(Res.string.preview_image_description)
        Text(
            text = stringResource(Res.string.selected_source),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(6.dp),
            color = PreviewBackground,
            border = BorderStroke(1.dp, Color(0xFF343A40)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                val currentPreviewImage = previewImage()
                if (currentPreviewImage != null) {
                    Image(
                        bitmap = currentPreviewImage,
                        contentDescription = previewImageDescription,
                        modifier = Modifier.fillMaxSize().background(Color.White).testTag("preview-image"),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.High,
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MaterialIcon(
                            icon = selected?.kind?.icon ?: MaterialIcons.Capture,
                            description = null,
                            color = Color(0xFF90BCE5),
                            size = 54.dp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = selected?.displayName ?: stringResource(Res.string.no_capture_sources),
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.previewStatus == PreviewUiStatus.PermissionRequired) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = stringResource(Res.string.preview_permission_required),
                                modifier = Modifier.padding(horizontal = 24.dp),
                                color = Color(0xFFD5E0E8),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { onAction(RecorderUiAction.RequestPreviewPermission) },
                                modifier = Modifier.testTag("enable-preview"),
                            ) {
                                Text(stringResource(Res.string.enable_preview))
                            }
                        }
                    }
                }
                if (state.previewStatus == PreviewUiStatus.Preparing) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LinearProgressIndicator(modifier = Modifier.width(180.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(Res.string.preview_preparing),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                ImportantFrameCaptureEffect(
                    trigger = state.importantFrameCaptureSequence,
                    compact = false,
                    modifier = Modifier.testTag("recorder-important-frame-capture-effect"),
                )
            }
        }
    }
}

@Composable
private fun ImportantFrameCaptureEffect(
    trigger: Long,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val alpha = remember { Animatable(0f) }
    var handledTrigger by remember { mutableLongStateOf(trigger) }
    LaunchedEffect(trigger) {
        if (trigger > handledTrigger) {
            alpha.snapTo(0.92f)
            alpha.animateTo(targetValue = 0f, animationSpec = tween(durationMillis = 300))
        }
        handledTrigger = trigger
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            .background(ImportantFrameGold),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(Res.drawable.star),
                contentDescription = null,
                modifier = Modifier.size(if (compact) 30.dp else 54.dp),
                tint = Color(0xFF292410),
            )
            if (!compact) {
                Text(
                    text = stringResource(Res.string.important_frame_captured),
                    color = Color(0xFF292410),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SettingsPane(
    state: RecorderUiState,
    onAction: (RecorderUiAction) -> Unit,
    modifier: Modifier,
    scrollable: Boolean = true,
) {
    val scrollModifier = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surface).then(scrollModifier).padding(20.dp),
    ) {
        state.errorMessage?.let { message ->
            ErrorBanner(message = message, onDismiss = { onAction(RecorderUiAction.DismissError) })
            Spacer(Modifier.height(18.dp))
        }
        state.lastOutputPath?.let { outputPath ->
            SavedPathNotice(
                text = stringResource(Res.string.saved_to, outputPath),
                openFolderLabel = stringResource(Res.string.open_recordings_folder),
                testTag = "open-saved-recording-folder",
                onOpenFolder = { onAction(RecorderUiAction.OpenRecordingsFolder) },
            )
            Spacer(Modifier.height(18.dp))
        }
        state.lastStoryboardPath?.let { outputPath ->
            Text(
                text = stringResource(Res.string.storyboard_saved_to, outputPath),
                style = MaterialTheme.typography.bodySmall,
                color = SuccessGreen,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(18.dp))
        }
        state.lastReplayPath?.let { outputPath ->
            Text(
                text = stringResource(Res.string.replay_saved_to, outputPath),
                style = MaterialTheme.typography.bodySmall,
                color = SuccessGreen,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(18.dp))
        }
        state.lastScreenshotPath?.let { outputPath ->
            Text(
                text = stringResource(Res.string.screenshot_saved_to, outputPath),
                style = MaterialTheme.typography.bodySmall,
                color = SuccessGreen,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(18.dp))
        }

        SectionTitle(stringResource(Res.string.audio))
        Spacer(Modifier.height(12.dp))
        MicrophoneSelector(state = state, onAction = onAction)
        Spacer(Modifier.height(8.dp))
        AudioGainControl(
            label = stringResource(Res.string.microphone_gain),
            percent = state.microphoneGainPercent,
            enabled = state.selectedMicrophoneId != null && !state.isBusy,
            testTag = "microphone-gain",
            onPercentChanged = { percent ->
                onAction(RecorderUiAction.SetMicrophoneGainPercent(percent))
            },
        )
        Spacer(Modifier.height(14.dp))
        SystemAudioSelector(state = state, onAction = onAction)
        Spacer(Modifier.height(8.dp))
        AudioGainControl(
            label = stringResource(Res.string.system_audio_gain),
            percent = state.systemAudioGainPercent,
            enabled = state.systemAudioAvailable && !state.isBusy,
            testTag = "system-audio-gain",
            onPercentChanged = { percent ->
                onAction(RecorderUiAction.SetSystemAudioGainPercent(percent))
            },
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.system_audio), style = MaterialTheme.typography.bodyMedium)
                if (!state.systemAudioAvailable) {
                    Text(
                        stringResource(Res.string.unavailable),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                AudioLevelBar(
                    level = state.systemAudioLevel,
                    color = SuccessGreen,
                    modifier = Modifier.fillMaxWidth().testTag("system-audio-level"),
                )
            }
            Spacer(Modifier.width(8.dp))
            AudioMuteButton(
                muted = state.systemAudioMuted,
                enabled = state.canToggleSystemAudioMute,
                muteLabel = stringResource(Res.string.mute_system_audio),
                unmuteLabel = stringResource(Res.string.unmute_system_audio),
                mutedSymbol = MaterialIcons.VolumeOff,
                unmutedSymbol = MaterialIcons.VolumeUp,
                testTag = "system-audio-mute",
                onMutedChange = { muted -> onAction(RecorderUiAction.SetSystemAudioMuted(muted)) },
            )
            AudioSoloButton(
                solo = state.systemAudioSolo,
                enabled = state.canToggleAudioSolo,
                soloLabel = stringResource(Res.string.solo_system_audio),
                unsoloLabel = stringResource(Res.string.unsolo_system_audio),
                testTag = "system-audio-solo",
                onSoloChange = { solo -> onAction(RecorderUiAction.SetSystemAudioSolo(solo)) },
            )
            Spacer(Modifier.width(4.dp))
            Switch(
                checked = state.systemAudioEnabled,
                onCheckedChange = { onAction(RecorderUiAction.SetSystemAudioEnabled(it)) },
                enabled = state.systemAudioAvailable && !state.isBusy,
            )
        }

        Spacer(Modifier.height(22.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(22.dp))
        SectionTitle(stringResource(Res.string.video))
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(Res.string.frame_rate),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        FrameRateSelector(
            frameRate = state.frameRate,
            enabled = !state.isBusy,
            onFrameRateChanged = { onAction(RecorderUiAction.SetFrameRate(it)) },
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.video_bitrate),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.megabits_per_second, state.videoBitrateMbps),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(
            value = state.videoBitrateMbps.toFloat(),
            onValueChange = { value ->
                onAction(RecorderUiAction.SetVideoBitrateMbps(value.roundToInt()))
            },
            modifier = Modifier.fillMaxWidth().testTag("video-bitrate"),
            enabled = !state.isBusy,
            valueRange = MIN_VIDEO_BITRATE_MBPS.toFloat()..MAX_VIDEO_BITRATE_MBPS.toFloat(),
            steps = MAX_VIDEO_BITRATE_MBPS - MIN_VIDEO_BITRATE_MBPS - 1,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.show_capture_border),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = state.showCaptureBorder,
                onCheckedChange = { enabled ->
                    onAction(RecorderUiAction.SetShowCaptureBorder(enabled))
                },
                modifier = Modifier.testTag("show-capture-border"),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.show_input_overlay),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = state.showInputOverlay,
                onCheckedChange = { enabled -> onAction(RecorderUiAction.SetShowInputOverlay(enabled)) },
                modifier = Modifier.testTag("show-input-overlay"),
                enabled = !state.isBusy,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.capture_cursor),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = state.captureCursor,
                onCheckedChange = { enabled -> onAction(RecorderUiAction.SetCaptureCursor(enabled)) },
                modifier = Modifier.testTag("capture-cursor"),
                enabled = !state.isBusy,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.show_mouse_trail),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = state.showMouseTrail,
                onCheckedChange = { enabled -> onAction(RecorderUiAction.SetShowMouseTrail(enabled)) },
                modifier = Modifier.testTag("show-mouse-trail"),
                enabled = !state.isBusy,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.record_mouse_trail),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = state.recordMouseTrail,
                onCheckedChange = { enabled -> onAction(RecorderUiAction.SetRecordMouseTrail(enabled)) },
                modifier = Modifier.testTag("record-mouse-trail"),
                enabled = !state.isBusy,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.show_application_in_recording),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = state.showApplicationInRecording,
                onCheckedChange = { enabled ->
                    onAction(RecorderUiAction.SetShowApplicationInRecording(enabled))
                },
                modifier = Modifier.testTag("show-application-in-recording"),
            )
        }

        Spacer(Modifier.height(22.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(22.dp))
        SectionTitle(stringResource(Res.string.output))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.outputPath,
            onValueChange = { onAction(RecorderUiAction.SetOutputPath(it)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isBusy,
            singleLine = true,
            label = { Text(stringResource(Res.string.output_path)) },
            trailingIcon = {
                val description = stringResource(Res.string.choose_output_file)
                IconButton(
                    onClick = { onAction(RecorderUiAction.ChooseOutputFile) },
                    enabled = !state.isBusy && !state.isRefreshingSources,
                    modifier = Modifier.testTag("choose-output-file"),
                ) {
                    MaterialIcon(MaterialIcons.Folder, description)
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { onAction(RecorderUiAction.ShowOutputNamingDialog) },
            modifier = Modifier.fillMaxWidth().testTag("output-naming"),
            enabled = !state.isBusy && !state.isRefreshingSources,
        ) {
            MaterialIcon(MaterialIcons.Edit, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.output_naming))
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { onAction(RecorderUiAction.OpenRecordingsFolder) },
            modifier = Modifier.fillMaxWidth().testTag("open-recordings-folder"),
            enabled = state.outputPath.isNotBlank(),
        ) {
            MaterialIcon(MaterialIcons.FolderOpen, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.open_recordings_folder))
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.overwrite_output),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = state.overwriteOutput,
                onCheckedChange = { enabled -> onAction(RecorderUiAction.SetOverwriteOutput(enabled)) },
                modifier = Modifier.testTag("overwrite-output"),
                enabled = !state.isBusy,
            )
        }

        Spacer(Modifier.height(22.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(22.dp))
        ReplayControls(state = state, onAction = onAction)

        Spacer(Modifier.height(22.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(22.dp))
        EditorControls(state = state, onAction = onAction)
    }
}

@Composable
private fun SavedPathNotice(
    text: String,
    openFolderLabel: String,
    testTag: String,
    onOpenFolder: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = SuccessGreen,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        RecorderTooltipIconButton(
            label = openFolderLabel,
            enabled = true,
            onClick = onOpenFolder,
            modifier = Modifier.size(34.dp).testTag(testTag),
        ) {
            MaterialIcon(
                icon = MaterialIcons.FolderOpen,
                description = openFolderLabel,
                color = SuccessGreen,
                size = 20.dp,
            )
        }
    }
}

@Composable
private fun AudioGainControl(
    label: String,
    percent: Int,
    enabled: Boolean,
    testTag: String,
    onPercentChanged: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(Res.string.audio_gain_percent, percent),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
    Slider(
        value = percent.toFloat(),
        onValueChange = { value -> onPercentChanged(value.roundToInt()) },
        modifier = Modifier.fillMaxWidth().testTag(testTag),
        enabled = enabled,
        valueRange = MIN_AUDIO_GAIN_PERCENT.toFloat()..MAX_AUDIO_GAIN_PERCENT.toFloat(),
        steps = (MAX_AUDIO_GAIN_PERCENT - MIN_AUDIO_GAIN_PERCENT) / 5 - 1,
    )
}

@Composable
private fun ReplayControls(state: RecorderUiState, onAction: (RecorderUiAction) -> Unit) {
    SectionTitle(stringResource(Res.string.replay_buffer))
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(Res.string.replay_duration),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(Res.string.replay_minutes, state.replayDurationMinutes),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
    }
    Slider(
        value = state.replayDurationMinutes.toFloat(),
        onValueChange = { value ->
            onAction(RecorderUiAction.SetReplayDurationMinutes(value.roundToInt()))
        },
        modifier = Modifier.fillMaxWidth().testTag("replay-duration"),
        enabled = !state.isBusy,
        valueRange = 1f..60f,
        steps = 58,
    )

    if (state.isReplayActive) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = replayStatusLabel(state.replayStatus),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = formatElapsed(state.replayRetainedMilliseconds),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (state.replayStatus != ReplayUiStatus.Buffering) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val stopDescription = stringResource(Res.string.stop_replay_buffer)
            IconButton(
                onClick = { onAction(RecorderUiAction.StopReplayBuffer) },
                modifier = Modifier.size(46.dp).testTag("stop-replay"),
                enabled = state.canStopReplay,
            ) {
                MaterialIcon(MaterialIcons.Stop, stopDescription)
            }
            Button(
                onClick = { onAction(RecorderUiAction.SaveReplayBuffer) },
                modifier = Modifier.weight(1f).height(46.dp).testTag("save-replay"),
                enabled = state.canSaveReplay,
                shape = RoundedCornerShape(6.dp),
            ) {
                MaterialIcon(MaterialIcons.Capture, null, color = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.save_replay), maxLines = 1)
            }
        }
    } else {
        Button(
            onClick = { onAction(RecorderUiAction.StartReplayBuffer) },
            modifier = Modifier.fillMaxWidth().height(46.dp).testTag("start-replay"),
            enabled = state.canStartReplay,
            shape = RoundedCornerShape(6.dp),
        ) {
            MaterialIcon(MaterialIcons.Record, null, color = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.start_replay_buffer))
        }
    }
}

@Composable
private fun AudioLevelBar(
    level: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val normalizedLevel = level.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .progressSemantics(normalizedLevel),
    ) {
        if (normalizedLevel > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(normalizedLevel)
                    .background(color),
            )
        }
    }
}

@Composable
private fun AudioMuteButton(
    muted: Boolean,
    enabled: Boolean,
    muteLabel: String,
    unmuteLabel: String,
    mutedSymbol: DrawableResource,
    unmutedSymbol: DrawableResource,
    testTag: String,
    onMutedChange: (Boolean) -> Unit,
) {
    val actionLabel = if (muted) unmuteLabel else muteLabel
    val iconColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        muted -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    RecorderTooltipIconButton(
        label = actionLabel,
        enabled = enabled,
        onClick = { onMutedChange(!muted) },
        modifier = Modifier
            .size(40.dp)
            .testTag(testTag)
            .semantics { selected = muted },
    ) {
        MaterialIcon(
            icon = if (muted) mutedSymbol else unmutedSymbol,
            description = actionLabel,
            color = iconColor,
            size = 20.dp,
        )
    }
}

@Composable
private fun AudioSoloButton(
    solo: Boolean,
    enabled: Boolean,
    soloLabel: String,
    unsoloLabel: String,
    testTag: String,
    onSoloChange: (Boolean) -> Unit,
) {
    val actionLabel = if (solo) unsoloLabel else soloLabel
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        solo -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    RecorderTooltipIconButton(
        label = actionLabel,
        enabled = enabled,
        onClick = { onSoloChange(!solo) },
        modifier = Modifier
            .size(40.dp)
            .testTag(testTag)
            .semantics { selected = solo },
    ) {
        Text(
            text = "S",
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun MiniScreenshotButtons(
    state: RecorderUiState,
    onAction: (RecorderUiAction) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().wrapContentHeight().testTag("mini-screenshot-group"),
        shape = RoundedCornerShape(19.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniScreenScreenshotButton(
                state = state,
                onAction = onAction,
                modifier = Modifier.weight(1f),
            )
            VerticalDivider(
                modifier = Modifier.height(24.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            MiniRegionScreenshotButton(
                state = state,
                onAction = onAction,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MiniScreenScreenshotButton(
    state: RecorderUiState,
    onAction: (RecorderUiAction) -> Unit,
    modifier: Modifier,
) {
    val label = stringResource(
        if (state.isSavingScreenshot) Res.string.saving_screenshot else Res.string.take_screen_screenshot,
    )
    val enabled = state.canTakeScreenScreenshot
    MiniIconButton(
        label = label,
        enabled = enabled,
        onClick = { onAction(RecorderUiAction.TakeScreenScreenshot) },
        modifier = modifier.testTag("mini-screen-screenshot"),
    ) {
        MaterialIcon(
            icon = MaterialIcons.Monitor,
            description = null,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            size = 24.dp,
        )
    }
}

@Composable
private fun MiniRegionScreenshotButton(
    state: RecorderUiState,
    onAction: (RecorderUiAction) -> Unit,
    modifier: Modifier,
) {
    val label = stringResource(
        if (state.isSelectingRegion) Res.string.selecting_area else Res.string.select_region_screenshot,
    )
    val enabled = !state.isBusy && !state.isRefreshingSources && !state.isSavingScreenshot
    MiniIconButton(
        label = label,
        enabled = enabled,
        onClick = { onAction(RecorderUiAction.SelectRegionAndTakeScreenshot) },
        modifier = modifier.testTag("mini-region-screenshot"),
    ) {
        MaterialIcon(
            icon = MaterialIcons.Crop,
            description = null,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            size = 24.dp,
        )
    }
}

@Composable
private fun MiniRecordButton(
    state: RecorderUiState,
    onAction: (RecorderUiAction) -> Unit,
    shortcut: String,
) {
    val label = when (state.status) {
        RecorderStatus.Preparing -> stringResource(Res.string.starting)
        RecorderStatus.Stopping -> stringResource(Res.string.stopping)
        RecorderStatus.Recording,
        RecorderStatus.Paused,
            -> stringResource(Res.string.stop)

        else -> stringResource(Res.string.record)
    }
    val enabled = state.hasActiveRecording || state.canStart
    val containerColor = when {
        !enabled -> MaterialTheme.colorScheme.surface
        state.hasActiveRecording -> RecordingRed
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier.size(48.dp).testTag("mini-record-layout"),
        contentAlignment = Alignment.Center,
    ) {
        MiniIconButton(
            label = label,
            shortcut = shortcut,
            enabled = enabled,
            onClick = {
                onAction(
                    if (state.hasActiveRecording) RecorderUiAction.StopRecording else RecorderUiAction.StartRecording,
                )
            },
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(containerColor)
                .testTag("mini-record-toggle"),
        ) {
            Icon(
                painter = painterResource(if (state.hasActiveRecording) MaterialIcons.Stop else MaterialIcons.Play),
                contentDescription = "Pay/Pause",
                tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MiniOpenEditorButton(
    state: RecorderUiState,
    onClick: () -> Unit,
) {
    val label = stringResource(
        if (state.hasActiveRecording) Res.string.finish_and_open_editor else Res.string.open_editor,
    )
    val enabled = state.hasActiveRecording || state.canOpenEditor
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp)
            .semantics { contentDescription = label }
            .testTag("mini-open-editor"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().testTag("mini-open-editor-icon"),
            contentAlignment = Alignment.Center,
        ) {
            MaterialIcon(
                icon = MaterialIcons.ContentCut,
                description = null,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                size = 24.dp,
            )
        }
    }
}

@Composable
private fun MiniIconButton(
    label: String,
    shortcut: String? = null,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val buttonContent: @Composable () -> Unit = {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.semantics { contentDescription = label },
            content = content,
        )
    }
    val shortcutLabel = shortcut?.takeIf(String::isNotBlank)
    if (shortcutLabel == null) {
        buttonContent()
    } else {
        RecorderTooltipBox(label = shortcutLabel, content = buttonContent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecorderTooltipIconButton(
    label: String,
    shortcut: String? = null,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    RecorderTooltipBox(label = label, shortcut = shortcut) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecorderTooltipBox(
    label: String,
    shortcut: String? = null,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(shortcutTooltipLabel(label, shortcut)) } },
        state = rememberTooltipState(),
        content = content,
    )
}

internal fun shortcutTooltipLabel(label: String, shortcut: String?): String =
    if (shortcut == null) label else "$label · $shortcut"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoryboardControls(state: RecorderUiState, onAction: (RecorderUiAction) -> Unit) {
    SectionTitle(stringResource(Res.string.storyboard))
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = state.storyboardInputPath,
        onValueChange = { onAction(RecorderUiAction.SetStoryboardInputPath(it)) },
        modifier = Modifier.fillMaxWidth().testTag("storyboard-input-video"),
        enabled = !state.isBusy,
        singleLine = true,
        label = { Text(stringResource(Res.string.storyboard_input_video)) },
    )
    Spacer(Modifier.height(12.dp))
    val options = listOf(
        StoryboardMode.SeparatePngFiles to stringResource(Res.string.storyboard_separate_png),
        StoryboardMode.ContactSheet to stringResource(Res.string.storyboard_contact_sheet),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = state.storyboardMode == mode,
                onClick = { onAction(RecorderUiAction.SetStoryboardMode(mode)) },
                modifier = Modifier.testTag(
                    when (mode) {
                        StoryboardMode.SeparatePngFiles -> "storyboard-mode-separate"
                        StoryboardMode.ContactSheet -> "storyboard-mode-contact"
                    },
                ),
                enabled = !state.isBusy,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(label, maxLines = 2) },
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { onAction(RecorderUiAction.ExportStoryboard) },
        modifier = Modifier.fillMaxWidth().height(46.dp).testTag("export-storyboard"),
        enabled = state.canExportStoryboard,
        shape = RoundedCornerShape(6.dp),
    ) {
        MaterialIcon(MaterialIcons.Capture, null, color = Color.White)
        Spacer(Modifier.width(8.dp))
        Text(
            if (state.isExportingStoryboard) {
                stringResource(Res.string.creating_storyboard)
            } else {
                stringResource(Res.string.create_storyboard)
            },
        )
    }
}

@Composable
private fun EditorControls(state: RecorderUiState, onAction: (RecorderUiAction) -> Unit) {
    SectionTitle(stringResource(Res.string.open_editor))
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = state.storyboardInputPath,
        onValueChange = { onAction(RecorderUiAction.SetStoryboardInputPath(it)) },
        modifier = Modifier.fillMaxWidth().testTag("storyboard-input-video"),
        enabled = !state.isBusy,
        singleLine = true,
        label = { Text(stringResource(Res.string.storyboard_input_video)) },
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { onAction(RecorderUiAction.ChooseStoryboardInputFile) },
        enabled = !state.isBusy,
        modifier = Modifier.fillMaxWidth().height(56.dp).testTag("choose-storyboard-input-video"),
    ) {
        MaterialIcon(MaterialIcons.FolderOpen, null, color = MaterialTheme.colorScheme.primary, size = 18.dp)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(Res.string.choose_video_file), maxLines = 1)
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { onAction(RecorderUiAction.OpenEditor) },
        modifier = Modifier.fillMaxWidth().height(46.dp).testTag("open-video-editor"),
        enabled = state.canOpenEditor,
    ) {
        Text(stringResource(Res.string.open_editor))
    }
}

@Composable
private fun MicrophoneSelector(state: RecorderUiState, onAction: (RecorderUiAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.microphones.firstOrNull { it.id == state.selectedMicrophoneId }
    Text(
        text = stringResource(Res.string.microphone),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isBusy,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                MaterialIcon(
                    if (selected == null || state.microphoneMuted) MaterialIcons.MicOff else MaterialIcons.Mic,
                    null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = selected?.displayName ?: stringResource(Res.string.microphone_off),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MaterialIcon(MaterialIcons.ExpandMore, null)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 260.dp, max = 420.dp),
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.microphone_off)) },
                    leadingIcon = { MaterialIcon(MaterialIcons.MicOff, null) },
                    onClick = {
                        expanded = false
                        onAction(RecorderUiAction.SelectMicrophone(null))
                    },
                )
                state.microphones.forEach { microphone ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = microphone.displayName,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = { MaterialIcon(MaterialIcons.Mic, null) },
                        onClick = {
                            expanded = false
                            onAction(RecorderUiAction.SelectMicrophone(microphone.id))
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        AudioMuteButton(
            muted = state.microphoneMuted,
            enabled = state.canToggleMicrophoneMute,
            muteLabel = stringResource(Res.string.mute_microphone),
            unmuteLabel = stringResource(Res.string.unmute_microphone),
            mutedSymbol = MaterialIcons.MicOff,
            unmutedSymbol = MaterialIcons.Mic,
            testTag = "microphone-mute",
            onMutedChange = { muted -> onAction(RecorderUiAction.SetMicrophoneMuted(muted)) },
        )
        AudioSoloButton(
            solo = state.microphoneSolo,
            enabled = state.canToggleAudioSolo,
            soloLabel = stringResource(Res.string.solo_microphone),
            unsoloLabel = stringResource(Res.string.unsolo_microphone),
            testTag = "microphone-solo",
            onSoloChange = { solo -> onAction(RecorderUiAction.SetMicrophoneSolo(solo)) },
        )
    }
    Spacer(Modifier.height(6.dp))
    AudioLevelBar(
        level = state.microphoneLevel,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().testTag("microphone-level"),
    )
}

@Composable
private fun SystemAudioSelector(state: RecorderUiState, onAction: (RecorderUiAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.systemAudioSources.firstOrNull { source -> source.id == state.selectedSystemAudioId }
    Text(
        text = stringResource(Res.string.output_device),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = state.systemAudioAvailable && !state.isBusy,
            modifier = Modifier.fillMaxWidth().testTag("system-audio-selector"),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) {
            MaterialIcon(MaterialIcons.VolumeUp, null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = selected?.displayName ?: stringResource(Res.string.unavailable),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MaterialIcon(MaterialIcons.ExpandMore, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.systemAudioSources.forEach { source ->
                DropdownMenuItem(
                    text = { Text(source.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onAction(RecorderUiAction.SelectSystemAudio(source.id))
                    },
                    leadingIcon = { MaterialIcon(MaterialIcons.VolumeUp, null) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrameRateSelector(
    frameRate: Int,
    enabled: Boolean,
    onFrameRateChanged: (Int) -> Unit,
) {
    val options = listOf(15, 30, 60)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = frameRate == option,
                onClick = { onFrameRateChanged(option) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(stringResource(Res.string.frames_per_second, option)) },
            )
        }
    }
}

@Composable
private fun TransportBar(
    state: RecorderUiState,
    onAction: (RecorderUiAction) -> Unit,
    shortcutLabels: RecorderShortcutLabels,
) {
    val displayedElapsed = if (state.isReplayActive) {
        state.replayRetainedMilliseconds
    } else {
        state.elapsedMilliseconds
    }
    val displayedVideoFrames = if (state.isReplayActive) state.replayVideoFrames.toLong() else state.videoFrames
    val displayedAudioFrames = if (state.isReplayActive) state.replayAudioFrames.toLong() else state.audioFrames
    val displayedDroppedFrames = if (state.isReplayActive) state.replayDroppedFrames else state.droppedFrames
    val displayedEffectiveFps = if (state.isReplayActive) {
        framesPerSecond(displayedVideoFrames, displayedElapsed)
    } else {
        state.effectiveFramesPerSecond
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(82.dp).background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatElapsed(displayedElapsed),
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(20.dp))
        Column {
            Text(
                text = stringResource(Res.string.video_frames, displayedVideoFrames),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.audio_frames, displayedAudioFrames),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(20.dp))
        Column {
            Text(
                text = stringResource(Res.string.effective_fps, formatFramesPerSecond(displayedEffectiveFps)),
                modifier = Modifier.testTag("effective-fps"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.dropped_frames, displayedDroppedFrames),
                modifier = Modifier.testTag("dropped-frames"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        val screenshotLabel = stringResource(
            if (state.isSavingScreenshot) Res.string.saving_screenshot else Res.string.take_screenshot,
        )
        OutlinedButton(
            onClick = { onAction(RecorderUiAction.TakeScreenshot) },
            modifier = Modifier.testTag("take-screenshot").widthIn(min = 168.dp).height(46.dp),
            enabled = state.canTakeScreenshot,
            shape = RoundedCornerShape(6.dp),
        ) {
            MaterialIcon(MaterialIcons.Capture, null)
            Spacer(Modifier.width(8.dp))
            Text(screenshotLabel)
        }
        Spacer(Modifier.width(8.dp))
        val buttonLabel = when (state.status) {
            RecorderStatus.Preparing -> stringResource(Res.string.starting)
            RecorderStatus.Stopping -> stringResource(Res.string.stopping)
            RecorderStatus.Recording,
            RecorderStatus.Paused,
                -> stringResource(Res.string.stop)

            else -> stringResource(Res.string.record)
        }
        val pauseLabel = if (state.isPaused) stringResource(Res.string.resume) else stringResource(Res.string.pause)
        RecorderTooltipIconButton(
            label = pauseLabel,
            shortcut = shortcutLabels.pause,
            enabled = state.canPauseRecording || state.canResumeRecording,
            onClick = {
                onAction(if (state.isPaused) RecorderUiAction.ResumeRecording else RecorderUiAction.PauseRecording)
            },
            modifier = Modifier.size(46.dp).testTag("pause-toggle"),
        ) {
            MaterialIcon(
                icon = if (state.isPaused) MaterialIcons.Play else MaterialIcons.Pause,
                description = pauseLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        RecorderTooltipBox(
            label = buttonLabel,
            shortcut = shortcutLabels.recording,
        ) {
            Button(
                onClick = {
                    onAction(
                        if (state.hasActiveRecording) {
                            RecorderUiAction.StopRecording
                        } else {
                            RecorderUiAction.StartRecording
                        },
                    )
                },
                modifier = Modifier.testTag("record-toggle").widthIn(min = 132.dp).height(46.dp),
                enabled = state.hasActiveRecording || state.canStart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.hasActiveRecording) RecordingRed else MaterialTheme.colorScheme.primary,
                ),
                shape = RoundedCornerShape(6.dp),
            ) {
                MaterialIcon(
                    if (state.hasActiveRecording) MaterialIcons.Stop else MaterialIcons.Record,
                    null,
                    color = Color.White
                )
                Spacer(Modifier.width(8.dp))
                Text(buttonLabel)
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            val description = stringResource(Res.string.dismiss)
            IconButton(onClick = onDismiss) {
                MaterialIcon(MaterialIcons.Close, description)
            }
        }
    }
}

@Composable
private fun StatusIndicator(status: RecorderStatus) {
    val label = statusLabel(status)
    val color = when (status) {
        RecorderStatus.Recording -> RecordingRed
        RecorderStatus.Paused -> Color(0xFFB26A00)
        RecorderStatus.Completed -> SuccessGreen
        RecorderStatus.Failed -> MaterialTheme.colorScheme.error
        RecorderStatus.Preparing,
        RecorderStatus.Stopping,
            -> Color(0xFFB26A00)

        RecorderStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

@Composable
private fun ReplayStatusIndicator(status: ReplayUiStatus) {
    val label = replayStatusLabel(status)
    val color = when (status) {
        ReplayUiStatus.Buffering -> LapisLazuli
        ReplayUiStatus.Failed -> MaterialTheme.colorScheme.error
        ReplayUiStatus.Preparing,
        ReplayUiStatus.Saving,
        ReplayUiStatus.Stopping,
            -> Color(0xFFB26A00)

        ReplayUiStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun replayStatusLabel(status: ReplayUiStatus): String =
    when (status) {
        ReplayUiStatus.Idle -> stringResource(Res.string.status_idle)
        ReplayUiStatus.Preparing -> stringResource(Res.string.replay_preparing)
        ReplayUiStatus.Buffering -> stringResource(Res.string.replay_buffering)
        ReplayUiStatus.Saving -> stringResource(Res.string.replay_saving)
        ReplayUiStatus.Stopping -> stringResource(Res.string.replay_stopping)
        ReplayUiStatus.Failed -> stringResource(Res.string.replay_failed)
    }

@Composable
private fun statusLabel(status: RecorderStatus): String =
    when (status) {
        RecorderStatus.Idle -> stringResource(Res.string.status_idle)
        RecorderStatus.Preparing -> stringResource(Res.string.status_preparing)
        RecorderStatus.Recording -> stringResource(Res.string.status_recording)
        RecorderStatus.Paused -> stringResource(Res.string.status_paused)
        RecorderStatus.Stopping -> stringResource(Res.string.status_stopping)
        RecorderStatus.Completed -> stringResource(Res.string.status_completed)
        RecorderStatus.Failed -> stringResource(Res.string.status_failed)
    }

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

private val RecorderSourceKind.icon: DrawableResource
    get() = when (this) {
        RecorderSourceKind.Screen -> MaterialIcons.Capture
        RecorderSourceKind.Monitor -> MaterialIcons.Monitor
        RecorderSourceKind.Region -> MaterialIcons.Crop
        RecorderSourceKind.Window -> MaterialIcons.Window
        RecorderSourceKind.Application -> MaterialIcons.Apps
    }

private fun formatElapsed(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return listOf(hours, minutes, seconds).joinToString(":") { it.toString().padStart(2, '0') }
}

private fun framesPerSecond(frameCount: Long, durationMilliseconds: Long): Double =
    if (durationMilliseconds > 0) frameCount * 1_000.0 / durationMilliseconds else 0.0

private fun formatFramesPerSecond(value: Double): String {
    val tenths = (value.coerceAtLeast(0.0) * 10).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}

private val ImportantFrameGold = Color(0xFFF3C64D)
