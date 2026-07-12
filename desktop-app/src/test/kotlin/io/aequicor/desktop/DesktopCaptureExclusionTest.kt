package io.aequicor.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopCaptureExclusionTest {
    @Test
    fun enablesNativeCaptureExclusionOnlyOnWindows() {
        assertEquals(0x00000011, captureExclusionAffinity("Windows 11"))
        assertNull(captureExclusionAffinity("Linux"))
        assertNull(captureExclusionAffinity("Mac OS X"))
    }
}
