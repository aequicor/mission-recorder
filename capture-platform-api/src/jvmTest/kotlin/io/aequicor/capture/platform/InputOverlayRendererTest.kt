package io.aequicor.capture.platform

import io.aequicor.capture.core.InputEventFrameMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InputOverlayRendererTest {
    @Test
    fun showsNewCombinationAndKeepsItVisibleAfterRelease() {
        val renderer = InputOverlayRenderer(visibleDurationNanoseconds = 100)

        assertEquals("Ctrl", renderer.update(listOf("Ctrl"), timestampNanoseconds = 0))
        assertEquals("Ctrl + C", renderer.update(listOf("Ctrl", "C"), timestampNanoseconds = 10))
        assertEquals("Ctrl + C", renderer.update(emptyList(), timestampNanoseconds = 109))
        assertNull(renderer.update(emptyList(), timestampNanoseconds = 111))
    }

    @Test
    fun doesNotRestartTimeoutWhileInputRemainsHeld() {
        val renderer = InputOverlayRenderer(visibleDurationNanoseconds = 100)

        renderer.update(listOf("LMB"), timestampNanoseconds = 0)

        assertNull(renderer.update(listOf("LMB"), timestampNanoseconds = 101))
    }

    @Test
    fun paintsLabelWithinSmallFrameBounds() {
        val renderer = InputOverlayRenderer()
        val pixels = ByteArray(64 * 32 * 4) { 0xff.toByte() }

        renderer.drawBgra(pixels, 64, 32, hotspotX = 63, hotspotY = 31, text = "Ctrl + C")

        assertTrue(pixels.any { channel -> channel != 0xff.toByte() })
    }

    @Test
    fun paintsEventMarkerOnlyOnFirstFrameOfNewInput() {
        val renderer = InputOverlayRenderer()
        val first = ByteArray(160 * 120 * 4) { 0xff.toByte() }
        val held = first.copyOf()

        val label = renderer.update(listOf("LMB"), timestampNanoseconds = 0)
        renderer.drawRgba(first, 160, 120, hotspotX = 80, hotspotY = 60, text = requireNotNull(label))
        renderer.drawPendingEventMarkerRgba(first, 160, 120)
        renderer.update(listOf("LMB"), timestampNanoseconds = 1)
        renderer.drawRgba(held, 160, 120, hotspotX = 80, hotspotY = 60, text = requireNotNull(label))
        renderer.drawPendingEventMarkerRgba(held, 160, 120)

        val markerOffset = (
            (InputEventFrameMarker.MARGIN_PIXELS + InputEventFrameMarker.CELL_SIZE_PIXELS / 2) * 160 +
                InputEventFrameMarker.MARGIN_PIXELS + InputEventFrameMarker.CELL_SIZE_PIXELS / 2
            ) * 4
        assertEquals(InputEventFrameMarker.ACCENT_RED, first[markerOffset].toInt() and 0xff)
        assertEquals(InputEventFrameMarker.ACCENT_GREEN, first[markerOffset + 1].toInt() and 0xff)
        assertEquals(InputEventFrameMarker.ACCENT_BLUE, first[markerOffset + 2].toInt() and 0xff)
        assertEquals(0xff, held[markerOffset].toInt() and 0xff)
    }
}
