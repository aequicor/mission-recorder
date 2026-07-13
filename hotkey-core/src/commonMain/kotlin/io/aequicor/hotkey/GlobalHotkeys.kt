package io.aequicor.hotkey

import kotlinx.coroutines.flow.Flow

enum class GlobalHotkeyAction {
    ToggleRecording,
    TogglePause,
    SaveReplay,
    SelectRegionAndStartRecording,
    SelectRegion,
    MarkImportantFrame,
}

enum class GlobalHotkeyModifier {
    Alt,
    Control,
    Shift,
    Meta,
}

enum class GlobalHotkeyKey(val displayName: String) {
    A("A"),
    B("B"),
    C("C"),
    D("D"),
    E("E"),
    F("F"),
    G("G"),
    H("H"),
    I("I"),
    J("J"),
    K("K"),
    L("L"),
    M("M"),
    N("N"),
    O("O"),
    P("P"),
    Q("Q"),
    R("R"),
    S("S"),
    T("T"),
    U("U"),
    V("V"),
    W("W"),
    X("X"),
    Y("Y"),
    Z("Z"),
    Digit0("0"),
    Digit1("1"),
    Digit2("2"),
    Digit3("3"),
    Digit4("4"),
    Digit5("5"),
    Digit6("6"),
    Digit7("7"),
    Digit8("8"),
    Digit9("9"),
    F1("F1"),
    F2("F2"),
    F3("F3"),
    F4("F4"),
    F5("F5"),
    F6("F6"),
    F7("F7"),
    F8("F8"),
    F9("F9"),
    F10("F10"),
    F11("F11"),
    F12("F12"),
    Space("Space"),
    Tab("Tab"),
    Enter("Enter"),
    Escape("Esc"),
    Backspace("Backspace"),
    Insert("Insert"),
    Delete("Delete"),
    Home("Home"),
    End("End"),
    PageUp("Page Up"),
    PageDown("Page Down"),
    ArrowUp("Up"),
    ArrowDown("Down"),
    ArrowLeft("Left"),
    ArrowRight("Right"),
    Minus("-"),
    Equal("="),
    LeftBracket("["),
    RightBracket("]"),
    Backslash("\\"),
    Semicolon(";"),
    Apostrophe("'"),
    Comma(","),
    Period("."),
    Slash("/"),
    Grave("`"),
    Numpad0("Num 0"),
    Numpad1("Num 1"),
    Numpad2("Num 2"),
    Numpad3("Num 3"),
    Numpad4("Num 4"),
    Numpad5("Num 5"),
    Numpad6("Num 6"),
    Numpad7("Num 7"),
    Numpad8("Num 8"),
    Numpad9("Num 9"),
    NumpadAdd("Num +"),
    NumpadSubtract("Num -"),
    NumpadMultiply("Num *"),
    NumpadDivide("Num /"),
    NumpadDecimal("Num ."),
    NumpadEnter("Num Enter"),
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
    GlobalHotkeyBinding(
        action = GlobalHotkeyAction.SelectRegion,
        gesture = GlobalHotkeyGesture(
            modifiers = setOf(GlobalHotkeyModifier.Control, GlobalHotkeyModifier.Shift),
            key = GlobalHotkeyKey.F8,
        ),
    ),
    GlobalHotkeyBinding(
        action = GlobalHotkeyAction.MarkImportantFrame,
        gesture = GlobalHotkeyGesture(
            modifiers = setOf(GlobalHotkeyModifier.Control, GlobalHotkeyModifier.Shift),
            key = GlobalHotkeyKey.F12,
        ),
    ),
    GlobalHotkeyBinding(
        action = GlobalHotkeyAction.SelectRegionAndStartRecording,
        gesture = GlobalHotkeyGesture(
            modifiers = setOf(GlobalHotkeyModifier.Control, GlobalHotkeyModifier.Shift),
            key = GlobalHotkeyKey.F7,
        ),
    ),
)
