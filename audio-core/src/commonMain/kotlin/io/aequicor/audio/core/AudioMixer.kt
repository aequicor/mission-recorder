package io.aequicor.audio.core

import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.AudioSampleFormat
import io.aequicor.capture.core.AudioSourceId
import kotlin.math.roundToInt

class AudioMixer {
    fun mix(inputs: List<AudioMixInput>, outputSourceId: AudioSourceId): AudioFrame {
        require(inputs.isNotEmpty()) { "At least one audio input is required." }
        val reference = inputs.first().frame
        validateCompatible(reference, inputs)
        val totalSamples = reference.totalInterleavedSamples()
        val mixedSamples = DoubleArray(totalSamples)

        inputs.forEach { input ->
            if (!input.muted) {
                repeat(totalSamples) { index ->
                    mixedSamples[index] += input.frame.readNormalizedSample(index) * input.gain
                }
            }
        }

        val outputData = when (reference.sampleFormat) {
            AudioSampleFormat.PcmS16Le -> mixedSamples.toPcmS16Le()
            AudioSampleFormat.PcmFloat32Le -> mixedSamples.toPcmFloat32Le()
        }
        return reference.copy(sourceId = outputSourceId, audioData = outputData)
    }

    private fun validateCompatible(reference: AudioFrame, inputs: List<AudioMixInput>) {
        inputs.forEach { input ->
            require(input.gain >= 0.0) { "Audio gain must not be negative." }
            val frame = input.frame
            val audioData = frame.audioData
            require(audioData != null) { "Audio frame has no PCM payload." }
            require(audioData.size >= frame.requiredBytes()) {
                "Audio frame payload is shorter than required by sampleCount, channelCount, and sampleFormat."
            }
            require(frame.sampleRate == reference.sampleRate) { "Audio sample rates must match." }
            require(frame.channelCount == reference.channelCount) { "Audio channel counts must match." }
            require(frame.sampleCount == reference.sampleCount) { "Audio sample counts must match." }
            require(frame.sampleFormat == reference.sampleFormat) { "Audio sample formats must match." }
            require(frame.timestamp == reference.timestamp) { "Audio frame timestamps must match." }
        }
    }
}

data class AudioMixInput(
    val frame: AudioFrame,
    val gain: Double = 1.0,
    val muted: Boolean = false,
)

private fun DoubleArray.toPcmS16Le(): ByteArray {
    val bytes = ByteArray(size * 2)
    forEachIndexed { index, sample ->
        val value = (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
        val offset = index * 2
        bytes[offset] = (value.toInt() and 0xff).toByte()
        bytes[offset + 1] = ((value.toInt() shr 8) and 0xff).toByte()
    }
    return bytes
}

private fun DoubleArray.toPcmFloat32Le(): ByteArray {
    val bytes = ByteArray(size * 4)
    forEachIndexed { index, sample ->
        val bits = sample.coerceIn(-1.0, 1.0).toFloat().toBits()
        val offset = index * 4
        bytes[offset] = (bits and 0xff).toByte()
        bytes[offset + 1] = ((bits shr 8) and 0xff).toByte()
        bytes[offset + 2] = ((bits shr 16) and 0xff).toByte()
        bytes[offset + 3] = ((bits shr 24) and 0xff).toByte()
    }
    return bytes
}
