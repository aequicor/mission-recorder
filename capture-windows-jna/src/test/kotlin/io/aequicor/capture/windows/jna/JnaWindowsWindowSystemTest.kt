package io.aequicor.capture.windows.jna

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JnaWindowsWindowSystemTest {
    @Test
    fun detectsInputHeldAtPollTime() {
        assertTrue(isWindowsInputActive(0x8000.toShort()))
    }

    @Test
    fun detectsInputPressedAndReleasedBetweenPolls() {
        assertTrue(isWindowsInputActive(0x0001))
    }

    @Test
    fun ignoresInputWithoutCurrentPress() {
        assertFalse(isWindowsInputActive(0x0000))
    }
}
