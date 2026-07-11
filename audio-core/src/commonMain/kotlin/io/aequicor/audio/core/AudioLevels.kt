package io.aequicor.audio.core

import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.AudioSampleFormat
import kotlin.math.sqrt

object AudioLevelMeter {
    fun measure(frame: AudioFrame): AudioLevels {
        val data = frame.audioData ?: return AudioLevels.Silence
        val totalSamples = frame.totalInterleavedSamples()
        if (totalSamples == 0) {
            return AudioLevels.Silence
        }
        require(data.size >= frame.requiredBytes()) {
            "Audio frame payload is shorter than required by sampleCount, channelCount, and sampleFormat."
        }

        var peak = 0.0
        var sumSquares = 0.0
        repeat(totalSamples) { index ->
            val sample = frame.readNormalizedSample(index)
            val magnitude = kotlin.math.abs(sample)
            if (magnitude > peak) {
                peak = magnitude
            }
            sumSquares += sample * sample
        }
        return AudioLevels(
            peak = peak.coerceIn(0.0, 1.0),
            rms = sqrt(sumSquares / totalSamples).coerceIn(0.0, 1.0),
        )
    }
}

data class AudioLevels(
    val peak: Double,
    val rms: Double,
) {
    companion object {
        val Silence = AudioLevels(peak = 0.0, rms = 0.0)
    }
}

internal fun AudioFrame.totalInterleavedSamples(): Int = sampleCount * channelCount

internal fun AudioFrame.requiredBytes(): Int = totalInterleavedSamples() * sampleFormat.bytesPerSample

internal fun AudioFrame.readNormalizedSample(index: Int): Double {
    val data = requireNotNull(audioData) { "Audio frame has no PCM payload." }
    return when (sampleFormat) {
        AudioSampleFormat.PcmS16Le -> {
            val offset = index * 2
            val low = data[offset].toInt() and 0xff
            val high = data[offset + 1].toInt()
            val value = (high shl 8) or low
            value.toShort().toDouble() / Short.MAX_VALUE.toDouble()
        }
        AudioSampleFormat.PcmFloat32Le -> {
            val offset = index * 4
            val bits = (data[offset].toInt() and 0xff) or
                ((data[offset + 1].toInt() and 0xff) shl 8) or
                ((data[offset + 2].toInt() and 0xff) shl 16) or
                ((data[offset + 3].toInt() and 0xff) shl 24)
            Float.fromBits(bits).toDouble().coerceIn(-1.0, 1.0)
        }
    }
}
