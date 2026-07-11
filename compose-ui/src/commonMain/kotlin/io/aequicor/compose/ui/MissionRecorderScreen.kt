package io.aequicor.compose.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import io.aequicor.compose.resources.choose_output_file
import io.aequicor.compose.resources.create_storyboard
import io.aequicor.compose.resources.creating_storyboard
import io.aequicor.compose.resources.dismiss
import io.aequicor.compose.resources.dropped_frames
import io.aequicor.compose.resources.effective_fps
import io.aequicor.compose.resources.frame_rate
import io.aequicor.compose.resources.frames_per_second
import io.aequicor.compose.resources.file_name_pattern
import io.aequicor.compose.resources.file_name_pattern_hint
import io.aequicor.compose.resources.material_symbols_rounded
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
import io.aequicor.compose.resources.output_path
import io.aequicor.compose.resources.output_device
import io.aequicor.compose.resources.overwrite_output
import io.aequicor.compose.resources.pause
import io.aequicor.compose.resources.profile
import io.aequicor.compose.resources.create_profile
import io.aequicor.compose.resources.delete_profile
import io.aequicor.compose.resources.delete_profile_confirmation
import io.aequicor.compose.resources.profile_name
import io.aequicor.compose.resources.cancel
import io.aequicor.compose.resources.record
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
import io.aequicor.compose.resources.selected_source
import io.aequicor.compose.resources.start_preview
import io.aequicor.compose.resources.stop_preview
import io.aequicor.compose.resources.preview_preparing
import io.aequicor.compose.resources.preview_image_description
import io.aequicor.compose.resources.select_area
import io.aequicor.compose.resources.selecting_area
import io.aequicor.compose.resources.solo_microphone
import io.aequicor.compose.resources.solo_system_audio
import io.aequicor.compose.resources.starting
import io.aequicor.compose.resources.start_replay_buffer
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
import io.aequicor.compose.resources.storyboard_saved_to
import io.aequicor.compose.resources.storyboard_separate_png
import io.aequicor.compose.resources.system_audio
import io.aequicor.compose.resources.system_audio_gain
import io.aequicor.compose.resources.unavailable
import io.aequicor.compose.resources.unmute_microphone
import io.aequicor.compose.resources.unmute_system_audio
import io.aequicor.compose.resources.unsolo_microphone
import io.aequicor.compose.resources.unsolo_system_audio
import io.aequicor.compose.resources.video
import io.aequicor.compose.resources.video_bitrate
import io.aequicor.compose.resources.video_frames
import org.jetbrains.compose.resources.Font
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
fun MissionRecorderScreen(
    state: RecorderUiState,
    onAction: (RecorderUiAction) -> Unit,
    previewImage: ImageBitmap? = null,
    modifier: Modifier = Modifier,
) {
    MissionRecorderTheme {
        if (state.showCreateProfileDialog) {
            CreateProfileDialog(state = state, onAction = onAction)
        }
        if (state.showDeleteProfileDialog) {
            DeleteProfileDialog(state = state, onAction = onAction)
        }
        if (state.showOutputNamingDialog) {
            OutputNamingDialog(state = state, onAction = onAction)
        }
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                AppHeader(state = state, onAction = onAction)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (maxWidth < 860.dp) {
                        CompactWorkspace(state = state, onAction = onAction, previewImage = previewImage)
                    } else {
                        WideWorkspace(state = state, onAction = onAction, previewImage = previewImage)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TransportBar(state = state, onAction = onAction)
            }
        }
    }
}

@Composable
fun MiniRecorderController(
    state: RecorderUiState,
    onAction: (RecorderUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    MissionRecorderTheme {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val selectedSource = state.sources.firstOrNull { source -> source.id == state.selectedSourceId }
                Column(modifier = Modifier.weight(1f)) {
                    if (state.isReplayActive || state.replayStatus == ReplayUiStatus.Failed) {
                        ReplayStatusIndicator(state.replayStatus)
                    } else {
                        StatusIndicator(state.status)
                    }
                    Text(
                        text = selectedSource?.displayName ?: stringResource(Res.string.no_capture_sources),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = formatElapsed(
                        if (state.isReplayActive) state.replayRetainedMilliseconds else state.elapsedMilliseconds,
                    ),
                    modifier = Modifier.width(72.dp).testTag("mini-elapsed"),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
                AudioMuteButton(
                    muted = state.microphoneMuted,
                    enabled = state.canToggleMicrophoneMute,
                    muteLabel = stringResource(Res.string.mute_microphone),
                    unmuteLabel = stringResource(Res.string.unmute_microphone),
                    mutedSymbol = Symbols.MicOff,
                    unmutedSymbol = Symbols.Mic,
                    testTag = "mini-microphone-mute",
                    onMutedChange = { muted -> onAction(RecorderUiAction.SetMicrophoneMuted(muted)) },
                )
                AudioMuteButton(
                    muted = state.systemAudioMuted,
                    enabled = state.canToggleSystemAudioMute,
                    muteLabel = stringResource(Res.string.mute_system_audio),
                    unmuteLabel = stringResource(Res.string.unmute_system_audio),
                    mutedSymbol = Symbols.VolumeOff,
                    unmutedSymbol = Symbols.VolumeUp,
                    testTag = "mini-system-audio-mute",
                    onMutedChange = { muted -> onAction(RecorderUiAction.SetSystemAudioMuted(muted)) },
                )
                MiniPauseButton(state = state, onAction = onAction)
                MiniRecordButton(state = state, onAction = onAction)
            }
        }
    }
}

@Composable
private fun AppHeader(state: RecorderUiState, onAction: (RecorderUiAction) -> Unit) {
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
        ProfileSelector(state = state, onAction = onAction)
        Spacer(Modifier.width(12.dp))
        if (state.isReplayActive || state.replayStatus == ReplayUiStatus.Failed) {
            ReplayStatusIndicator(state.replayStatus)
        } else {
            StatusIndicator(state.status)
        }
        Spacer(Modifier.width(8.dp))
        val refreshDescription = stringResource(Res.string.refresh_sources)
        IconButton(
            onClick = { onAction(RecorderUiAction.RefreshSources) },
            enabled = !state.isBusy && !state.isRefreshingSources,
        ) {
            MaterialSymbol(Symbols.Refresh, refreshDescription)
        }
    }
}

@Composable
private fun ProfileSelector(state: RecorderUiState, onAction: (RecorderUiAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.profiles.firstOrNull { profile -> profile.id == state.selectedProfileId }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = state.canManageProfiles,
            modifier = Modifier.widthIn(min = 160.dp, max = 220.dp).testTag("profile-selector"),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = selected?.name ?: stringResource(Res.string.profile),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            MaterialSymbol(Symbols.ExpandMore, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onAction(RecorderUiAction.SelectProfile(profile.id))
                    },
                )
            }
        }
    }
    val createLabel = stringResource(Res.string.create_profile)
    RecorderTooltipIconButton(
        label = createLabel,
        enabled = state.canManageProfiles,
        onClick = { onAction(RecorderUiAction.ShowCreateProfileDialog) },
        modifier = Modifier.testTag("create-profile"),
    ) {
        MaterialSymbol(Symbols.Add, createLabel)
    }
    val deleteLabel = stringResource(Res.string.delete_profile)
    RecorderTooltipIconButton(
        label = deleteLabel,
        enabled = state.canDeleteProfile,
        onClick = { onAction(RecorderUiAction.ShowDeleteProfileDialog) },
        modifier = Modifier.testTag("delete-profile"),
    ) {
        MaterialSymbol(Symbols.Delete, deleteLabel)
    }
}

@Composable
private fun DeleteProfileDialog(state: RecorderUiState, onAction: (RecorderUiAction) -> Unit) {
    val selectedName = state.profiles.firstOrNull { it.id == state.selectedProfileId }?.name.orEmpty()
    AlertDialog(
        onDismissRequest = { onAction(RecorderUiAction.DismissDeleteProfileDialog) },
        title = { Text(stringResource(Res.string.delete_profile)) },
        text = { Text(stringResource(Res.string.delete_profile_confirmation, selectedName)) },
        confirmButton = {
            TextButton(
                onClick = { onAction(RecorderUiAction.DeleteSelectedProfile) },
                modifier = Modifier.testTag("confirm-delete-profile"),
            ) {
                Text(stringResource(Res.string.delete_profile), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(RecorderUiAction.DismissDeleteProfileDialog) }) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}

@Composable
private fun CreateProfileDialog(state: RecorderUiState, onAction: (RecorderUiAction) -> Unit) {
    var name by remember(state.showCreateProfileDialog) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onAction(RecorderUiAction.DismissCreateProfileDialog) },
        title = { Text(stringResource(Res.string.create_profile)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.profile_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("profile-name"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onAction(RecorderUiAction.CreateProfile(name.trim())) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("confirm-create-profile"),
            ) {
                Text(stringResource(Res.string.create_profile))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(RecorderUiAction.DismissCreateProfileDialog) }) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
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
    previewImage: ImageBitmap?,
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
    previewImage: ImageBitmap?,
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
        SectionTitle(stringResource(Res.string.capture_source))
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
            MaterialSymbol(symbol = Symbols.Crop, description = null, size = 20.sp)
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
        MaterialSymbol(
            symbol = source.kind.symbol,
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
    previewImage: ImageBitmap?,
    modifier: Modifier,
) {
    Column(modifier = modifier.padding(20.dp)) {
        val selected = state.sources.firstOrNull { it.id == state.selectedSourceId }
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
                if (previewImage != null) {
                    Image(
                        bitmap = previewImage,
                        contentDescription = stringResource(Res.string.preview_image_description),
                        modifier = Modifier.fillMaxSize().testTag("preview-image"),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MaterialSymbol(
                            symbol = selected?.kind?.symbol ?: Symbols.Capture,
                            description = null,
                            color = Color(0xFF90BCE5),
                            size = 54.sp,
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
                OutlinedButton(
                    onClick = {
                        onAction(
                            if (state.canStopPreview) RecorderUiAction.StopPreview else RecorderUiAction.StartPreview,
                        )
                    },
                    enabled = state.canStopPreview || state.canStartPreview,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(14.dp).testTag("toggle-preview"),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xDD171A1D),
                        contentColor = Color.White,
                    ),
                ) {
                    MaterialSymbol(
                        symbol = if (state.canStopPreview) Symbols.Stop else Symbols.Play,
                        description = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.canStopPreview) {
                            stringResource(Res.string.stop_preview)
                        } else {
                            stringResource(Res.string.start_preview)
                        },
                    )
                }
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
            Text(
                text = stringResource(Res.string.saved_to, outputPath),
                style = MaterialTheme.typography.bodySmall,
                color = SuccessGreen,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
                mutedSymbol = Symbols.VolumeOff,
                unmutedSymbol = Symbols.VolumeUp,
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
                    MaterialSymbol(Symbols.Folder, description)
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { onAction(RecorderUiAction.ShowOutputNamingDialog) },
            modifier = Modifier.fillMaxWidth().testTag("output-naming"),
            enabled = !state.isBusy && !state.isRefreshingSources,
        ) {
            MaterialSymbol(Symbols.Edit, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.output_naming))
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { onAction(RecorderUiAction.OpenRecordingsFolder) },
            modifier = Modifier.fillMaxWidth().testTag("open-recordings-folder"),
            enabled = state.outputPath.isNotBlank(),
        ) {
            MaterialSymbol(Symbols.FolderOpen, null)
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
        StoryboardControls(state = state, onAction = onAction)
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
                MaterialSymbol(Symbols.Stop, stopDescription)
            }
            Button(
                onClick = { onAction(RecorderUiAction.SaveReplayBuffer) },
                modifier = Modifier.weight(1f).height(46.dp).testTag("save-replay"),
                enabled = state.canSaveReplay,
                shape = RoundedCornerShape(6.dp),
            ) {
                MaterialSymbol(Symbols.Capture, null, color = Color.White)
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
            MaterialSymbol(Symbols.Record, null, color = Color.White)
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
    mutedSymbol: String,
    unmutedSymbol: String,
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
        MaterialSymbol(
            symbol = if (muted) mutedSymbol else unmutedSymbol,
            description = actionLabel,
            color = iconColor,
            size = 20.sp,
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
private fun MiniRecordButton(state: RecorderUiState, onAction: (RecorderUiAction) -> Unit) {
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
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        state.hasActiveRecording -> RecordingRed
        else -> MaterialTheme.colorScheme.primary
    }
    RecorderTooltipIconButton(
        label = label,
        enabled = enabled,
        onClick = {
            onAction(
                if (state.hasActiveRecording) RecorderUiAction.StopRecording else RecorderUiAction.StartRecording,
            )
        },
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .testTag("mini-record-toggle"),
    ) {
        MaterialSymbol(
            symbol = if (state.hasActiveRecording) Symbols.Stop else Symbols.Record,
            description = label,
            color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            size = 22.sp,
        )
    }
}

@Composable
private fun MiniPauseButton(state: RecorderUiState, onAction: (RecorderUiAction) -> Unit) {
    val label = if (state.isPaused) stringResource(Res.string.resume) else stringResource(Res.string.pause)
    val enabled = state.canPauseRecording || state.canResumeRecording
    RecorderTooltipIconButton(
        label = label,
        enabled = enabled,
        onClick = {
            onAction(if (state.isPaused) RecorderUiAction.ResumeRecording else RecorderUiAction.PauseRecording)
        },
        modifier = Modifier.size(40.dp).testTag("mini-pause-toggle"),
    ) {
        MaterialSymbol(
            symbol = if (state.isPaused) Symbols.Resume else Symbols.Pause,
            description = label,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            size = 20.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecorderTooltipIconButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
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
private fun StoryboardControls(state: RecorderUiState, onAction: (RecorderUiAction) -> Unit) {
    SectionTitle(stringResource(Res.string.storyboard))
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
        MaterialSymbol(Symbols.Capture, null, color = Color.White)
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
                MaterialSymbol(
                    if (selected == null || state.microphoneMuted) Symbols.MicOff else Symbols.Mic,
                    null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = selected?.displayName ?: stringResource(Res.string.microphone_off),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MaterialSymbol(Symbols.ExpandMore, null)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 260.dp, max = 420.dp),
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.microphone_off)) },
                    leadingIcon = { MaterialSymbol(Symbols.MicOff, null) },
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
                        leadingIcon = { MaterialSymbol(Symbols.Mic, null) },
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
            mutedSymbol = Symbols.MicOff,
            unmutedSymbol = Symbols.Mic,
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
            MaterialSymbol(Symbols.VolumeUp, null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = selected?.displayName ?: stringResource(Res.string.unavailable),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MaterialSymbol(Symbols.ExpandMore, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.systemAudioSources.forEach { source ->
                DropdownMenuItem(
                    text = { Text(source.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onAction(RecorderUiAction.SelectSystemAudio(source.id))
                    },
                    leadingIcon = { MaterialSymbol(Symbols.VolumeUp, null) },
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
private fun TransportBar(state: RecorderUiState, onAction: (RecorderUiAction) -> Unit) {
    val displayedElapsed = if (state.isReplayActive) {
        state.replayRetainedMilliseconds
    } else {
        state.elapsedMilliseconds
    }
    val displayedVideoFrames = if (state.isReplayActive) state.replayVideoFrames.toLong() else state.videoFrames
    val displayedAudioFrames = if (state.isReplayActive) state.replayAudioFrames.toLong() else state.audioFrames
    val displayedDroppedFrames = if (state.isReplayActive) 0 else state.droppedFrames
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
            enabled = state.canPauseRecording || state.canResumeRecording,
            onClick = {
                onAction(if (state.isPaused) RecorderUiAction.ResumeRecording else RecorderUiAction.PauseRecording)
            },
            modifier = Modifier.size(46.dp).testTag("pause-toggle"),
        ) {
            MaterialSymbol(
                symbol = if (state.isPaused) Symbols.Resume else Symbols.Pause,
                description = pauseLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                onAction(
                    if (state.hasActiveRecording) RecorderUiAction.StopRecording else RecorderUiAction.StartRecording,
                )
            },
            modifier = Modifier.testTag("record-toggle").widthIn(min = 132.dp).height(46.dp),
            enabled = state.hasActiveRecording || state.canStart,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.hasActiveRecording) RecordingRed else MaterialTheme.colorScheme.primary,
            ),
            shape = RoundedCornerShape(6.dp),
        ) {
            MaterialSymbol(if (state.hasActiveRecording) Symbols.Stop else Symbols.Record, null, color = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(buttonLabel)
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
                MaterialSymbol(Symbols.Close, description)
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

@Composable
private fun MaterialSymbol(
    symbol: String,
    description: String?,
    color: Color = MaterialTheme.colorScheme.onSurface,
    size: androidx.compose.ui.unit.TextUnit = 22.sp,
) {
    val symbolsFont = FontFamily(Font(Res.font.material_symbols_rounded))
    Text(
        text = symbol,
        color = color,
        fontFamily = symbolsFont,
        fontSize = size,
        lineHeight = size,
        modifier = if (description == null) {
            Modifier.clearAndSetSemantics { }
        } else {
            Modifier.clearAndSetSemantics { contentDescription = description }
        },
    )
}

private val RecorderSourceKind.symbol: String
    get() = when (this) {
        RecorderSourceKind.Screen -> Symbols.Capture
        RecorderSourceKind.Monitor -> Symbols.Monitor
        RecorderSourceKind.Region -> Symbols.Crop
        RecorderSourceKind.Window -> Symbols.Window
        RecorderSourceKind.Application -> Symbols.Application
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

private object Symbols {
    const val Add = "\uE145"
    const val Application = "\uE5C3"
    const val Capture = "\uF727"
    const val Close = "\uE5CD"
    const val Crop = "\uE3C2"
    const val Delete = "\uE872"
    const val Edit = "\uE3C9"
    const val ExpandMore = "\uE5CF"
    const val Folder = "\uE2C8"
    const val FolderOpen = "\uE2C7"
    const val Mic = "\uE31D"
    const val MicOff = "\uE02B"
    const val Monitor = "\uE30C"
    const val Pause = "\uE034"
    const val Play = "\uE037"
    const val Record = "\uE061"
    const val Refresh = "\uE5D5"
    const val Resume = "\uE037"
    const val Stop = "\uE047"
    const val VolumeOff = "\uE04F"
    const val VolumeUp = "\uE050"
    const val Window = "\uF088"
}
