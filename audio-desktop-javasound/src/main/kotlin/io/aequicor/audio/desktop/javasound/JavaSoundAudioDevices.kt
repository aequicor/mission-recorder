package io.aequicor.audio.desktop.javasound

import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.RecordingError
import io.aequicor.capture.core.RecordingException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine

data class JavaSoundPcmFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val sampleSizeBits: Int = 16,
    val signed: Boolean = true,
    val bigEndian: Boolean = false,
) {
    init {
        require(sampleRate > 0) { "Sample rate must be positive." }
        require(channelCount > 0) { "Channel count must be positive." }
        require(sampleSizeBits == 16) { "Java Sound adapter currently supports 16-bit PCM only." }
        require(signed) { "Java Sound adapter currently supports signed PCM only." }
        require(!bigEndian) { "Java Sound adapter currently supports little-endian PCM only." }
    }

    val bytesPerFrame: Int = channelCount * (sampleSizeBits / 8)
}

data class JavaSoundAudioDeviceDescriptor(
    val id: AudioSourceId,
    val displayName: String,
    val format: JavaSoundPcmFormat,
)

interface JavaSoundTargetLine : AutoCloseable {
    val format: JavaSoundPcmFormat

    fun start()
    fun stop()
    fun available(): Int
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    override fun close()
}

interface JavaSoundAudioDeviceProvider {
    fun listTargetDevices(): List<JavaSoundAudioDeviceDescriptor>

    fun openTargetLine(
        sourceId: AudioSourceId,
        format: JavaSoundPcmFormat,
        bufferSizeBytes: Int,
    ): JavaSoundTargetLine
}

class SystemJavaSoundAudioDeviceProvider(
    private val formatPreferences: List<JavaSoundPcmFormat> = defaultFormatPreferences,
) : JavaSoundAudioDeviceProvider {
    override fun listTargetDevices(): List<JavaSoundAudioDeviceDescriptor> =
        AudioSystem.getMixerInfo().mapIndexedNotNull { index, mixerInfo ->
            val mixer = runCatching { AudioSystem.getMixer(mixerInfo) }.getOrNull()
                ?: return@mapIndexedNotNull null
            val format = preferredFormatFor(mixer) ?: return@mapIndexedNotNull null
            JavaSoundAudioDeviceDescriptor(
                id = mixerInfo.audioSourceId(index),
                displayName = mixerInfo.displayName(),
                format = format,
            )
        }

    override fun openTargetLine(
        sourceId: AudioSourceId,
        format: JavaSoundPcmFormat,
        bufferSizeBytes: Int,
    ): JavaSoundTargetLine {
        val mixerInfo = AudioSystem.getMixerInfo()
            .mapIndexedNotNull { index, info -> info.takeIf { info.audioSourceId(index) == sourceId } }
            .firstOrNull()
            ?: throw sourceUnavailable("Audio input device is unavailable: ${sourceId.value}")
        val mixer = runCatching { AudioSystem.getMixer(mixerInfo) }
            .getOrElse { throw sourceUnavailable("Audio input device cannot be opened: ${sourceId.value}") }
        val audioFormat = format.toAudioFormat()
        val lineInfo = DataLine.Info(TargetDataLine::class.java, audioFormat)
        if (!mixer.isLineSupported(lineInfo)) {
            throw sourceUnavailable("Audio input device no longer supports ${format.sampleRate} Hz PCM.")
        }
        val line = try {
            mixer.getLine(lineInfo) as TargetDataLine
        } catch (exception: IllegalArgumentException) {
            throw sourceUnavailable(exception.message ?: "Audio input line is unavailable.")
        }
        try {
            line.open(audioFormat, bufferSizeBytes)
        } catch (exception: LineUnavailableException) {
            throw sourceUnavailable(exception.message ?: "Audio input line is unavailable.")
        }
        return TargetDataLineHandle(line = line, format = format)
    }

    private fun preferredFormatFor(mixer: Mixer): JavaSoundPcmFormat? =
        formatPreferences.firstOrNull { format ->
            mixer.isLineSupported(DataLine.Info(TargetDataLine::class.java, format.toAudioFormat()))
        } ?: mixer.targetLineInfo
            .filterIsInstance<DataLine.Info>()
            .flatMap { it.formats.toList() }
            .mapNotNull { it.toJavaSoundPcmFormatOrNull() }
            .distinct()
            .firstOrNull { format ->
                mixer.isLineSupported(DataLine.Info(TargetDataLine::class.java, format.toAudioFormat()))
            }
}

private class TargetDataLineHandle(
    private val line: TargetDataLine,
    override val format: JavaSoundPcmFormat,
) : JavaSoundTargetLine {
    override fun start() {
        line.start()
    }

    override fun stop() {
        line.stop()
    }

    override fun available(): Int = line.available()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = line.read(buffer, offset, length)

    override fun close() {
        line.close()
    }
}

private val defaultFormatPreferences = listOf(
    JavaSoundPcmFormat(sampleRate = 48_000, channelCount = 2),
    JavaSoundPcmFormat(sampleRate = 48_000, channelCount = 1),
    JavaSoundPcmFormat(sampleRate = 44_100, channelCount = 2),
    JavaSoundPcmFormat(sampleRate = 44_100, channelCount = 1),
)

private fun JavaSoundPcmFormat.toAudioFormat(): AudioFormat =
    AudioFormat(
        sampleRate.toFloat(),
        sampleSizeBits,
        channelCount,
        signed,
        bigEndian,
    )

private fun AudioFormat.toJavaSoundPcmFormatOrNull(): JavaSoundPcmFormat? {
    if (encoding != AudioFormat.Encoding.PCM_SIGNED) {
        return null
    }
    if (sampleRate <= 0 || sampleSizeInBits != 16 || channels <= 0 || isBigEndian) {
        return null
    }
    return JavaSoundPcmFormat(
        sampleRate = sampleRate.toInt(),
        channelCount = channels,
        sampleSizeBits = sampleSizeInBits,
        signed = true,
        bigEndian = false,
    )
}

private fun Mixer.Info.audioSourceId(index: Int): AudioSourceId {
    val fingerprint = listOf(name, vendor, version, description).joinToString(separator = "|")
    return AudioSourceId("javasound:target:$index:${Integer.toUnsignedString(fingerprint.hashCode(), 16)}")
}

private fun Mixer.Info.displayName(): String {
    val parts = listOf(name, description)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
    return parts.joinToString(separator = " - ").ifBlank { "Java Sound input" }
}

private fun sourceUnavailable(message: String): RecordingException =
    RecordingException(RecordingError.SourceUnavailable(message))
