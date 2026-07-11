package io.aequicor.desktop

import java.awt.Dimension
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopWindowPlacementTest {
    private val panelSize = Dimension(440, 92)

    @Test
    fun keepsPositionInsideNegativeCoordinateMonitor() {
        val position = clampWindowPosition(
            preferred = DesktopWindowPosition(x = -1200, y = 120),
            windowSize = panelSize,
            screenBounds = listOf(
                Rectangle(-1920, 0, 1920, 1080),
                Rectangle(0, 0, 1920, 1080),
            ),
        )

        assertEquals(DesktopWindowPosition(x = -1200, y = 120), position)
    }

    @Test
    fun movesPositionFromDisconnectedMonitorToPrimaryScreen() {
        val position = clampWindowPosition(
            preferred = DesktopWindowPosition(x = -1200, y = 100),
            windowSize = panelSize,
            screenBounds = listOf(Rectangle(0, 0, 1920, 1080)),
        )

        assertEquals(DesktopWindowPosition(x = 0, y = 100), position)
    }

    @Test
    fun choosesNearestRealMonitorInsteadOfVirtualDesktopGap() {
        val position = clampWindowPosition(
            preferred = DesktopWindowPosition(x = 1200, y = 100),
            windowSize = Dimension(400, 100),
            screenBounds = listOf(
                Rectangle(0, 0, 1000, 700),
                Rectangle(1000, 500, 1000, 700),
            ),
        )

        assertEquals(DesktopWindowPosition(x = 1200, y = 500), position)
    }

    @Test
    fun keepsEntirePanelInsideScreenBounds() {
        val position = clampWindowPosition(
            preferred = DesktopWindowPosition(x = 1900, y = 1040),
            windowSize = panelSize,
            screenBounds = listOf(Rectangle(0, 0, 1920, 1080)),
        )

        assertEquals(DesktopWindowPosition(x = 1480, y = 988), position)
    }

    @Test
    fun leavesPositionUnchangedWhenScreenGeometryIsUnavailable() {
        val preferred = DesktopWindowPosition(x = 50, y = 70)

        val position = clampWindowPosition(
            preferred = preferred,
            windowSize = panelSize,
            screenBounds = emptyList(),
        )

        assertEquals(preferred, position)
    }
}
