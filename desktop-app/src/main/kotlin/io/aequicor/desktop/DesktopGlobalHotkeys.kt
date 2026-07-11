package io.aequicor.desktop

import io.aequicor.compose.ui.RecorderUiAction
import io.aequicor.compose.ui.RecorderUiState
import io.aequicor.hotkey.GlobalHotkeyAction

internal fun routeGlobalHotkey(
    action: GlobalHotkeyAction,
    state: RecorderUiState,
    onAction: (RecorderUiAction) -> Unit,
) {
    val uiAction = when (action) {
        GlobalHotkeyAction.ToggleRecording -> when {
            state.hasActiveRecording -> RecorderUiAction.StopRecording
            state.canStart -> RecorderUiAction.StartRecording
            else -> null
        }
        GlobalHotkeyAction.TogglePause -> when {
            state.canPauseRecording -> RecorderUiAction.PauseRecording
            state.canResumeRecording -> RecorderUiAction.ResumeRecording
            else -> null
        }
        GlobalHotkeyAction.SaveReplay -> RecorderUiAction.SaveReplayBuffer.takeIf { state.canSaveReplay }
    }
    uiAction?.let(onAction)
}
