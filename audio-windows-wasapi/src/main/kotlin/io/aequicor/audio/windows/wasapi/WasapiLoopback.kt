package io.aequicor.audio.windows.wasapi

import io.aequicor.capture.core.AudioSourceId

internal data class WasapiEndpoint(
    val id: AudioSourceId,
    val displayName: String,
    val sampleRate: Int,
    val channelCount: Int,
)

internal enum class WasapiSampleEncoding {
    UnsignedPcm8,
    SignedPcm16,
    SignedPcm24,
    SignedPcm32,
    FloatPcm32,
}

internal data class WasapiNativeFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val blockAlign: Int,
    val encoding: WasapiSampleEncoding,
)

internal data class WasapiAudioPacket(
    val frameCount: Int,
    val data: ByteArray?,
    val silent: Boolean,
    val devicePosition: Long,
)

internal interface WasapiLoopbackClient : AutoCloseable {
    val format: WasapiNativeFormat

    fun start()

    fun nextPacket(): WasapiAudioPacket?

    fun stop()
}

internal interface WasapiLoopbackClientFactory {
    fun defaultEndpoint(): WasapiEndpoint?

    fun endpoints(): List<WasapiEndpoint> = listOfNotNull(defaultEndpoint())

    fun open(sourceId: AudioSourceId): WasapiLoopbackClient
}

internal class WasapiFailure(
    message: String,
    val hresult: Int? = null,
) : RuntimeException(message)

internal val WasapiNativeFormat.outputChannelCount: Int
    get() = channelCount.coerceIn(1, 2)

internal val DEFAULT_WASAPI_SOURCE_ID = AudioSourceId("wasapi:loopback:default")
internal const val WASAPI_ENDPOINT_SOURCE_PREFIX = "wasapi:loopback:endpoint:"
