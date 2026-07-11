package io.aequicor.desktop

import io.aequicor.compose.ui.RecorderSourceKind
import io.aequicor.compose.ui.RecorderSourceUi
import io.aequicor.compose.ui.RecorderStatus
import io.aequicor.compose.ui.RecorderUiAction
import io.aequicor.compose.ui.RecorderUiState
import io.aequicor.compose.ui.ReplayUiStatus
import io.aequicor.hotkey.GlobalHotkeyAction
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopGlobalHotkeysTest {
    @Test
    fun routesActionsOnlyWhenTheirCurrentStateAllowsThem() {
        val actions = mutableListOf<RecorderUiAction>()
        val ready = RecorderUiState(
            sources = listOf(RecorderSourceUi("screen:test", "Test screen", RecorderSourceKind.Screen)),
            selectedSourceId = "screen:test",
            outputPath = "recordings/test.mp4",
        )

        routeGlobalHotkey(GlobalHotkeyAction.ToggleRecording, ready, actions::add)
        routeGlobalHotkey(
            GlobalHotkeyAction.TogglePause,
            ready.copy(status = RecorderStatus.Recording),
            actions::add,
        )
        routeGlobalHotkey(
            GlobalHotkeyAction.TogglePause,
            ready.copy(status = RecorderStatus.Paused),
            actions::add,
        )
        routeGlobalHotkey(
            GlobalHotkeyAction.ToggleRecording,
            ready.copy(status = RecorderStatus.Paused),
            actions::add,
        )
        routeGlobalHotkey(
            GlobalHotkeyAction.SaveReplay,
            ready.copy(replayStatus = ReplayUiStatus.Buffering, replayVideoFrames = 30),
            actions::add,
        )

        assertEquals(
            listOf(
                RecorderUiAction.StartRecording,
                RecorderUiAction.PauseRecording,
                RecorderUiAction.ResumeRecording,
                RecorderUiAction.StopRecording,
                RecorderUiAction.SaveReplayBuffer,
            ),
            actions,
        )
    }

    @Test
    fun ignoresHotkeysDuringUnavailableTransitions() {
        val actions = mutableListOf<RecorderUiAction>()
        val state = RecorderUiState(status = RecorderStatus.Preparing)

        GlobalHotkeyAction.entries.forEach { action ->
            routeGlobalHotkey(action, state, actions::add)
        }

        assertEquals(emptyList(), actions)
    }
}
