package io.aequicor.hotkey

import kotlinx.coroutines.flow.Flow

enum class GlobalHotkeyAction {
    ToggleRecording,
    TogglePause,
    SaveReplay,
}

enum class GlobalHotkeyModifier {
    Alt,
    Control,
    Shift,
    Meta,
}

enum class GlobalHotkeyKey {
    F9,
    F10,
    F11,
}

data class GlobalHotkeyGesture(
    val modifiers: Set<GlobalHotkeyModifier>,
    val key: GlobalHotkeyKey,
)

data class GlobalHotkeyBinding(
    val action: GlobalHotkeyAction,
    val gesture: GlobalHotkeyGesture,
)

sealed interface GlobalHotkeyEvent {
    data class Triggered(val action: GlobalHotkeyAction) : GlobalHotkeyEvent
    data class Failed(val message: String) : GlobalHotkeyEvent
}

interface GlobalHotkeyService {
    val events: Flow<GlobalHotkeyEvent>
    val bindings: List<GlobalHotkeyBinding>

    fun close()
}

interface GlobalHotkeyServiceFactory {
    val isSupported: Boolean

    fun create(
        bindings: List<GlobalHotkeyBinding> = defaultDesktopGlobalHotkeys,
    ): GlobalHotkeyService
}

val defaultDesktopGlobalHotkeys: List<GlobalHotkeyBinding> = listOf(
    GlobalHotkeyBinding(
        action = GlobalHotkeyAction.ToggleRecording,
        gesture = GlobalHotkeyGesture(
            modifiers = setOf(GlobalHotkeyModifier.Control, GlobalHotkeyModifier.Shift),
            key = GlobalHotkeyKey.F9,
        ),
    ),
    GlobalHotkeyBinding(
        action = GlobalHotkeyAction.TogglePause,
        gesture = GlobalHotkeyGesture(
            modifiers = setOf(GlobalHotkeyModifier.Control, GlobalHotkeyModifier.Shift),
            key = GlobalHotkeyKey.F10,
        ),
    ),
    GlobalHotkeyBinding(
        action = GlobalHotkeyAction.SaveReplay,
        gesture = GlobalHotkeyGesture(
            modifiers = setOf(GlobalHotkeyModifier.Control, GlobalHotkeyModifier.Shift),
            key = GlobalHotkeyKey.F11,
        ),
    ),
)
