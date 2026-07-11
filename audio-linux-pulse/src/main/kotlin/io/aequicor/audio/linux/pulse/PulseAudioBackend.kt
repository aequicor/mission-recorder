package io.aequicor.audio.linux.pulse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.InputStream

internal data class PulseMonitorDevice(
    val name: String,
    val description: String,
    val sampleRate: Int,
    val channelCount: Int,
)

internal interface PulseAudioBackend {
    fun listMonitorDevices(): List<PulseMonitorDevice>

    fun openCapture(deviceName: String, sampleRate: Int, channelCount: Int): PulseCaptureProcess
}

internal interface PulseCaptureProcess : AutoCloseable {
    val inputStream: InputStream
    fun exitCodeOrNull(): Int?
}

internal class CommandPulseAudioBackend(
    private val pactlCommand: String,
    private val parecCommand: String,
) : PulseAudioBackend {
    override fun listMonitorDevices(): List<PulseMonitorDevice> {
        val process = ProcessBuilder(pactlCommand, "--format=json", "list", "sources")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("pactl failed with exit code $exitCode: ${output.trim()}")
        }
        return parsePulseMonitorDevices(output)
    }

    override fun openCapture(
        deviceName: String,
        sampleRate: Int,
        channelCount: Int,
    ): PulseCaptureProcess {
        val process = ProcessBuilder(
            parecCommand,
            "--raw",
            "--device=$deviceName",
            "--format=s16le",
            "--rate=$sampleRate",
            "--channels=$channelCount",
            "--client-name=Mission Recorder",
            "--stream-name=System Audio Capture",
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        return JvmPulseCaptureProcess(process)
    }
}

private class JvmPulseCaptureProcess(
    private val process: Process,
) : PulseCaptureProcess {
    override val inputStream: InputStream = process.inputStream

    override fun exitCodeOrNull(): Int? = if (process.isAlive) null else process.exitValue()

    override fun close() {
        runCatching { inputStream.close() }
        if (process.isAlive) {
            process.destroy()
        }
        if (process.isAlive) {
            process.destroyForcibly()
        }
    }
}

internal fun parsePulseMonitorDevices(payload: String): List<PulseMonitorDevice> {
    val root = Json.parseToJsonElement(payload) as? JsonArray
        ?: error("pactl JSON response is not an array.")
    return root.mapNotNull { element ->
        val source = element as? JsonObject ?: return@mapNotNull null
        val name = source.string("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val monitorOfSink = source["monitor_of_sink"]
        val isMonitor = (monitorOfSink != null && monitorOfSink !is kotlinx.serialization.json.JsonNull) ||
            name.endsWith(".monitor")
        if (!isMonitor) {
            return@mapNotNull null
        }
        val (sampleRate, channelCount) = source.sampleFormat() ?: return@mapNotNull null
        PulseMonitorDevice(
            name = name,
            description = source.string("description")?.takeIf(String::isNotBlank) ?: name,
            sampleRate = sampleRate,
            channelCount = channelCount,
        )
    }.distinctBy(PulseMonitorDevice::name)
}

private fun JsonObject.sampleFormat(): Pair<Int, Int>? {
    val structured = this["sample_spec"] as? JsonObject
    val structuredRate = structured?.int("rate")
    val structuredChannels = structured?.int("channels")
    if (structuredRate != null && structuredChannels != null && structuredRate > 0 && structuredChannels > 0) {
        return structuredRate to structuredChannels
    }
    val specification = string("sample_specification") ?: return null
    val match = SAMPLE_SPECIFICATION.find(specification) ?: return null
    val channelCount = match.groupValues[1].toIntOrNull() ?: return null
    val sampleRate = match.groupValues[2].toIntOrNull() ?: return null
    return if (sampleRate > 0 && channelCount > 0) sampleRate to channelCount else null
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(name: String): Int? =
    (this[name] as? JsonPrimitive)?.intOrNull

private val SAMPLE_SPECIFICATION = Regex("""(\d+)ch\s+(\d+)Hz""")
