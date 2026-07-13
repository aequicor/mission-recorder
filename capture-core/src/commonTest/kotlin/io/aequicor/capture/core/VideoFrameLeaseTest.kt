package io.aequicor.capture.core

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoFrameLeaseTest {
    @Test
    fun releasesBackingStorageOnlyOnceAcrossFrameCopies() {
        var releases = 0
        val frame = VideoFrame(
            timestamp = MediaTimestamp(0),
            width = 1,
            height = 1,
            pixelFormat = PixelFormat.Bgra8888,
            strideBytes = 4,
            sourceId = CaptureSourceId("screen:test"),
            pixelData = ByteArray(4),
            lease = VideoFrameLease { releases += 1 },
        )

        frame.release()
        frame.copy(timestamp = MediaTimestamp(1)).release()

        assertEquals(1, releases)
    }
}
