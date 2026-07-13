package io.aequicor.capture.platform

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
}
