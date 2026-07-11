package io.aequicor.audio.core

import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.AudioSampleFormat
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.MediaTimestamp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AudioCoreTest {
    @Test
    fun measuresPeakAndRmsForPcmS16Le() {
        val frame = audioFrame(samples = shortArrayOf(0, Short.MAX_VALUE, Short.MIN_VALUE))

        val levels = AudioLevelMeter.measure(frame)

        assertEquals(1.0, levels.peak, absoluteTolerance = 0.0001)
        assertEquals(sqrt(2.0 / 3.0), levels.rms, absoluteTolerance = 0.0001)
    }

    @Test
    fun mixesPcmS16WithGainAndMute() {
        val mixer = AudioMixer()
        val mixed = mixer.mix(
            inputs = listOf(
                AudioMixInput(audioFrame(samples = shortArrayOf(10_000, 10_000)), gain = 0.5),
                AudioMixInput(audioFrame(samples = shortArrayOf(10_000, 10_000)), muted = true),
                AudioMixInput(audioFrame(samples = shortArrayOf(5_000, -5_000))),
            ),
            outputSourceId = AudioSourceId("mixed"),
        )

        assertEquals(AudioSourceId("mixed"), mixed.sourceId)
        assertEquals(listOf(10_000, 0), mixed.audioData!!.toShortSamples())
    }

    @Test
    fun clipsMixedSamples() {
        val mixed = AudioMixer().mix(
            inputs = listOf(
                AudioMixInput(audioFrame(samples = shortArrayOf(30_000))),
                AudioMixInput(audioFrame(samples = shortArrayOf(30_000))),
            ),
            outputSourceId = AudioSourceId("mixed"),
        )

        assertEquals(listOf(Short.MAX_VALUE.toInt()), mixed.audioData!!.toShortSamples())
    }

    @Test
    fun rejectsIncompatibleFrames() {
        val mixer = AudioMixer()

        assertFailsWith<IllegalArgumentException> {
            mixer.mix(
                inputs = listOf(
                    AudioMixInput(audioFrame(samples = shortArrayOf(1), sampleRate = 48_000)),
                    AudioMixInput(audioFrame(samples = shortArrayOf(1), sampleRate = 44_100)),
                ),
                outputSourceId = AudioSourceId("mixed"),
            )
        }
    }

    @Test
    fun mixesFloat32Pcm() {
        val mixed = AudioMixer().mix(
            inputs = listOf(
                AudioMixInput(floatAudioFrame(samples = floatArrayOf(0.25f, -0.25f))),
                AudioMixInput(floatAudioFrame(samples = floatArrayOf(0.25f, 0.5f))),
            ),
            outputSourceId = AudioSourceId("mixed"),
        )

        assertEquals(listOf(0.5f, 0.25f), mixed.audioData!!.toFloatSamples())
    }

    private fun audioFrame(
        samples: ShortArray,
        sampleRate: Int = 48_000,
        sourceId: AudioSourceId = AudioSourceId("source"),
    ) = AudioFrame(
        timestamp = MediaTimestamp(0),
        sampleRate = sampleRate,
        channelCount = 1,
        sampleCount = samples.size,
        sourceId = sourceId,
        sampleFormat = AudioSampleFormat.PcmS16Le,
        audioData = samples.toPcmS16Le(),
    )

    private fun floatAudioFrame(samples: FloatArray) = AudioFrame(
        timestamp = MediaTimestamp(0),
        sampleRate = 48_000,
        channelCount = 1,
        sampleCount = samples.size,
        sourceId = AudioSourceId("source"),
        sampleFormat = AudioSampleFormat.PcmFloat32Le,
        audioData = samples.toPcmFloat32Le(),
    )
}

private fun ShortArray.toPcmS16Le(): ByteArray {
    val bytes = ByteArray(size * 2)
    forEachIndexed { index, sample ->
        val offset = index * 2
        bytes[offset] = (sample.toInt() and 0xff).toByte()
        bytes[offset + 1] = ((sample.toInt() shr 8) and 0xff).toByte()
    }
    return bytes
}

private fun FloatArray.toPcmFloat32Le(): ByteArray {
    val bytes = ByteArray(size * 4)
    forEachIndexed { index, sample ->
        val bits = sample.toBits()
        val offset = index * 4
        bytes[offset] = (bits and 0xff).toByte()
        bytes[offset + 1] = ((bits shr 8) and 0xff).toByte()
        bytes[offset + 2] = ((bits shr 16) and 0xff).toByte()
        bytes[offset + 3] = ((bits shr 24) and 0xff).toByte()
    }
    return bytes
}

private fun ByteArray.toShortSamples(): List<Int> =
    (indices step 2).map { offset ->
        val low = this[offset].toInt() and 0xff
        val high = this[offset + 1].toInt()
        ((high shl 8) or low).toShort().toInt()
    }

private fun ByteArray.toFloatSamples(): List<Float> =
    (indices step 4).map { offset ->
        val bits = (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)
        Float.fromBits(bits)
    }
