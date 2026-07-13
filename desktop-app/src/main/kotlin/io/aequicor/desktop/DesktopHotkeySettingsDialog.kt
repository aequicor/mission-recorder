package io.aequicor.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.compose.resources.Res
import io.aequicor.compose.resources.apply
import io.aequicor.compose.resources.cancel
import io.aequicor.compose.resources.global_hotkeys
import io.aequicor.compose.resources.hotkey_conflict
import io.aequicor.compose.resources.reset_defaults
import io.aequicor.compose.resources.shortcut_pause
import io.aequicor.compose.resources.shortcut_recording
import io.aequicor.compose.resources.shortcut_save_replay
import io.aequicor.compose.resources.shortcut_select_region
import io.aequicor.hotkey.GlobalHotkeyAction
import io.aequicor.hotkey.GlobalHotkeyBinding
import io.aequicor.hotkey.GlobalHotkeyGesture
import io.aequicor.hotkey.GlobalHotkeyKey
import io.aequicor.hotkey.GlobalHotkeyModifier
import io.aequicor.hotkey.defaultDesktopGlobalHotkeys
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DesktopHotkeySettingsDialog(
    bindings: List<GlobalHotkeyBinding>,
    onDismissRequest: () -> Unit,
    onApply: (List<GlobalHotkeyBinding>) -> Unit,
) {
    var draft by remember(bindings) { mutableStateOf(bindings) }
    val gesturesAreDistinct = draft.map(GlobalHotkeyBinding::gesture).distinct().size == draft.size
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Res.string.global_hotkeys)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                GlobalHotkeyAction.entries.forEach { action ->
                    val binding = requireNotNull(draft.firstOrNull { candidate -> candidate.action == action })
                    HotkeyBindingEditor(
                        label = hotkeyActionLabel(action),
                        binding = binding,
                        onBindingChange = { updated -> draft = draft.replaceBinding(updated) },
                    )
                }
                if (!gesturesAreDistinct) {
                    Text(
                        text = stringResource(Res.string.hotkey_conflict),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(draft) },
                enabled = gesturesAreDistinct,
            ) {
                Text(stringResource(Res.string.apply))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { draft = defaultDesktopGlobalHotkeys }) {
                    Text(stringResource(Res.string.reset_defaults))
                }
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        },
    )
}

@Composable
private fun HotkeyBindingEditor(
    label: String,
    binding: GlobalHotkeyBinding,
    onBindingChange: (GlobalHotkeyBinding) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            editableModifiers.forEach { modifier ->
                FilterChip(
                    selected = modifier in binding.gesture.modifiers,
                    onClick = {
                        val updatedModifiers = binding.gesture.modifiers.toMutableSet().apply {
                            if (!add(modifier)) {
                                remove(modifier)
                            }
                        }
                        onBindingChange(binding.copy(gesture = binding.gesture.copy(modifiers = updatedModifiers)))
                    },
                    label = { Text(modifier.shortLabel()) },
                )
            }
            Spacer(Modifier.weight(1f))
            HotkeyKeySelector(
                key = binding.gesture.key,
                onKeyChange = { key -> onBindingChange(binding.copy(gesture = binding.gesture.copy(key = key))) },
            )
        }
    }
}

@Composable
private fun HotkeyKeySelector(
    key: GlobalHotkeyKey,
    onKeyChange: (GlobalHotkeyKey) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(key.name)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            GlobalHotkeyKey.entries.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(candidate.name) },
                    onClick = {
                        expanded = false
                        onKeyChange(candidate)
                    },
                )
            }
        }
    }
}

@Composable
private fun hotkeyActionLabel(action: GlobalHotkeyAction): String = when (action) {
    GlobalHotkeyAction.ToggleRecording -> stringResource(Res.string.shortcut_recording)
    GlobalHotkeyAction.TogglePause -> stringResource(Res.string.shortcut_pause)
    GlobalHotkeyAction.SaveReplay -> stringResource(Res.string.shortcut_save_replay)
    GlobalHotkeyAction.SelectRegion -> stringResource(Res.string.shortcut_select_region)
}

internal fun formatGlobalHotkeyGesture(gesture: GlobalHotkeyGesture): String =
    (editableModifiers.filter(gesture.modifiers::contains).map(GlobalHotkeyModifier::shortLabel) + gesture.key.name)
        .joinToString("+")

private fun List<GlobalHotkeyBinding>.replaceBinding(updated: GlobalHotkeyBinding): List<GlobalHotkeyBinding> =
    map { binding -> if (binding.action == updated.action) updated else binding }

private fun GlobalHotkeyModifier.shortLabel(): String = when (this) {
    GlobalHotkeyModifier.Alt -> "Alt"
    GlobalHotkeyModifier.Control -> "Ctrl"
    GlobalHotkeyModifier.Shift -> "Shift"
    GlobalHotkeyModifier.Meta -> "Meta"
}

private val editableModifiers = listOf(
    GlobalHotkeyModifier.Control,
    GlobalHotkeyModifier.Alt,
    GlobalHotkeyModifier.Shift,
    GlobalHotkeyModifier.Meta,
)
