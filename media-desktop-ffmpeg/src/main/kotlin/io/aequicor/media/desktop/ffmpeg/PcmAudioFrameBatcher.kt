package io.aequicor.media.desktop.ffmpeg

import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.MediaTimestamp
import kotlin.math.abs
import kotlin.math.min

internal class PcmAudioFrameBatcher(
    private val targetSampleCount: Int = AAC_FRAME_SAMPLE_COUNT,
) {
    private var template: AudioFrame? = null
    private var pendingTimestamp: MediaTimestamp? = null
    private var pendingSampleCount: Int = 0
    private var pendingData: ByteArray = ByteArray(0)

    init {
        require(targetSampleCount > 0) { "Target audio batch size must be positive." }
    }

    fun append(frame: AudioFrame): List<AudioFrame> {
        validateAudioFrame(frame)
        val output = mutableListOf<AudioFrame>()
        if (pendingSampleCount > 0 && (!isCompatible(frame) || !isContinuous(frame))) {
            drain()?.let(output::add)
        }

        val bytesPerFrame = frame.channelCount * frame.sampleFormat.bytesPerSample
        ensureCapacity(targetSampleCount * bytesPerFrame)
        val source = requireNotNull(frame.audioData)
        var sourceSampleOffset = 0
        while (sourceSampleOffset < frame.sampleCount) {
            if (pendingSampleCount == 0) {
                template = frame
                pendingTimestamp = MediaTimestamp(
                    frame.timestamp.nanoseconds + sourceSampleOffset.toLong() * NANOS_PER_SECOND / frame.sampleRate,
                )
            }
            val copiedSamples = min(targetSampleCount - pendingSampleCount, frame.sampleCount - sourceSampleOffset)
            source.copyInto(
                destination = pendingData,
                destinationOffset = pendingSampleCount * bytesPerFrame,
                startIndex = sourceSampleOffset * bytesPerFrame,
                endIndex = (sourceSampleOffset + copiedSamples) * bytesPerFrame,
            )
            pendingSampleCount += copiedSamples
            sourceSampleOffset += copiedSamples
            if (pendingSampleCount == targetSampleCount) {
                output += requireNotNull(drain())
            }
        }
        return output
    }

    fun drain(): AudioFrame? {
        if (pendingSampleCount == 0) return null
        val frame = requireNotNull(template)
        val bytesPerFrame = frame.channelCount * frame.sampleFormat.bytesPerSample
        val result = frame.copy(
            timestamp = requireNotNull(pendingTimestamp),
            sampleCount = pendingSampleCount,
            audioData = pendingData.copyOf(pendingSampleCount * bytesPerFrame),
        )
        template = null
        pendingTimestamp = null
        pendingSampleCount = 0
        return result
    }

    private fun isCompatible(frame: AudioFrame): Boolean {
        val current = template ?: return true
        return current.sampleRate == frame.sampleRate &&
            current.channelCount == frame.channelCount &&
            current.sampleFormat == frame.sampleFormat &&
            current.sourceId == frame.sourceId
    }

    private fun isContinuous(frame: AudioFrame): Boolean {
        val current = template ?: return true
        val timestamp = pendingTimestamp ?: return true
        val expected = timestamp.nanoseconds + pendingSampleCount.toLong() * NANOS_PER_SECOND / current.sampleRate
        val tolerance = NANOS_PER_SECOND / current.sampleRate
        return abs(frame.timestamp.nanoseconds - expected) <= tolerance
    }

    private fun ensureCapacity(size: Int) {
        if (pendingData.size < size) {
            pendingData = ByteArray(size)
        }
    }
}

private const val AAC_FRAME_SAMPLE_COUNT = 1_024
private const val NANOS_PER_SECOND = 1_000_000_000L
