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
        assertEquals("Ctrl", renderer.update(listOf("Ctrl"), timestampNanoseconds = 11))
        assertEquals("Ctrl", renderer.update(emptyList(), timestampNanoseconds = 12))
        assertEquals("Ctrl", renderer.update(emptyList(), timestampNanoseconds = 112))
        assertNull(renderer.update(emptyList(), timestampNanoseconds = 113))
    }

    @Test
    fun keepsInputVisibleWhileItRemainsHeld() {
        val renderer = InputOverlayRenderer(visibleDurationNanoseconds = 100)

        renderer.update(listOf("LMB"), timestampNanoseconds = 0)

        assertEquals("ЛКМ", renderer.update(listOf("LMB"), timestampNanoseconds = 101))
    }

    @Test
    fun keepsMouseButtonVisibleBrieflyAfterRelease() {
        val renderer = InputOverlayRenderer(visibleDurationNanoseconds = 100)

        assertEquals("ЛКМ", renderer.update(listOf("LMB"), timestampNanoseconds = 0))
        assertEquals("ЛКМ", renderer.update(emptyList(), timestampNanoseconds = 1))
        assertEquals("ЛКМ", renderer.update(emptyList(), timestampNanoseconds = 101))
        assertNull(renderer.update(emptyList(), timestampNanoseconds = 102))
    }

    @Test
    fun keepsReleasedInputVisibleForOneTenthOfASecondByDefault() {
        val renderer = InputOverlayRenderer()

        renderer.update(listOf("LMB"), timestampNanoseconds = 0)

        assertEquals("ЛКМ", renderer.update(emptyList(), timestampNanoseconds = 1))
        assertEquals("ЛКМ", renderer.update(emptyList(), timestampNanoseconds = 100_000_001))
        assertNull(renderer.update(emptyList(), timestampNanoseconds = 100_000_002))
    }

    @Test
    fun paintsLabelWithinSmallFrameBounds() {
        val renderer = InputOverlayRenderer()
        val pixels = ByteArray(64 * 32 * 4) { 0xff.toByte() }

        renderer.drawBgra(pixels, 64, 32, hotspotX = 63, hotspotY = 31, text = "Ctrl + C")

        assertTrue(pixels.any { channel -> channel != 0xff.toByte() })
    }

    @Test
    fun labelsMovedMouseButtonAsDrag() {
        val renderer = InputOverlayRenderer()

        assertEquals(
            "ЛКМ",
            renderer.update(listOf("LMB"), timestampNanoseconds = 0, hotspotX = 20, hotspotY = 20),
        )
        assertEquals(
            "ЛКМ",
            renderer.update(listOf("LMB"), timestampNanoseconds = 1, hotspotX = 27, hotspotY = 20),
        )
        assertEquals(
            "ЛКМ + drag",
            renderer.update(listOf("LMB"), timestampNanoseconds = 2, hotspotX = 28, hotspotY = 20),
        )
    }

    @Test
    fun keepsModifiersAndMouseButtonInDragLabel() {
        val renderer = InputOverlayRenderer()

        assertEquals(
            "Ctrl + ЛКМ",
            renderer.update(listOf("Ctrl", "LMB"), timestampNanoseconds = 0, hotspotX = 20, hotspotY = 20),
        )
        assertEquals(
            "Ctrl + ЛКМ + drag",
            renderer.update(listOf("Ctrl", "LMB"), timestampNanoseconds = 1, hotspotX = 28, hotspotY = 20),
        )
    }

    @Test
    fun labelsSecondNearbyClickAsDouble() {
        val renderer = InputOverlayRenderer()

        renderer.update(listOf("LMB"), timestampNanoseconds = 0, hotspotX = 20, hotspotY = 20)
        renderer.update(emptyList(), timestampNanoseconds = 50_000_000, hotspotX = 20, hotspotY = 20)

        assertEquals(
            "(double) ЛКМ",
            renderer.update(listOf("LMB"), timestampNanoseconds = 300_000_000, hotspotX = 22, hotspotY = 21),
        )
    }

    @Test
    fun doesNotLabelLateOrDistantSecondClickAsDouble() {
        val renderer = InputOverlayRenderer()

        renderer.update(listOf("LMB"), timestampNanoseconds = 0, hotspotX = 20, hotspotY = 20)
        renderer.update(emptyList(), timestampNanoseconds = 50_000_000, hotspotX = 20, hotspotY = 20)
        assertEquals(
            "ЛКМ",
            renderer.update(listOf("LMB"), timestampNanoseconds = 550_000_001, hotspotX = 20, hotspotY = 20),
        )
        renderer.update(emptyList(), timestampNanoseconds = 600_000_000, hotspotX = 20, hotspotY = 20)

        assertEquals(
            "ЛКМ",
            renderer.update(listOf("LMB"), timestampNanoseconds = 700_000_000, hotspotX = 28, hotspotY = 20),
        )
    }

    @Test
    fun labelsHeldMouseButtonAsLongPress() {
        val renderer = InputOverlayRenderer()

        assertEquals(
            "ЛКМ",
            renderer.update(listOf("LMB"), timestampNanoseconds = 0, hotspotX = 20, hotspotY = 20),
        )
        assertEquals(
            "ЛКМ",
            renderer.update(listOf("LMB"), timestampNanoseconds = 499_999_999, hotspotX = 20, hotspotY = 20),
        )
        assertEquals(
            "(long) ЛКМ",
            renderer.update(listOf("LMB"), timestampNanoseconds = 500_000_000, hotspotX = 20, hotspotY = 20),
        )
    }

    @Test
    fun recognizesLongPressOnReleaseAndDoesNotReuseItAsFirstClick() {
        val renderer = InputOverlayRenderer()

        renderer.update(listOf("LMB"), timestampNanoseconds = 0, hotspotX = 20, hotspotY = 20)
        assertEquals(
            "(long) ЛКМ",
            renderer.update(emptyList(), timestampNanoseconds = 500_000_000, hotspotX = 20, hotspotY = 20),
        )

        assertEquals(
            "ЛКМ",
            renderer.update(listOf("LMB"), timestampNanoseconds = 600_000_000, hotspotX = 20, hotspotY = 20),
        )
    }

    @Test
    fun dragTakesPrecedenceOverLongPress() {
        val renderer = InputOverlayRenderer()

        renderer.update(listOf("LMB"), timestampNanoseconds = 0, hotspotX = 20, hotspotY = 20)
        renderer.update(listOf("LMB"), timestampNanoseconds = 1, hotspotX = 28, hotspotY = 20)

        assertEquals(
            "ЛКМ + drag",
            renderer.update(listOf("LMB"), timestampNanoseconds = 500_000_000, hotspotX = 28, hotspotY = 20),
        )
    }
}
