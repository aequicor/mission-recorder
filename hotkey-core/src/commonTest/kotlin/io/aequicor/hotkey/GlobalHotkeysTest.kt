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
    }
}
