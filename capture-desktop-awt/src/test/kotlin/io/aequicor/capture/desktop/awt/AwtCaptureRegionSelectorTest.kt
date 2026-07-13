package io.aequicor.capture.desktop.awt

import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.CoordinateSpace
import java.awt.Color
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AwtCaptureRegionSelectorTest {
    @Test
    fun normalizesReverseDragInsideVirtualDesktop() {
        val bounds = Rectangle(-1920, 0, 5360, 1440)

        val selection = normalizedSelection(Point(400, 900), Point(-500, 200), bounds)

        assertEquals(Rectangle(-500, 200, 900, 700), selection)
    }

    @Test
    fun rejectsClickWithoutArea() {
        assertNull(normalizedSelection(Point(10, 10), Point(12, 12), Rectangle(0, 0, 100, 100)))
    }

    @Test
    fun createsCursorWithLightForegroundAndDarkOutline() {
        val image = createHighContrastCursorImage(width = 32, height = 32)
        val colors = buildSet {
            repeat(image.height) { y ->
                repeat(image.width) { x -> add(image.getRGB(x, y)) }
            }
        }

        assertTrue(Color.WHITE.rgb in colors)
        assertTrue(Color.BLACK.rgb in colors)
    }

    @Test
    fun mapsSingleMonitorSelectionToLogicalCaptureRegion() {
        val geometry = AwtDesktopGeometry(
            virtualBounds = Rectangle(-1920, 0, 5360, 1440),
            monitors = listOf(
                AwtMonitorGeometry(0, Rectangle(-1920, 0, 1920, 1080), 1.0),
                AwtMonitorGeometry(1, Rectangle(0, 0, 3440, 1440), 1.5),
            ),
        )

        val region = geometry.toCaptureRegion(Rectangle(100, 200, 800, 600))

        assertEquals(CaptureSourceId("monitor:1"), region.monitorId)
        assertEquals(1.5, region.scaleFactor)
        assertEquals(CoordinateSpace.LogicalPixels, region.coordinateSpace)
    }

    @Test
    fun leavesMonitorUnsetWhenSelectionSpansDisplays() {
        val geometry = AwtDesktopGeometry(
            virtualBounds = Rectangle(-100, 0, 200, 100),
            monitors = listOf(
                AwtMonitorGeometry(0, Rectangle(-100, 0, 100, 100), 1.0),
                AwtMonitorGeometry(1, Rectangle(0, 0, 100, 100), 2.0),
            ),
        )

        val region = geometry.toCaptureRegion(Rectangle(-20, 10, 40, 40))

        assertNull(region.monitorId)
        assertEquals(1.0, region.scaleFactor)
    }
}
