package io.aequicor.media.desktop.ffmpeg

import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.AudioSampleFormat
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.MediaTimestamp
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmAudioFrameBatcherTest {
    @Test
    fun combinesSmallContiguousFramesIntoOneEncoderPacket() {
        val batcher = PcmAudioFrameBatcher(targetSampleCount = 4)

        assertTrue(batcher.append(frame(timestamp = 0, samples = byteArrayOf(1, 2, 3, 4))).isEmpty())
        val output = batcher.append(frame(timestamp = 2_000_000, samples = byteArrayOf(5, 6, 7, 8))).single()

        assertEquals(4, output.sampleCount)
        assertEquals(MediaTimestamp(0), output.timestamp)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), output.audioData)
    }

    @Test
    fun flushesPartialPacketBeforeTimestampGap() {
        val batcher = PcmAudioFrameBatcher(targetSampleCount = 4)
        batcher.append(frame(timestamp = 0, samples = byteArrayOf(1, 2)))

        val output = batcher.append(frame(timestamp = 10_000_000, samples = byteArrayOf(3, 4))).single()

        assertEquals(1, output.sampleCount)
        assertContentEquals(byteArrayOf(1, 2), output.audioData)
    }

    private fun frame(timestamp: Long, samples: ByteArray): AudioFrame = AudioFrame(
        timestamp = MediaTimestamp(timestamp),
        sampleRate = 1_000,
        channelCount = 1,
        sampleCount = samples.size / Short.SIZE_BYTES,
        sourceId = AudioSourceId("mic:test"),
        sampleFormat = AudioSampleFormat.PcmS16Le,
        audioData = samples,
    )
}
