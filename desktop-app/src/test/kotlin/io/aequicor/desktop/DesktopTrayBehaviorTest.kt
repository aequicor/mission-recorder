package io.aequicor.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopTrayBehaviorTest {
    @Test
    fun hidesApplicationWhenSystemTrayIsSupported() {
        assertEquals(DesktopCloseAction.HideToTray, desktopCloseAction(traySupported = true))
    }

    @Test
    fun minimizesApplicationWhenSystemTrayIsUnavailable() {
        assertEquals(DesktopCloseAction.MinimizeToTaskbar, desktopCloseAction(traySupported = false))
    }
}
