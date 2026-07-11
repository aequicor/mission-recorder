package io.aequicor.capture.windows.jna

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsCaptureAdapterFactoryTest {
    @Test
    fun recognizesWindowsWithoutLoadingNativeApis() {
        assertTrue(WindowsCaptureAdapterFactory.isSupported("Windows 11"))
        assertFalse(WindowsCaptureAdapterFactory.isSupported("Linux"))
        assertFalse(WindowsCaptureAdapterFactory.isSupported("Mac OS X"))
    }
}
