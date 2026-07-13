package io.aequicor.desktop

import java.awt.Frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopTrayBehaviorTest {
    @Test
    fun hidesApplicationWhenSystemTrayIsSupported() {
        assertEquals(DesktopCloseAction.HideToTray, desktopCloseAction(traySupported = true))
    }

    @Test
    fun minimizesApplicationWhenSystemTrayIsUnavailable() {
        assertEquals(DesktopCloseAction.MinimizeToTaskbar, desktopCloseAction(traySupported = false))
    }

    @Test
    fun compactsMainWindowWhenFocusMovesOutsideApplication() {
        assertTrue(shouldCompactOnFocusLoss(focusMovedWithinApplication = false))
    }

    @Test
    fun keepsMainWindowOpenForApplicationDialogs() {
        assertFalse(shouldCompactOnFocusLoss(focusMovedWithinApplication = true))
    }

    @Test
    fun restoresMainWindowWhenMiniControllerIsMaximized() {
        assertTrue(shouldRestoreMainWindowFromMiniController(Frame.MAXIMIZED_BOTH))
        assertTrue(shouldRestoreMainWindowFromMiniController(Frame.MAXIMIZED_BOTH or Frame.ICONIFIED))
        assertFalse(shouldRestoreMainWindowFromMiniController(Frame.NORMAL))
    }

    @Test
    fun runsPreviewOnlyWhileMainWindowIsVisible() {
        assertTrue(
            shouldStartPreview(
                mainWindowVisible = true,
                mainWindowMinimized = false,
                canStartPreview = true,
            ),
        )
        assertFalse(
            shouldStartPreview(
                mainWindowVisible = false,
                mainWindowMinimized = false,
                canStartPreview = true,
            ),
        )
        assertFalse(
            shouldStartPreview(
                mainWindowVisible = true,
                mainWindowMinimized = true,
                canStartPreview = true,
            ),
        )
    }

    @Test
    fun stopsRunningPreviewWhenMainWindowIsHiddenOrMinimized() {
        assertTrue(
            shouldStopPreview(
                mainWindowVisible = false,
                mainWindowMinimized = false,
                previewRunning = true,
            ),
        )
        assertTrue(
            shouldStopPreview(
                mainWindowVisible = true,
                mainWindowMinimized = true,
                previewRunning = true,
            ),
        )
        assertFalse(
            shouldStopPreview(
                mainWindowVisible = true,
                mainWindowMinimized = false,
                previewRunning = true,
            ),
        )
    }
}
