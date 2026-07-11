package io.aequicor.audio.windows.wasapi

import io.aequicor.capture.core.AudioSampleFormat
import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.platform.AudioSourceRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsWasapiAudioAdaptersTest {
    @Test
    fun formatsFriendlyEndpointNameAndDefaultMarker() {
        assertEquals(
            "Speakers (Realtek USB Audio) (default, WASAPI)",
            wasapiEndpointDisplayName(
                friendlyName = "  Speakers (Realtek USB Audio)  ",
                index = 0,
                isDefault = true,
            ),
        )
    }

    @Test
    fun fallsBackToStableOrdinalWhenFriendlyNameIsUnavailable() {
        assertEquals(
            "System output 2 (WASAPI)",
            wasapiEndpointDisplayName(friendlyName = null, index = 1, isDefault = false),
        )
    }

    @Test
    fun discoversAllActiveLoopbackEndpoints() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val endpoints = listOf(
            WasapiEndpoint(io.aequicor.capture.core.AudioSourceId("wasapi:loopback:endpoint:speakers"), "Speakers", 48_000, 2),
            WasapiEndpoint(io.aequicor.capture.core.AudioSourceId("wasapi:loopback:endpoint:headset"), "Headset", 44_100, 2),
        )
        val repository = WindowsWasapiAudioSourceRepository(
            FakeWasapiClientFactory(availableEndpoints = endpoints),
            dispatcher,
        )

        val sources = repository.listAudioSources(AudioSourceRequest(includeMicrophones = false))

        assertEquals(endpoints.map { endpoint -> endpoint.id }, sources.map { source -> source.id })
        assertEquals(listOf("Speakers", "Headset"), sources.map { source -> source.displayName })
    }

    @Test
    fun discoversDefaultLoopbackEndpoint() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val endpoint = WasapiEndpoint(DEFAULT_WASAPI_SOURCE_ID, "Speakers", 48_000, 2)
        val repository = WindowsWasapiAudioSourceRepository(FakeWasapiClientFactory(endpoint = endpoint), dispatcher)

        val sources = repository.listAudioSources(AudioSourceRequest(includeMicrophones = false))

        assertEquals(
            listOf(AudioSource.SystemLoopback(DEFAULT_WASAPI_SOURCE_ID, "Speakers", 48_000, 2)),
            sources,
        )
    }

    @Test
    fun convertsFloatPacketAndReleasesClientOnCancellation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val format = WasapiNativeFormat(48_000, 2, 8, WasapiSampleEncoding.FloatPcm32)
        val client = FakeWasapiClient(
            format = format,
            packets = ArrayDeque(
                listOf(
                    WasapiAudioPacket(
                        frameCount = 1,
                        data = floatArrayOf(0.5f, -0.5f).toLittleEndianBytes(),
                        silent = false,
                        devicePosition = 0,
                    ),
                ),
            ),
        )
        val adapter = WindowsWasapiAudioCaptureAdapter(
            clientFactory = FakeWasapiClientFactory(client = client),
            dispatcher = dispatcher,
            clock = WasapiTimestampClock { 0L },
        )
        val source = AudioSource.SystemLoopback(DEFAULT_WASAPI_SOURCE_ID, "System", 48_000, 2)

        val frame = adapter.frames(settings(source)).first()

        assertEquals(AudioSampleFormat.PcmS16Le, frame.sampleFormat)
        assertContentEquals(shortArrayOf(16_384, -16_383).toLittleEndianBytes(), frame.audioData)
        assertTrue(client.started)
        assertTrue(client.stopped)
        assertTrue(client.closed)
    }

    @Test
    fun opensTheExplicitlySelectedEndpoint() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val selectedId = io.aequicor.capture.core.AudioSourceId("wasapi:loopback:endpoint:headset")
        val client = FakeWasapiClient(
            format = WasapiNativeFormat(48_000, 2, 8, WasapiSampleEncoding.FloatPcm32),
            packets = ArrayDeque(
                listOf(WasapiAudioPacket(1, floatArrayOf(0f, 0f).toLittleEndianBytes(), false, 0)),
            ),
        )
        val factory = FakeWasapiClientFactory(client = client)
        val adapter = WindowsWasapiAudioCaptureAdapter(
            clientFactory = factory,
            dispatcher = dispatcher,
            clock = WasapiTimestampClock { 0L },
        )

        adapter.frames(
            settings(AudioSource.SystemLoopback(selectedId, "Headset", 48_000, 2)),
        ).first()

        assertEquals(selectedId, factory.lastOpenedSourceId)
    }

    @Test
    fun emitsSilenceWhenLoopbackHasNoPackets() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = FakeWasapiClient(
            format = WasapiNativeFormat(48_000, 2, 8, WasapiSampleEncoding.FloatPcm32),
        )
        val timestamps = ArrayDeque(listOf(0L, 30_000_000L))
        val adapter = WindowsWasapiAudioCaptureAdapter(
            clientFactory = FakeWasapiClientFactory(client = client),
            dispatcher = dispatcher,
            clock = WasapiTimestampClock { timestamps.removeFirst() },
        )

        val frame = adapter.frames(
            settings(AudioSource.SystemLoopback(DEFAULT_WASAPI_SOURCE_ID, "System", 48_000, 2)),
        ).first()

        assertEquals(480, frame.sampleCount)
        assertTrue(requireNotNull(frame.audioData).all { byte -> byte == 0.toByte() })
    }

    @Test
    fun recognizesWindowsWithoutCreatingComObjects() {
        assertTrue(WindowsWasapiAudioAdapterFactory.isSupported("Windows 11"))
        assertFalse(WindowsWasapiAudioAdapterFactory.isSupported("Linux"))
    }
}

private class FakeWasapiClientFactory(
    private val endpoint: WasapiEndpoint? = WasapiEndpoint(DEFAULT_WASAPI_SOURCE_ID, "System", 48_000, 2),
    private val client: FakeWasapiClient? = null,
    private val availableEndpoints: List<WasapiEndpoint>? = null,
) : WasapiLoopbackClientFactory {
    var lastOpenedSourceId: io.aequicor.capture.core.AudioSourceId? = null
        private set

    override fun defaultEndpoint(): WasapiEndpoint? = endpoint

    override fun endpoints(): List<WasapiEndpoint> = availableEndpoints ?: super.endpoints()

    override fun open(sourceId: io.aequicor.capture.core.AudioSourceId): WasapiLoopbackClient {
        lastOpenedSourceId = sourceId
        return requireNotNull(client) { "Fake client is not configured." }
    }
}

private class FakeWasapiClient(
    override val format: WasapiNativeFormat,
    private val packets: ArrayDeque<WasapiAudioPacket> = ArrayDeque(),
) : WasapiLoopbackClient {
    var started = false
        private set
    var stopped = false
        private set
    var closed = false
        private set

    override fun start() {
        started = true
    }

    override fun nextPacket(): WasapiAudioPacket? = packets.removeFirstOrNull()

    override fun stop() {
        stopped = true
    }

    override fun close() {
        closed = true
    }
}

private fun settings(source: AudioSource.SystemLoopback): RecordingSettings = RecordingSettings(
    captureSource = CaptureSource.Screen(CaptureSourceId("screen"), "Screen"),
    audioSources = listOf(source),
    outputPath = "unused.mp4",
)

private fun FloatArray.toLittleEndianBytes(): ByteArray = ByteArray(size * Float.SIZE_BYTES).also { output ->
    forEachIndexed { index, sample ->
        val bits = sample.toBits()
        repeat(Float.SIZE_BYTES) { byteIndex ->
            output[index * Float.SIZE_BYTES + byteIndex] = (bits shr (byteIndex * 8) and 0xff).toByte()
        }
    }
}

private fun ShortArray.toLittleEndianBytes(): ByteArray = ByteArray(size * Short.SIZE_BYTES).also { output ->
    forEachIndexed { index, sample ->
        output[index * Short.SIZE_BYTES] = (sample.toInt() and 0xff).toByte()
        output[index * Short.SIZE_BYTES + 1] = (sample.toInt() shr 8 and 0xff).toByte()
    }
}
