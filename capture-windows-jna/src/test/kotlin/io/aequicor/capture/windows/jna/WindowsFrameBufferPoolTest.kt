package io.aequicor.capture.windows.jna

import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class WindowsFrameBufferPoolTest {
    @Test
    fun reusesReleasedBufferWithMatchingSize() {
        val pool = WindowsFrameBufferPool(maxRetainedBuffers = 2)
        val first = pool.acquire(16)
        pool.release(first)

        assertSame(first, pool.acquire(16))
    }

    @Test
    fun doesNotReturnBufferWithDifferentSize() {
        val pool = WindowsFrameBufferPool(maxRetainedBuffers = 2)
        val first = pool.acquire(16)
        pool.release(first)

        assertNotSame(first, pool.acquire(32))
    }
}
