package io.aequicor.audio.windows.wasapi

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid.CLSID
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Guid.IID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.platform.win32.WinNT.HRESULT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.atomic.AtomicBoolean

internal class JnaWasapiLoopbackClientFactory(
    private val ole32: Ole32 = Ole32.INSTANCE,
) : WasapiLoopbackClientFactory {
    override fun defaultEndpoint(): WasapiEndpoint? {
        val apartment = ComApartment.open(ole32)
        var enumerator: MmDeviceEnumerator? = null
        var device: MmDevice? = null
        var audioClient: AudioClient? = null
        try {
            enumerator = createEnumerator(ole32)
            device = enumerator.defaultRenderEndpoint() ?: return null
            audioClient = device.activateAudioClient()
            val format = audioClient.readMixFormat(ole32)
            return WasapiEndpoint(
                id = DEFAULT_WASAPI_SOURCE_ID,
                displayName = "Default system output (WASAPI)",
                sampleRate = format.sampleRate,
                channelCount = format.outputChannelCount,
            )
        } finally {
            audioClient.releaseQuietly()
            device.releaseQuietly()
            enumerator.releaseQuietly()
            apartment.close()
        }
    }

    override fun endpoints(): List<WasapiEndpoint> {
        val apartment = ComApartment.open(ole32)
        var enumerator: MmDeviceEnumerator? = null
        var defaultDevice: MmDevice? = null
        var collection: MmDeviceCollection? = null
        try {
            enumerator = createEnumerator(ole32)
            defaultDevice = enumerator.defaultRenderEndpoint()
            val defaultNativeId = defaultDevice?.endpointId(ole32)
            defaultDevice.releaseQuietly()
            defaultDevice = null
            collection = enumerator.activeRenderEndpoints()
            return buildList {
                repeat(collection.count()) { index ->
                    var device: MmDevice? = null
                    var audioClient: AudioClient? = null
                    try {
                        device = collection.item(index)
                        val nativeId = device.endpointId(ole32)
                        val friendlyName = runCatching { device.friendlyName() }.getOrNull()
                        audioClient = device.activateAudioClient()
                        val format = audioClient.readMixFormat(ole32)
                        add(
                            WasapiEndpoint(
                                id = io.aequicor.capture.core.AudioSourceId(
                                    WASAPI_ENDPOINT_SOURCE_PREFIX + nativeId,
                                ),
                                displayName = wasapiEndpointDisplayName(
                                    friendlyName = friendlyName,
                                    index = index,
                                    isDefault = nativeId == defaultNativeId,
                                ),
                                sampleRate = format.sampleRate,
                                channelCount = format.outputChannelCount,
                            ),
                        )
                    } catch (_: WasapiFailure) {
                        // Skip active endpoints whose mix format cannot be captured.
                    } finally {
                        audioClient.releaseQuietly()
                        device.releaseQuietly()
                    }
                }
            }
        } finally {
            collection.releaseQuietly()
            defaultDevice.releaseQuietly()
            enumerator.releaseQuietly()
            apartment.close()
        }
    }

    override fun open(sourceId: io.aequicor.capture.core.AudioSourceId): WasapiLoopbackClient {
        val apartment = ComApartment.open(ole32)
        var enumerator: MmDeviceEnumerator? = null
        var device: MmDevice? = null
        var audioClient: AudioClient? = null
        var captureClient: AudioCaptureClient? = null
        try {
            enumerator = createEnumerator(ole32)
            device = when {
                sourceId == DEFAULT_WASAPI_SOURCE_ID -> enumerator.defaultRenderEndpoint()
                    ?: throw WasapiFailure("Windows has no default render audio endpoint.", E_NOT_FOUND)
                sourceId.value.startsWith(WASAPI_ENDPOINT_SOURCE_PREFIX) -> {
                    val nativeId = sourceId.value.removePrefix(WASAPI_ENDPOINT_SOURCE_PREFIX)
                    if (nativeId.isBlank()) {
                        throw WasapiFailure("WASAPI endpoint source id is empty.")
                    }
                    enumerator.device(nativeId)
                }
                else -> throw WasapiFailure("Unknown WASAPI loopback source: ${sourceId.value}.")
            }
            audioClient = device.activateAudioClient()
            val formatPointer = audioClient.getMixFormatPointer()
            val format = try {
                val parsed = parseWaveFormat(formatPointer)
                audioClient.initializeLoopback(formatPointer)
                parsed
            } finally {
                ole32.CoTaskMemFree(formatPointer)
            }
            captureClient = audioClient.getCaptureClient()
            enumerator.releaseQuietly()
            enumerator = null
            device.releaseQuietly()
            device = null
            return JnaWasapiLoopbackClient(
                apartment = apartment,
                audioClient = audioClient,
                captureClient = captureClient,
                format = format,
            )
        } catch (failure: Throwable) {
            captureClient.releaseQuietly()
            audioClient.releaseQuietly()
            device.releaseQuietly()
            enumerator.releaseQuietly()
            apartment.close()
            throw failure
        }
    }
}

private class JnaWasapiLoopbackClient(
    private val apartment: ComApartment,
    private val audioClient: AudioClient,
    private val captureClient: AudioCaptureClient,
    override val format: WasapiNativeFormat,
) : WasapiLoopbackClient {
    private var started = false
    private val closed = AtomicBoolean(false)

    override fun start() {
        check(!closed.get()) { "WASAPI client is closed." }
        if (!started) {
            checkHResult(audioClient.start(), "IAudioClient.Start")
            started = true
        }
    }

    override fun nextPacket(): WasapiAudioPacket? {
        check(!closed.get()) { "WASAPI client is closed." }
        val packetFrames = IntByReference()
        checkHResult(captureClient.getNextPacketSize(packetFrames), "IAudioCaptureClient.GetNextPacketSize")
        if (packetFrames.value <= 0) {
            return null
        }

        val data = PointerByReference()
        val frameCount = IntByReference()
        val flags = IntByReference()
        val devicePosition = LongByReference()
        val qpcPosition = LongByReference()
        checkHResult(
            captureClient.getBuffer(data, frameCount, flags, devicePosition, qpcPosition),
            "IAudioCaptureClient.GetBuffer",
        )
        if (frameCount.value <= 0) {
            return null
        }
        try {
            val silent = flags.value and AUDCLNT_BUFFERFLAGS_SILENT != 0
            val byteCount = frameCount.value.toLong() * format.blockAlign
            if (byteCount <= 0 || byteCount > Int.MAX_VALUE) {
                throw WasapiFailure("WASAPI returned an invalid packet size: $byteCount bytes.")
            }
            val bytes = if (silent) {
                null
            } else {
                data.value?.getByteArray(0, byteCount.toInt())
                    ?: throw WasapiFailure("WASAPI returned a non-silent packet without data.")
            }
            return WasapiAudioPacket(
                frameCount = frameCount.value,
                data = bytes,
                silent = silent,
                devicePosition = devicePosition.value,
            )
        } finally {
            checkHResult(captureClient.releaseBuffer(frameCount.value), "IAudioCaptureClient.ReleaseBuffer")
        }
    }

    override fun stop() {
        if (!closed.get() && started) {
            checkHResult(audioClient.stop(), "IAudioClient.Stop")
            started = false
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        if (started) {
            runCatching { checkHResult(audioClient.stop(), "IAudioClient.Stop") }
            started = false
        }
        captureClient.releaseQuietly()
        audioClient.releaseQuietly()
        apartment.close()
    }
}

private class ComApartment private constructor(
    private val ole32: Ole32,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            ole32.CoUninitialize()
        }
    }

    companion object {
        fun open(ole32: Ole32): ComApartment {
            val result = ole32.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED).toInt()
            checkHResult(result, "CoInitializeEx")
            return ComApartment(ole32)
        }
    }
}

private abstract class WasapiComObject(pointer: Pointer) : Unknown(pointer) {
    protected fun invokeHResult(vtableIndex: Int, vararg arguments: Any?): Int {
        val nativeArguments = arrayOfNulls<Any>(arguments.size + 1)
        nativeArguments[0] = pointer
        arguments.copyInto(nativeArguments, destinationOffset = 1)
        return (_invokeNativeObject(vtableIndex, nativeArguments, HRESULT::class.java) as HRESULT).toInt()
    }
}

private class MmDeviceEnumerator(pointer: Pointer) : WasapiComObject(pointer) {
    fun activeRenderEndpoints(): MmDeviceCollection {
        val collection = PointerByReference()
        checkHResult(
            invokeHResult(VTABLE_ENUM_AUDIO_ENDPOINTS, E_RENDER, DEVICE_STATE_ACTIVE, collection),
            "IMMDeviceEnumerator.EnumAudioEndpoints",
        )
        return collection.value?.let(::MmDeviceCollection)
            ?: throw WasapiFailure("IMMDeviceEnumerator returned no endpoint collection.")
    }

    fun defaultRenderEndpoint(): MmDevice? {
        val device = PointerByReference()
        val result = invokeHResult(
            VTABLE_GET_DEFAULT_AUDIO_ENDPOINT,
            E_RENDER,
            E_CONSOLE,
            device,
        )
        if (result == E_NOT_FOUND) {
            return null
        }
        checkHResult(result, "IMMDeviceEnumerator.GetDefaultAudioEndpoint")
        return device.value?.let(::MmDevice)
            ?: throw WasapiFailure("IMMDeviceEnumerator returned no default render endpoint.")
    }

    fun device(nativeId: String): MmDevice {
        val device = PointerByReference()
        checkHResult(
            invokeHResult(VTABLE_GET_DEVICE, WString(nativeId), device),
            "IMMDeviceEnumerator.GetDevice",
        )
        return device.value?.let(::MmDevice)
            ?: throw WasapiFailure("IMMDeviceEnumerator returned no requested endpoint.")
    }
}

private class MmDeviceCollection(pointer: Pointer) : WasapiComObject(pointer) {
    fun count(): Int {
        val count = IntByReference()
        checkHResult(invokeHResult(VTABLE_DEVICE_COLLECTION_GET_COUNT, count), "IMMDeviceCollection.GetCount")
        return count.value.coerceAtLeast(0)
    }

    fun item(index: Int): MmDevice {
        val device = PointerByReference()
        checkHResult(
            invokeHResult(VTABLE_DEVICE_COLLECTION_ITEM, index, device),
            "IMMDeviceCollection.Item",
        )
        return device.value?.let(::MmDevice)
            ?: throw WasapiFailure("IMMDeviceCollection returned no endpoint at index $index.")
    }
}

private class MmDevice(pointer: Pointer) : WasapiComObject(pointer) {
    fun friendlyName(): String? {
        val store = openPropertyStore()
        val value = Memory(PROPVARIANT_SIZE.toLong()).apply(Memory::clear)
        return try {
            val key = PKEY_DEVICE_FRIENDLY_NAME.apply(Structure::write)
            checkHResult(
                store.getValue(key.pointer, value),
                "IPropertyStore.GetValue(PKEY_Device_FriendlyName)",
            )
            val variantType = value.getShort(PROPVARIANT_TYPE_OFFSET).toInt() and 0xffff
            if (variantType != VT_LPWSTR) {
                null
            } else {
                value.getPointer(PROPVARIANT_VALUE_OFFSET)?.getWideString(0)?.trim()?.takeIf(String::isNotEmpty)
            }
        } finally {
            runCatching { Ole32PropertyApi.INSTANCE.PropVariantClear(value) }
            store.releaseQuietly()
        }
    }

    private fun openPropertyStore(): MmPropertyStore {
        val store = PointerByReference()
        checkHResult(
            invokeHResult(VTABLE_OPEN_PROPERTY_STORE, STGM_READ, store),
            "IMMDevice.OpenPropertyStore",
        )
        return store.value?.let(::MmPropertyStore)
            ?: throw WasapiFailure("IMMDevice returned no property store.")
    }

    fun endpointId(ole32: Ole32): String {
        val id = PointerByReference()
        checkHResult(invokeHResult(VTABLE_GET_ID, id), "IMMDevice.GetId")
        val pointer = id.value ?: throw WasapiFailure("IMMDevice returned no endpoint id.")
        return try {
            pointer.getWideString(0)
        } finally {
            ole32.CoTaskMemFree(pointer)
        }
    }

    fun activateAudioClient(): AudioClient {
        val audioClient = PointerByReference()
        checkHResult(
            invokeHResult(
                VTABLE_ACTIVATE,
                IID_IAUDIO_CLIENT,
                WTypes.CLSCTX_ALL,
                Pointer.NULL,
                audioClient,
            ),
            "IMMDevice.Activate(IAudioClient)",
        )
        return audioClient.value?.let(::AudioClient)
            ?: throw WasapiFailure("IMMDevice returned no IAudioClient interface.")
    }
}

private class MmPropertyStore(pointer: Pointer) : WasapiComObject(pointer) {
    fun getValue(propertyKey: Pointer, value: Pointer): Int =
        invokeHResult(VTABLE_PROPERTY_STORE_GET_VALUE, propertyKey, value)
}

@Structure.FieldOrder("formatId", "propertyId")
internal class PropertyKey(
    formatId: GUID,
    propertyId: Int,
) : Structure() {
    @JvmField
    var formatId: GUID = formatId

    @JvmField
    var propertyId: Int = propertyId
}

internal interface Ole32PropertyApi : StdCallLibrary {
    fun PropVariantClear(value: Pointer): HRESULT

    companion object {
        val INSTANCE: Ole32PropertyApi = Native.load("Ole32", Ole32PropertyApi::class.java)
    }
}

private class AudioClient(pointer: Pointer) : WasapiComObject(pointer) {
    fun initializeLoopback(format: Pointer) {
        checkHResult(
            invokeHResult(
                VTABLE_INITIALIZE,
                AUDCLNT_SHAREMODE_SHARED,
                AUDCLNT_STREAMFLAGS_LOOPBACK,
                LOOPBACK_BUFFER_DURATION_100NS,
                0L,
                format,
                Pointer.NULL,
            ),
            "IAudioClient.Initialize(loopback)",
        )
    }

    fun getMixFormatPointer(): Pointer {
        val format = PointerByReference()
        checkHResult(invokeHResult(VTABLE_GET_MIX_FORMAT, format), "IAudioClient.GetMixFormat")
        return format.value ?: throw WasapiFailure("IAudioClient returned no mix format.")
    }

    fun readMixFormat(ole32: Ole32): WasapiNativeFormat {
        val format = getMixFormatPointer()
        return try {
            parseWaveFormat(format)
        } finally {
            ole32.CoTaskMemFree(format)
        }
    }

    fun getCaptureClient(): AudioCaptureClient {
        val captureClient = PointerByReference()
        checkHResult(
            invokeHResult(VTABLE_GET_SERVICE, IID_IAUDIO_CAPTURE_CLIENT, captureClient),
            "IAudioClient.GetService(IAudioCaptureClient)",
        )
        return captureClient.value?.let(::AudioCaptureClient)
            ?: throw WasapiFailure("IAudioClient returned no IAudioCaptureClient interface.")
    }

    fun start(): Int = invokeHResult(VTABLE_START)

    fun stop(): Int = invokeHResult(VTABLE_STOP)
}

private class AudioCaptureClient(pointer: Pointer) : WasapiComObject(pointer) {
    fun getBuffer(
        data: PointerByReference,
        frameCount: IntByReference,
        flags: IntByReference,
        devicePosition: LongByReference,
        qpcPosition: LongByReference,
    ): Int = invokeHResult(
        VTABLE_GET_BUFFER,
        data,
        frameCount,
        flags,
        devicePosition,
        qpcPosition,
    )

    fun releaseBuffer(frameCount: Int): Int = invokeHResult(VTABLE_RELEASE_BUFFER, frameCount)

    fun getNextPacketSize(frameCount: IntByReference): Int =
        invokeHResult(VTABLE_GET_NEXT_PACKET_SIZE, frameCount)
}

private fun createEnumerator(ole32: Ole32): MmDeviceEnumerator {
    val enumerator = PointerByReference()
    val result = ole32.CoCreateInstance(
        CLSID_MM_DEVICE_ENUMERATOR,
        null,
        WTypes.CLSCTX_INPROC_SERVER,
        IID_IMM_DEVICE_ENUMERATOR,
        enumerator,
    ).toInt()
    checkHResult(result, "CoCreateInstance(MMDeviceEnumerator)")
    return enumerator.value?.let(::MmDeviceEnumerator)
        ?: throw WasapiFailure("CoCreateInstance returned no IMMDeviceEnumerator interface.")
}

private fun parseWaveFormat(pointer: Pointer): WasapiNativeFormat {
    val formatTag = pointer.getShort(WAVE_FORMAT_TAG_OFFSET).toInt() and 0xffff
    val channelCount = pointer.getShort(WAVE_CHANNEL_COUNT_OFFSET).toInt() and 0xffff
    val sampleRate = pointer.getInt(WAVE_SAMPLE_RATE_OFFSET)
    val blockAlign = pointer.getShort(WAVE_BLOCK_ALIGN_OFFSET).toInt() and 0xffff
    val bitsPerSample = pointer.getShort(WAVE_BITS_PER_SAMPLE_OFFSET).toInt() and 0xffff
    val extraSize = pointer.getShort(WAVE_EXTRA_SIZE_OFFSET).toInt() and 0xffff
    val concreteFormatTag = if (formatTag == WAVE_FORMAT_EXTENSIBLE) {
        if (extraSize < WAVE_FORMAT_EXTENSIBLE_EXTRA_SIZE) {
            throw WasapiFailure("WASAPI returned a truncated WAVEFORMATEXTENSIBLE structure.")
        }
        val subFormat = GUID(pointer.share(WAVE_SUBFORMAT_OFFSET))
        when (subFormat) {
            KSDATAFORMAT_SUBTYPE_PCM -> WAVE_FORMAT_PCM
            KSDATAFORMAT_SUBTYPE_IEEE_FLOAT -> WAVE_FORMAT_IEEE_FLOAT
            else -> throw WasapiFailure("Unsupported WASAPI subformat: ${subFormat.toGuidString()}.")
        }
    } else {
        formatTag
    }
    if (sampleRate <= 0 || channelCount <= 0 || blockAlign <= 0 || blockAlign % channelCount != 0) {
        throw WasapiFailure("WASAPI returned an invalid mix format.")
    }
    val bytesPerSample = blockAlign / channelCount
    val encoding = when (concreteFormatTag) {
        WAVE_FORMAT_PCM -> when (bytesPerSample) {
            1 -> WasapiSampleEncoding.UnsignedPcm8
            2 -> WasapiSampleEncoding.SignedPcm16
            3 -> WasapiSampleEncoding.SignedPcm24
            4 -> WasapiSampleEncoding.SignedPcm32
            else -> null
        }
        WAVE_FORMAT_IEEE_FLOAT -> WasapiSampleEncoding.FloatPcm32.takeIf {
            bytesPerSample == Float.SIZE_BYTES && bitsPerSample == Float.SIZE_BITS
        }
        else -> null
    } ?: throw WasapiFailure(
        "Unsupported WASAPI mix format: tag=$concreteFormatTag, bits=$bitsPerSample, blockAlign=$blockAlign.",
    )
    return WasapiNativeFormat(
        sampleRate = sampleRate,
        channelCount = channelCount,
        blockAlign = blockAlign,
        encoding = encoding,
    )
}

private fun checkHResult(result: Int, operation: String) {
    if (COMUtils.FAILED(result)) {
        val hex = Integer.toUnsignedString(result, 16).padStart(8, '0')
        throw WasapiFailure("$operation failed with HRESULT 0x$hex.", result)
    }
}

internal fun wasapiEndpointDisplayName(
    friendlyName: String?,
    index: Int,
    isDefault: Boolean,
): String {
    val baseName = friendlyName?.trim()?.takeIf(String::isNotEmpty) ?: "System output ${index + 1}"
    return if (isDefault) {
        "$baseName (default, WASAPI)"
    } else {
        "$baseName (WASAPI)"
    }
}

private fun Unknown?.releaseQuietly() {
    if (this != null) {
        runCatching { Release() }
    }
}

private val CLSID_MM_DEVICE_ENUMERATOR = CLSID("{BCDE0395-E52F-467C-8E3D-C4579291692E}")
private val IID_IMM_DEVICE_ENUMERATOR = IID("{A95664D2-9614-4F35-A746-DE8DB63617E6}")
private val IID_IAUDIO_CLIENT = IID("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}")
private val IID_IAUDIO_CAPTURE_CLIENT = IID("{C8ADBD64-E71E-48A0-A4DE-185C395CD317}")
private val KSDATAFORMAT_SUBTYPE_PCM = GUID("{00000001-0000-0010-8000-00AA00389B71}")
private val KSDATAFORMAT_SUBTYPE_IEEE_FLOAT = GUID("{00000003-0000-0010-8000-00AA00389B71}")
private val PKEY_DEVICE_FRIENDLY_NAME = PropertyKey(
    formatId = GUID("{A45C254E-DF1C-4EFD-8020-67D146A850E0}"),
    propertyId = 14,
)

private const val E_RENDER = 0
private const val E_CONSOLE = 0
private const val E_NOT_FOUND = 0x80070490.toInt()
private const val DEVICE_STATE_ACTIVE = 0x00000001
private const val STGM_READ = 0
private const val VT_LPWSTR = 31
private const val AUDCLNT_SHAREMODE_SHARED = 0
private const val AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000
private const val AUDCLNT_BUFFERFLAGS_SILENT = 0x00000002
private const val LOOPBACK_BUFFER_DURATION_100NS = 10_000_000L

private const val VTABLE_ENUM_AUDIO_ENDPOINTS = 3
private const val VTABLE_GET_DEFAULT_AUDIO_ENDPOINT = 4
private const val VTABLE_GET_DEVICE = 5
private const val VTABLE_ACTIVATE = 3
private const val VTABLE_OPEN_PROPERTY_STORE = 4
private const val VTABLE_GET_ID = 5
private const val VTABLE_DEVICE_COLLECTION_GET_COUNT = 3
private const val VTABLE_DEVICE_COLLECTION_ITEM = 4
private const val VTABLE_PROPERTY_STORE_GET_VALUE = 5
private const val VTABLE_INITIALIZE = 3
private const val VTABLE_GET_MIX_FORMAT = 8
private const val VTABLE_START = 10
private const val VTABLE_STOP = 11
private const val VTABLE_GET_SERVICE = 14
private const val VTABLE_GET_BUFFER = 3
private const val VTABLE_RELEASE_BUFFER = 4
private const val VTABLE_GET_NEXT_PACKET_SIZE = 5

private const val WAVE_FORMAT_PCM = 0x0001
private const val WAVE_FORMAT_IEEE_FLOAT = 0x0003
private const val WAVE_FORMAT_EXTENSIBLE = 0xfffe
private const val WAVE_FORMAT_EXTENSIBLE_EXTRA_SIZE = 22
private const val WAVE_FORMAT_TAG_OFFSET = 0L
private const val WAVE_CHANNEL_COUNT_OFFSET = 2L
private const val WAVE_SAMPLE_RATE_OFFSET = 4L
private const val WAVE_BLOCK_ALIGN_OFFSET = 12L
private const val WAVE_BITS_PER_SAMPLE_OFFSET = 14L
private const val WAVE_EXTRA_SIZE_OFFSET = 16L
private const val WAVE_SUBFORMAT_OFFSET = 24L
private const val PROPVARIANT_TYPE_OFFSET = 0L
private const val PROPVARIANT_VALUE_OFFSET = 8L
private val PROPVARIANT_SIZE = 8 + (Native.POINTER_SIZE * 2)
