package io.aequicor.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopCaptureExclusionTest {
    @Test
    fun selectsNativeCaptureAffinityOnlyOnWindows() {
        assertEquals(0x00000011, captureAffinity("Windows 11", visible = false))
        assertEquals(0x00000000, captureAffinity("Windows 11", visible = true))
        assertNull(captureAffinity("Linux", visible = false))
        assertNull(captureAffinity("Mac OS X", visible = true))
    }
}
