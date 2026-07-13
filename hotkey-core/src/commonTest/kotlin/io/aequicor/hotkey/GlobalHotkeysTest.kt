package io.aequicor.hotkey

import kotlin.test.Test
import kotlin.test.assertEquals

class GlobalHotkeysTest {
    @Test
    fun defaultBindingsHaveUniqueActionsAndGestures() {
        assertEquals(
            defaultDesktopGlobalHotkeys.size,
            defaultDesktopGlobalHotkeys.map(GlobalHotkeyBinding::action).toSet().size,
        )
        assertEquals(
            defaultDesktopGlobalHotkeys.size,
            defaultDesktopGlobalHotkeys.map(GlobalHotkeyBinding::gesture).toSet().size,
        )
        assertEquals(
            GlobalHotkeyGesture(
                modifiers = setOf(GlobalHotkeyModifier.Control, GlobalHotkeyModifier.Shift),
                key = GlobalHotkeyKey.F7,
            ),
            defaultDesktopGlobalHotkeys.single {
                it.action == GlobalHotkeyAction.SelectRegionAndStartRecording
            }.gesture,
        )
    }
}
