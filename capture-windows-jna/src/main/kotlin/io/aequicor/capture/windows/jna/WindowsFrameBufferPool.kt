package io.aequicor.capture.windows.jna

internal class WindowsFrameBufferPool(
    private val maxRetainedBuffers: Int,
) {
    private val buffers = ArrayDeque<ByteArray>()

    init {
        require(maxRetainedBuffers > 0) { "Frame buffer pool capacity must be positive." }
    }

    @Synchronized
    fun acquire(size: Int): ByteArray {
        require(size > 0) { "Frame buffer size must be positive." }
        val matchingIndex = buffers.indexOfFirst { buffer -> buffer.size == size }
        return if (matchingIndex >= 0) buffers.removeAt(matchingIndex) else ByteArray(size)
    }

    @Synchronized
    fun release(buffer: ByteArray) {
        if (buffers.size < maxRetainedBuffers) {
            buffers.addLast(buffer)
        }
    }

    @Synchronized
    fun clear() {
        buffers.clear()
    }
}
