package io.aequicor.desktop

import io.aequicor.hotkey.GlobalHotkeyGesture
import io.aequicor.hotkey.GlobalHotkeyKey
import io.aequicor.hotkey.GlobalHotkeyModifier
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopHotkeySettingsDialogTest {
    @Test
    fun resolvesRegularFunctionAndNumpadKeysFromDesktopEvents() {
        assertEquals(
            GlobalHotkeyKey.K,
            desktopGlobalHotkeyKey(KeyEvent.VK_K, KeyEvent.KEY_LOCATION_STANDARD),
        )
        assertEquals(
            GlobalHotkeyKey.F3,
            desktopGlobalHotkeyKey(KeyEvent.VK_F3, KeyEvent.KEY_LOCATION_STANDARD),
        )
        assertEquals(
            GlobalHotkeyKey.Numpad1,
            desktopGlobalHotkeyKey(KeyEvent.VK_NUMPAD1, KeyEvent.KEY_LOCATION_NUMPAD),
        )
        assertEquals(
            GlobalHotkeyKey.NumpadEnter,
            desktopGlobalHotkeyKey(KeyEvent.VK_ENTER, KeyEvent.KEY_LOCATION_NUMPAD),
        )
        assertNull(desktopGlobalHotkeyKey(KeyEvent.VK_CONTROL, KeyEvent.KEY_LOCATION_LEFT))
    }

    @Test
    fun formatsCapturedShortcutInModifierOrder() {
        assertEquals(
            "Ctrl+Shift+K",
            formatGlobalHotkeyGesture(
                GlobalHotkeyGesture(
                    modifiers = setOf(GlobalHotkeyModifier.Shift, GlobalHotkeyModifier.Control),
                    key = GlobalHotkeyKey.K,
                ),
            ),
        )
        assertEquals(
            "Num 1",
            formatGlobalHotkeyGesture(
                GlobalHotkeyGesture(modifiers = emptySet(), key = GlobalHotkeyKey.Numpad1),
            ),
        )
    }
}
