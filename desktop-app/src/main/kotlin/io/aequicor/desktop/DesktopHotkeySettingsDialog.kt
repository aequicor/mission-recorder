package io.aequicor.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.nativeKeyLocation
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.aequicor.compose.resources.Res
import io.aequicor.compose.resources.apply
import io.aequicor.compose.resources.cancel
import io.aequicor.compose.resources.change_shortcut
import io.aequicor.compose.resources.enable_global_hotkeys
import io.aequicor.compose.resources.global_hotkeys
import io.aequicor.compose.resources.hotkey_conflict
import io.aequicor.compose.resources.press_shortcut
import io.aequicor.compose.resources.reset_defaults
import io.aequicor.compose.resources.reset_shortcut
import io.aequicor.compose.resources.shortcut_pause
import io.aequicor.compose.resources.shortcut_mark_important_frame
import io.aequicor.compose.resources.shortcut_recording
import io.aequicor.compose.resources.shortcut_save_replay
import io.aequicor.compose.resources.shortcut_select_region
import io.aequicor.compose.resources.shortcut_select_region_and_start_recording
import io.aequicor.hotkey.GlobalHotkeyAction
import io.aequicor.hotkey.GlobalHotkeyBinding
import io.aequicor.hotkey.GlobalHotkeyGesture
import io.aequicor.hotkey.GlobalHotkeyKey
import io.aequicor.hotkey.GlobalHotkeyModifier
import io.aequicor.hotkey.defaultDesktopGlobalHotkeys
import java.awt.event.KeyEvent as AwtKeyEvent
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DesktopHotkeySettingsDialog(
    enabled: Boolean,
    hotkeysSupported: Boolean,
    bindings: List<GlobalHotkeyBinding>,
    onDismissRequest: () -> Unit,
    onApply: (Boolean, List<GlobalHotkeyBinding>) -> Unit,
) {
    var enabledDraft by remember(enabled) { mutableStateOf(enabled) }
    var draft by remember(bindings) { mutableStateOf(bindings) }
    var capturingAction by remember { mutableStateOf<GlobalHotkeyAction?>(null) }
    val gesturesAreDistinct = draft.map(GlobalHotkeyBinding::gesture).distinct().size == draft.size
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Res.string.global_hotkeys)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = enabledDraft,
                        onCheckedChange = { checked -> enabledDraft = checked },
                        enabled = hotkeysSupported,
                    )
                    Text(stringResource(Res.string.enable_global_hotkeys))
                }
                GlobalHotkeyAction.entries.forEach { action ->
                    val binding = requireNotNull(draft.firstOrNull { candidate -> candidate.action == action })
                    val defaultBinding = requireNotNull(
                        defaultDesktopGlobalHotkeys.firstOrNull { candidate -> candidate.action == action },
                    )
                    HotkeyBindingEditor(
                        label = hotkeyActionLabel(action),
                        binding = binding,
                        canReset = binding != defaultBinding,
                        isCapturing = capturingAction == action,
                        onStartCapturing = { capturingAction = action },
                        onGestureCaptured = { gesture ->
                            draft = draft.replaceBinding(binding.copy(gesture = gesture))
                            capturingAction = null
                        },
                        onReset = {
                            draft = draft.replaceBinding(defaultBinding)
                            if (capturingAction == action) {
                                capturingAction = null
                            }
                        },
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
                onClick = { onApply(enabledDraft, draft) },
                enabled = gesturesAreDistinct,
            ) {
                Text(stringResource(Res.string.apply))
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        draft = defaultDesktopGlobalHotkeys
                        capturingAction = null
                    },
                ) {
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
    canReset: Boolean,
    isCapturing: Boolean,
    onStartCapturing: () -> Unit,
    onGestureCaptured: (GlobalHotkeyGesture) -> Unit,
    onReset: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isCapturing) {
        if (isCapturing) {
            focusRequester.requestFocus()
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = if (isCapturing) {
                    stringResource(Res.string.press_shortcut)
                } else {
                    formatGlobalHotkeyGesture(binding.gesture)
                },
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (!isCapturing) {
                            false
                        } else {
                            if (event.type == KeyEventType.KeyDown) {
                                event.toGlobalHotkeyGesture()?.let(onGestureCaptured)
                            }
                            true
                        }
                    }
                    .testTag("hotkey-${binding.action.name}"),
            )
            OutlinedButton(onClick = onStartCapturing) {
                Text(stringResource(Res.string.change_shortcut))
            }
            OutlinedButton(
                onClick = onReset,
                enabled = canReset,
            ) {
                Text(stringResource(Res.string.reset_shortcut))
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
    GlobalHotkeyAction.SelectRegionAndStartRecording ->
        stringResource(Res.string.shortcut_select_region_and_start_recording)
    GlobalHotkeyAction.MarkImportantFrame -> stringResource(Res.string.shortcut_mark_important_frame)
}

internal fun formatGlobalHotkeyGesture(gesture: GlobalHotkeyGesture): String =
    (
        editableModifiers.filter(gesture.modifiers::contains).map(GlobalHotkeyModifier::shortLabel) +
            gesture.key.displayName
    )
        .joinToString("+")

private fun ComposeKeyEvent.toGlobalHotkeyGesture(): GlobalHotkeyGesture? {
    val hotkeyKey = desktopGlobalHotkeyKey(
        nativeKeyCode = key.nativeKeyCode,
        nativeKeyLocation = key.nativeKeyLocation,
    ) ?: return null
    return GlobalHotkeyGesture(
        modifiers = buildSet {
            if (isCtrlPressed) add(GlobalHotkeyModifier.Control)
            if (isAltPressed) add(GlobalHotkeyModifier.Alt)
            if (isShiftPressed) add(GlobalHotkeyModifier.Shift)
            if (isMetaPressed) add(GlobalHotkeyModifier.Meta)
        },
        key = hotkeyKey,
    )
}

internal fun desktopGlobalHotkeyKey(
    nativeKeyCode: Int,
    nativeKeyLocation: Int,
): GlobalHotkeyKey? =
    if (nativeKeyLocation == AwtKeyEvent.KEY_LOCATION_NUMPAD) {
        numpadHotkeyKeys[nativeKeyCode] ?: standardHotkeyKeys[nativeKeyCode]
    } else {
        standardHotkeyKeys[nativeKeyCode]
    }

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

private val standardHotkeyKeys = mapOf(
    AwtKeyEvent.VK_A to GlobalHotkeyKey.A,
    AwtKeyEvent.VK_B to GlobalHotkeyKey.B,
    AwtKeyEvent.VK_C to GlobalHotkeyKey.C,
    AwtKeyEvent.VK_D to GlobalHotkeyKey.D,
    AwtKeyEvent.VK_E to GlobalHotkeyKey.E,
    AwtKeyEvent.VK_F to GlobalHotkeyKey.F,
    AwtKeyEvent.VK_G to GlobalHotkeyKey.G,
    AwtKeyEvent.VK_H to GlobalHotkeyKey.H,
    AwtKeyEvent.VK_I to GlobalHotkeyKey.I,
    AwtKeyEvent.VK_J to GlobalHotkeyKey.J,
    AwtKeyEvent.VK_K to GlobalHotkeyKey.K,
    AwtKeyEvent.VK_L to GlobalHotkeyKey.L,
    AwtKeyEvent.VK_M to GlobalHotkeyKey.M,
    AwtKeyEvent.VK_N to GlobalHotkeyKey.N,
    AwtKeyEvent.VK_O to GlobalHotkeyKey.O,
    AwtKeyEvent.VK_P to GlobalHotkeyKey.P,
    AwtKeyEvent.VK_Q to GlobalHotkeyKey.Q,
    AwtKeyEvent.VK_R to GlobalHotkeyKey.R,
    AwtKeyEvent.VK_S to GlobalHotkeyKey.S,
    AwtKeyEvent.VK_T to GlobalHotkeyKey.T,
    AwtKeyEvent.VK_U to GlobalHotkeyKey.U,
    AwtKeyEvent.VK_V to GlobalHotkeyKey.V,
    AwtKeyEvent.VK_W to GlobalHotkeyKey.W,
    AwtKeyEvent.VK_X to GlobalHotkeyKey.X,
    AwtKeyEvent.VK_Y to GlobalHotkeyKey.Y,
    AwtKeyEvent.VK_Z to GlobalHotkeyKey.Z,
    AwtKeyEvent.VK_0 to GlobalHotkeyKey.Digit0,
    AwtKeyEvent.VK_1 to GlobalHotkeyKey.Digit1,
    AwtKeyEvent.VK_2 to GlobalHotkeyKey.Digit2,
    AwtKeyEvent.VK_3 to GlobalHotkeyKey.Digit3,
    AwtKeyEvent.VK_4 to GlobalHotkeyKey.Digit4,
    AwtKeyEvent.VK_5 to GlobalHotkeyKey.Digit5,
    AwtKeyEvent.VK_6 to GlobalHotkeyKey.Digit6,
    AwtKeyEvent.VK_7 to GlobalHotkeyKey.Digit7,
    AwtKeyEvent.VK_8 to GlobalHotkeyKey.Digit8,
    AwtKeyEvent.VK_9 to GlobalHotkeyKey.Digit9,
    AwtKeyEvent.VK_F1 to GlobalHotkeyKey.F1,
    AwtKeyEvent.VK_F2 to GlobalHotkeyKey.F2,
    AwtKeyEvent.VK_F3 to GlobalHotkeyKey.F3,
    AwtKeyEvent.VK_F4 to GlobalHotkeyKey.F4,
    AwtKeyEvent.VK_F5 to GlobalHotkeyKey.F5,
    AwtKeyEvent.VK_F6 to GlobalHotkeyKey.F6,
    AwtKeyEvent.VK_F7 to GlobalHotkeyKey.F7,
    AwtKeyEvent.VK_F8 to GlobalHotkeyKey.F8,
    AwtKeyEvent.VK_F9 to GlobalHotkeyKey.F9,
    AwtKeyEvent.VK_F10 to GlobalHotkeyKey.F10,
    AwtKeyEvent.VK_F11 to GlobalHotkeyKey.F11,
    AwtKeyEvent.VK_F12 to GlobalHotkeyKey.F12,
    AwtKeyEvent.VK_SPACE to GlobalHotkeyKey.Space,
    AwtKeyEvent.VK_TAB to GlobalHotkeyKey.Tab,
    AwtKeyEvent.VK_ENTER to GlobalHotkeyKey.Enter,
    AwtKeyEvent.VK_ESCAPE to GlobalHotkeyKey.Escape,
    AwtKeyEvent.VK_BACK_SPACE to GlobalHotkeyKey.Backspace,
    AwtKeyEvent.VK_INSERT to GlobalHotkeyKey.Insert,
    AwtKeyEvent.VK_DELETE to GlobalHotkeyKey.Delete,
    AwtKeyEvent.VK_HOME to GlobalHotkeyKey.Home,
    AwtKeyEvent.VK_END to GlobalHotkeyKey.End,
    AwtKeyEvent.VK_PAGE_UP to GlobalHotkeyKey.PageUp,
    AwtKeyEvent.VK_PAGE_DOWN to GlobalHotkeyKey.PageDown,
    AwtKeyEvent.VK_UP to GlobalHotkeyKey.ArrowUp,
    AwtKeyEvent.VK_DOWN to GlobalHotkeyKey.ArrowDown,
    AwtKeyEvent.VK_LEFT to GlobalHotkeyKey.ArrowLeft,
    AwtKeyEvent.VK_RIGHT to GlobalHotkeyKey.ArrowRight,
    AwtKeyEvent.VK_MINUS to GlobalHotkeyKey.Minus,
    AwtKeyEvent.VK_EQUALS to GlobalHotkeyKey.Equal,
    AwtKeyEvent.VK_OPEN_BRACKET to GlobalHotkeyKey.LeftBracket,
    AwtKeyEvent.VK_CLOSE_BRACKET to GlobalHotkeyKey.RightBracket,
    AwtKeyEvent.VK_BACK_SLASH to GlobalHotkeyKey.Backslash,
    AwtKeyEvent.VK_SEMICOLON to GlobalHotkeyKey.Semicolon,
    AwtKeyEvent.VK_QUOTE to GlobalHotkeyKey.Apostrophe,
    AwtKeyEvent.VK_COMMA to GlobalHotkeyKey.Comma,
    AwtKeyEvent.VK_PERIOD to GlobalHotkeyKey.Period,
    AwtKeyEvent.VK_SLASH to GlobalHotkeyKey.Slash,
    AwtKeyEvent.VK_BACK_QUOTE to GlobalHotkeyKey.Grave,
)

private val numpadHotkeyKeys = mapOf(
    AwtKeyEvent.VK_NUMPAD0 to GlobalHotkeyKey.Numpad0,
    AwtKeyEvent.VK_NUMPAD1 to GlobalHotkeyKey.Numpad1,
    AwtKeyEvent.VK_NUMPAD2 to GlobalHotkeyKey.Numpad2,
    AwtKeyEvent.VK_NUMPAD3 to GlobalHotkeyKey.Numpad3,
    AwtKeyEvent.VK_NUMPAD4 to GlobalHotkeyKey.Numpad4,
    AwtKeyEvent.VK_NUMPAD5 to GlobalHotkeyKey.Numpad5,
    AwtKeyEvent.VK_NUMPAD6 to GlobalHotkeyKey.Numpad6,
    AwtKeyEvent.VK_NUMPAD7 to GlobalHotkeyKey.Numpad7,
    AwtKeyEvent.VK_NUMPAD8 to GlobalHotkeyKey.Numpad8,
    AwtKeyEvent.VK_NUMPAD9 to GlobalHotkeyKey.Numpad9,
    AwtKeyEvent.VK_ADD to GlobalHotkeyKey.NumpadAdd,
    AwtKeyEvent.VK_SUBTRACT to GlobalHotkeyKey.NumpadSubtract,
    AwtKeyEvent.VK_MULTIPLY to GlobalHotkeyKey.NumpadMultiply,
    AwtKeyEvent.VK_DIVIDE to GlobalHotkeyKey.NumpadDivide,
    AwtKeyEvent.VK_DECIMAL to GlobalHotkeyKey.NumpadDecimal,
    AwtKeyEvent.VK_SEPARATOR to GlobalHotkeyKey.NumpadDecimal,
    AwtKeyEvent.VK_PERIOD to GlobalHotkeyKey.NumpadDecimal,
    AwtKeyEvent.VK_ENTER to GlobalHotkeyKey.NumpadEnter,
)
