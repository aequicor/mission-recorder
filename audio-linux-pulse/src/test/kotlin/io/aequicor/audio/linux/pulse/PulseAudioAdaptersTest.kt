package io.aequicor.audio.linux.pulse

import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.platform.AudioSourceRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PulseAudioAdaptersTest {
    @Test
    fun parsesOnlyMonitorSourcesFromPactlJson() {
        val devices = parsePulseMonitorDevices(
            """
            [
              {
                "name": "alsa_output.pci.stereo.monitor",
                "description": "Monitor of Built-in Audio",
                "sample_specification": "s16le 2ch 48000Hz",
                "monitor_of_sink": "alsa_output.pci.stereo"
              },
              {
                "name": "alsa_input.pci.analog-stereo",
                "description": "Built-in Microphone",
                "sample_specification": "s16le 2ch 48000Hz",
                "monitor_of_sink": null
              },
              {
                "name": "virtual_output.monitor",
                "description": "Virtual Output Monitor",
                "sample_spec": { "format": "float32le", "channels": 2, "rate": 44100 },
                "monitor_of_sink": 7
              }
            ]
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                PulseMonitorDevice(
                    name = "alsa_output.pci.stereo.monitor",
                    description = "Monitor of Built-in Audio",
                    sampleRate = 48_000,
                    channelCount = 2,
                ),
                PulseMonitorDevice(
                    name = "virtual_output.monitor",
                    description = "Virtual Output Monitor",
                    sampleRate = 44_100,
                    channelCount = 2,
                ),
            ),
            devices,
        )
    }

    @Test
    fun repositoryExposesStablePulseMonitorIdsAndHonorsRequestFilter() = runTest {
        val backend = FakePulseAudioBackend()
        val repository = PulseAudioSourceRepository(backend, UnconfinedTestDispatcher(testScheduler))

        assertTrue(repository.listAudioSources(AudioSourceRequest(includeSystemLoopback = false)).isEmpty())
        assertEquals(0, backend.discoveryCalls)

        val sources = repository.listAudioSources(AudioSourceRequest())

        val source = sources.single() as AudioSource.SystemLoopback
        assertEquals("pulse:monitor:test.monitor", source.id.value)
        assertEquals("Test output monitor", source.displayName)
        assertEquals(48_000, source.sampleRate)
        assertEquals(2, source.channelCount)
        assertEquals(1, backend.discoveryCalls)
    }

    @Test
    fun captureAlignsPcmFramesAndClosesProcessWhenCollectorStops() = runTest {
        val process = FakePulseCaptureProcess(
            ChunkedInputStream(
                byteArrayOf(1, 2, 3),
                byteArrayOf(4, 5, 6, 7, 8),
            ),
        )
        val backend = FakePulseAudioBackend(process)
        val source = AudioSource.SystemLoopback(
            id = AudioSourceId("pulse:monitor:test.monitor"),
            displayName = "Test output monitor",
            sampleRate = 48_000,
            channelCount = 2,
        )
        val adapter = PulseAudioCaptureAdapter(backend, UnconfinedTestDispatcher(testScheduler))

        val frame = adapter.frames(
            RecordingSettings(
                captureSource = CaptureSource.Screen(CaptureSourceId("screen:test"), "Test screen"),
                audioSources = listOf(source),
                outputPath = "test.mp4",
            ),
        ).first()

        assertEquals(0, frame.timestamp.nanoseconds)
        assertEquals(2, frame.sampleCount)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), frame.audioData)
        assertEquals("test.monitor", backend.openedDeviceName)
        assertTrue(process.closed)
    }

    @Test
    fun factoryRemainsUnavailableOutsideLinux() {
        assertFalse(LinuxPulseAudioAdapterFactory.isSupported(osName = "Windows 11", path = ""))
        assertFalse(LinuxPulseAudioAdapterFactory.isSupported(osName = "Mac OS X", path = ""))
    }
}

private class FakePulseAudioBackend(
    private val process: PulseCaptureProcess? = null,
) : PulseAudioBackend {
    var discoveryCalls = 0
        private set
    var openedDeviceName: String? = null
        private set

    override fun listMonitorDevices(): List<PulseMonitorDevice> {
        discoveryCalls += 1
        return listOf(PulseMonitorDevice("test.monitor", "Test output monitor", 48_000, 2))
    }

    override fun openCapture(deviceName: String, sampleRate: Int, channelCount: Int): PulseCaptureProcess {
        openedDeviceName = deviceName
        return requireNotNull(process)
    }
}

private class FakePulseCaptureProcess(
    override val inputStream: InputStream,
) : PulseCaptureProcess {
    var closed = false
        private set

    override fun exitCodeOrNull(): Int? = null

    override fun close() {
        closed = true
        inputStream.close()
    }
}

private class ChunkedInputStream(
    private vararg val chunks: ByteArray,
) : InputStream() {
    private var index = 0

    override fun read(): Int = error("Single-byte reads are not used.")

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (index >= chunks.size) {
            return -1
        }
        val chunk = chunks[index++]
        chunk.copyInto(buffer, destinationOffset = offset)
        return chunk.size
    }
}
