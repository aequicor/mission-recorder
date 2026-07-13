package io.aequicor.desktop

import io.aequicor.capture.core.CaptureRegion
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopCapturePresentationTest {
    @Test
    fun paintsBrightBlueCoreGlowAndStrongerCorners() {
        val surface = CaptureIndicatorSurface(IndicatorSide.Top).apply { setSize(200, 12) }
        val image = BufferedImage(200, 12, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().use { graphics -> surface.paint(graphics) }

        val core = Color(image.getRGB(100, 1), true)
        val glow = Color(image.getRGB(100, 9), true)
        val corner = Color(image.getRGB(5, 5), true)

        assertTrue(core.alpha > 200 && core.blue > core.red)
        assertTrue(glow.alpha in 1 until core.alpha)
        assertTrue(corner.alpha > glow.alpha)
    }

    @Test
    fun usesVisibleDefaultIndicatorThickness() {
        val edges = indicatorBounds(Rectangle(100, 200, 800, 600))

        assertEquals(12, edges.first().height)
        assertEquals(12, edges.last().width)
    }

    @Test
    fun createsFourIndicatorEdgesInsideCaptureBounds() {
        val edges = indicatorBounds(Rectangle(100, 200, 800, 600), thickness = 3)

        assertEquals(
            listOf(
                Rectangle(100, 200, 800, 3),
                Rectangle(100, 797, 800, 3),
                Rectangle(100, 200, 3, 600),
                Rectangle(897, 200, 3, 600),
            ),
            edges,
        )
    }

    @Test
    fun resolvesDesktopMonitorAndRegionBounds() {
        val controller = FakeCaptureWindowController()
        val presentation = DesktopCapturePresentation(
            windowController = controller,
            screenBounds = {
                listOf(
                    Rectangle(-1280, 0, 1280, 720),
                    Rectangle(0, 0, 1920, 1080),
                )
            },
        )

        assertEquals(
            Rectangle(-1280, 0, 3200, 1080),
            presentation.bounds(CaptureSource.Screen(CaptureSourceId("screen:all"), "All screens")),
        )
        assertEquals(
            Rectangle(0, 0, 1920, 1080),
            presentation.bounds(CaptureSource.Monitor(CaptureSourceId("monitor:1"), "Monitor 2", index = 1)),
        )
        assertEquals(
            Rectangle(20, 30, 640, 360),
            presentation.bounds(
                CaptureSource.Region(
                    id = CaptureSourceId("region:test"),
                    displayName = "Region",
                    region = CaptureRegion(20, 30, 640, 360),
                ),
            ),
        )
    }

    @Test
    fun delegatesWindowBoundsAndActivationToPlatformController() {
        val controller = FakeCaptureWindowController(bounds = Rectangle(10, 20, 800, 600))
        val presentation = DesktopCapturePresentation(windowController = controller)
        val source = CaptureSource.Application(CaptureSourceId("application:test:42"), "Selected app")

        assertEquals(Rectangle(10, 20, 800, 600), presentation.bounds(source))
        assertTrue(presentation.activate(source))
        assertEquals(source, controller.activatedSource)
    }
}

private inline fun <T : java.awt.Graphics> T.use(block: (T) -> Unit) {
    try {
        block(this)
    } finally {
        dispose()
    }
}

private class FakeCaptureWindowController(
    private val bounds: Rectangle? = null,
) : CaptureWindowController {
    var activatedSource: CaptureSource? = null
        private set

    override fun bounds(source: CaptureSource): Rectangle? = bounds?.let(::Rectangle)

    override fun activate(source: CaptureSource): Boolean {
        activatedSource = source
        return true
    }
}
