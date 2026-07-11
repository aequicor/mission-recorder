package io.aequicor.audio.windows.wasapi

import kotlin.math.roundToInt

internal object WasapiPcmConverter {
    fun toPcmS16Le(
        packet: WasapiAudioPacket,
        format: WasapiNativeFormat,
        outputChannelCount: Int,
    ): ByteArray {
        require(outputChannelCount in 1..2) { "WASAPI output must be mono or stereo." }
        require(packet.frameCount >= 0) { "WASAPI packet frame count must not be negative." }
        val output = ByteArray(packet.frameCount * outputChannelCount * Short.SIZE_BYTES)
        if (packet.silent || packet.frameCount == 0) {
            return output
        }
        val input = requireNotNull(packet.data) { "Non-silent WASAPI packet has no data." }
        val requiredBytes = packet.frameCount.toLong() * format.blockAlign
        require(requiredBytes <= Int.MAX_VALUE && input.size >= requiredBytes.toInt()) {
            "WASAPI packet is shorter than its frame metadata."
        }

        repeat(packet.frameCount) { frameIndex ->
            val first = format.readSample(input, frameIndex, 0)
            val second = if (format.channelCount > 1) {
                format.readSample(input, frameIndex, 1)
            } else {
                first
            }
            if (outputChannelCount == 1) {
                output.writeSample(frameIndex, (first + second) / 2.0)
            } else {
                val outputOffset = frameIndex * 2
                output.writeSample(outputOffset, first)
                output.writeSample(outputOffset + 1, second)
            }
        }
        return output
    }
}

private fun WasapiNativeFormat.readSample(data: ByteArray, frameIndex: Int, channelIndex: Int): Double {
    val bytesPerSample = blockAlign / channelCount
    val offset = frameIndex * blockAlign + channelIndex * bytesPerSample
    return when (encoding) {
        WasapiSampleEncoding.UnsignedPcm8 -> ((data[offset].toInt() and 0xff) - 128) / 127.0
        WasapiSampleEncoding.SignedPcm16 -> data.readSigned16(offset) / Short.MAX_VALUE.toDouble()
        WasapiSampleEncoding.SignedPcm24 -> data.readSigned24(offset) / 8_388_607.0
        WasapiSampleEncoding.SignedPcm32 -> data.readSigned32(offset) / Int.MAX_VALUE.toDouble()
        WasapiSampleEncoding.FloatPcm32 -> Float.fromBits(data.readSigned32(offset)).toDouble()
    }.coerceIn(-1.0, 1.0)
}

private fun ByteArray.writeSample(sampleIndex: Int, normalized: Double) {
    val sample = (normalized.coerceIn(-1.0, 1.0) * Short.MAX_VALUE)
        .roundToInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    val offset = sampleIndex * Short.SIZE_BYTES
    this[offset] = (sample and 0xff).toByte()
    this[offset + 1] = (sample shr 8 and 0xff).toByte()
}

private fun ByteArray.readSigned16(offset: Int): Int =
    ((this[offset].toInt() and 0xff) or (this[offset + 1].toInt() shl 8)).toShort().toInt()

private fun ByteArray.readSigned24(offset: Int): Int {
    val value = (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8) or
        ((this[offset + 2].toInt() and 0xff) shl 16)
    return if (value and 0x0080_0000 != 0) value or -0x0100_0000 else value
}

private fun ByteArray.readSigned32(offset: Int): Int =
    (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8) or
        ((this[offset + 2].toInt() and 0xff) shl 16) or
        (this[offset + 3].toInt() shl 24)
