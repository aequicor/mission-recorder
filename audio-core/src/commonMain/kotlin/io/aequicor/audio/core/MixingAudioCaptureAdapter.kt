package io.aequicor.audio.core

import io.aequicor.capture.core.AudioCaptureAdapter
import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.AudioOutputFormat
import io.aequicor.capture.core.AudioSampleFormat
import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.MediaTimestamp
import io.aequicor.capture.core.RecordingError
import io.aequicor.capture.core.RecordingException
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.audioOutputFormat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class AudioCaptureRoute(
    val matches: (AudioSource) -> Boolean,
    val adapter: AudioCaptureAdapter,
)

class MixingAudioCaptureAdapter(
    private val routes: List<AudioCaptureRoute>,
) : AudioCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<AudioFrame> {
        val sources = settings.audioSources.distinctBy { source -> source.id }
        if (sources.isEmpty()) {
            return emptyFlow()
        }
        val adapters = sources.associateWith { source ->
            routes.firstOrNull { route -> route.matches(source) }?.adapter
                ?: throw RecordingException(
                    RecordingError.SourceUnavailable("No audio capture adapter supports source ${source.id.value}."),
                )
        }
        if (sources.size == 1) {
            val source = sources.single()
            return adapters.getValue(source)
                .frames(settings.copy(audioSources = sources))
                .map { frame -> frame.withGain(source.gain) }
        }
        return mix(settings, sources, adapters)
    }

    private fun mix(
        settings: RecordingSettings,
        sources: List<AudioSource>,
        adapters: Map<AudioSource, AudioCaptureAdapter>,
    ): Flow<AudioFrame> = channelFlow {
        val outputFormat = requireNotNull(settings.audioOutputFormat())
        val incoming = Channel<AudioFrame>(capacity = Channel.BUFFERED)
        sources.forEach { source ->
            launch {
                adapters.getValue(source)
                    .frames(settings.copy(audioSources = listOf(source)))
                    .collect(incoming::send)
                throw RecordingException(
                    RecordingError.SourceUnavailable("Audio source ${source.displayName} stopped unexpectedly."),
                )
            }
        }

        val normalizers = sources.associate { source ->
            source.id to PcmFrameNormalizer(outputFormat)
        }
        val mixer = PcmTimelineMixer(
            sourceIds = sources.mapTo(linkedSetOf(), AudioSource::id),
            sourceGains = sources.associate { source -> source.id to source.gain },
            outputFormat = outputFormat,
            outputSourceId = MIXED_AUDIO_SOURCE_ID,
        )
        while (currentCoroutineContext().isActive) {
            val frame = incoming.receive()
            val normalized = normalizers.getValue(frame.sourceId).normalize(frame)
            mixer.add(normalized).forEach { mixed -> send(mixed) }
        }
    }
}

private class PcmFrameNormalizer(
    private val outputFormat: AudioOutputFormat,
) {
    private var inputFramesSeen = 0L
    private var outputFramesProduced = 0L
    private var inputSampleRate: Int? = null

    fun normalize(frame: AudioFrame): AudioFrame {
        validate(frame)
        val sourceRate = inputSampleRate ?: frame.sampleRate.also { inputSampleRate = it }
        require(sourceRate == frame.sampleRate) { "Audio source sample rate changed during capture." }
        val inputEnd = inputFramesSeen + frame.sampleCount
        val outputEnd = inputEnd * outputFormat.sampleRate / sourceRate
        val outputFrameCount = (outputEnd - outputFramesProduced).toInt()
        val output = ByteArray(outputFrameCount * outputFormat.channelCount * Short.SIZE_BYTES)
        for (outputFrame in 0 until outputFrameCount) {
            val globalOutputFrame = outputFramesProduced + outputFrame
            val globalInputFrame = globalOutputFrame * sourceRate / outputFormat.sampleRate
            val localInputFrame = (globalInputFrame - inputFramesSeen)
                .toInt()
                .coerceIn(0, frame.sampleCount - 1)
            writeConvertedFrame(frame, localInputFrame, output, outputFrame, outputFormat.channelCount)
        }
        inputFramesSeen = inputEnd
        outputFramesProduced = outputEnd
        return AudioFrame(
            timestamp = frame.timestamp,
            sampleRate = outputFormat.sampleRate,
            channelCount = outputFormat.channelCount,
            sampleCount = outputFrameCount,
            sourceId = frame.sourceId,
            sampleFormat = AudioSampleFormat.PcmS16Le,
            audioData = output,
        )
    }

    private fun validate(frame: AudioFrame) {
        require(frame.sampleRate > 0 && frame.channelCount > 0 && frame.sampleCount > 0) {
            "Audio frame format must be positive."
        }
        val data = requireNotNull(frame.audioData) { "Audio frame has no PCM payload." }
        require(data.size >= frame.requiredBytes()) { "Audio frame PCM payload is too short." }
    }
}

private class PcmTimelineMixer(
    sourceIds: Set<AudioSourceId>,
    private val sourceGains: Map<AudioSourceId, Double>,
    private val outputFormat: AudioOutputFormat,
    private val outputSourceId: AudioSourceId,
) {
    private val sourceWatermarks = sourceIds.associateWithTo(linkedMapOf()) { null as Long? }
    private val chunkFrames = (outputFormat.sampleRate / MIX_CHUNKS_PER_SECOND).coerceAtLeast(1)
    private val chunks = mutableMapOf<Long, LongArray>()
    private var nextChunkIndex = 0L

    fun add(frame: AudioFrame): List<AudioFrame> {
        require(frame.sampleRate == outputFormat.sampleRate)
        require(frame.channelCount == outputFormat.channelCount)
        require(frame.sampleFormat == AudioSampleFormat.PcmS16Le)
        val startFrame = frame.timestamp.nanoseconds * outputFormat.sampleRate / NANOS_PER_SECOND
        val data = requireNotNull(frame.audioData)
        repeat(frame.sampleCount) { frameIndex ->
            val globalFrame = startFrame + frameIndex
            val chunkIndex = globalFrame / chunkFrames
            if (chunkIndex < nextChunkIndex) {
                return@repeat
            }
            val frameInChunk = (globalFrame % chunkFrames).toInt()
            val sums = chunks.getOrPut(chunkIndex) {
                LongArray(chunkFrames * outputFormat.channelCount)
            }
            repeat(outputFormat.channelCount) { channel ->
                val sourceSample = frameIndex * outputFormat.channelCount + channel
                val targetSample = frameInChunk * outputFormat.channelCount + channel
                sums[targetSample] += (
                    data.readPcmS16(sourceSample) * sourceGains.getValue(frame.sourceId)
                    ).roundToLong()
            }
        }
        val endFrame = startFrame + frame.sampleCount
        sourceWatermarks[frame.sourceId] = maxOf(sourceWatermarks[frame.sourceId] ?: 0L, endFrame)
        if (sourceWatermarks.values.any { watermark -> watermark == null }) {
            return emptyList()
        }
        val completeThrough = sourceWatermarks.values.minOf { watermark -> requireNotNull(watermark) }
        return buildList {
            while ((nextChunkIndex + 1) * chunkFrames <= completeThrough) {
                val sums = chunks.remove(nextChunkIndex)
                    ?: LongArray(chunkFrames * outputFormat.channelCount)
                add(sums.toAudioFrame(nextChunkIndex))
                nextChunkIndex++
            }
        }
    }

    private fun LongArray.toAudioFrame(chunkIndex: Long): AudioFrame {
        val bytes = ByteArray(size * Short.SIZE_BYTES)
        forEachIndexed { index, sum -> bytes.writePcmS16(index, sum.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt()) }
        val startFrame = chunkIndex * chunkFrames
        return AudioFrame(
            timestamp = MediaTimestamp(startFrame * NANOS_PER_SECOND / outputFormat.sampleRate),
            sampleRate = outputFormat.sampleRate,
            channelCount = outputFormat.channelCount,
            sampleCount = chunkFrames,
            sourceId = outputSourceId,
            sampleFormat = AudioSampleFormat.PcmS16Le,
            audioData = bytes,
        )
    }
}

private fun AudioFrame.withGain(gain: Double): AudioFrame {
    if (gain == 1.0) {
        return this
    }
    return AudioMixer().mix(listOf(AudioMixInput(frame = this, gain = gain)), sourceId)
}

private fun writeConvertedFrame(
    frame: AudioFrame,
    sourceFrame: Int,
    output: ByteArray,
    outputFrame: Int,
    outputChannels: Int,
) {
    val first = frame.readNormalizedSample(sourceFrame * frame.channelCount)
    val second = if (frame.channelCount > 1) {
        frame.readNormalizedSample(sourceFrame * frame.channelCount + 1)
    } else {
        first
    }
    if (outputChannels == 1) {
        output.writeNormalizedSample(outputFrame, (first + second) / 2.0)
    } else {
        val offset = outputFrame * outputChannels
        output.writeNormalizedSample(offset, first)
        output.writeNormalizedSample(offset + 1, second)
        for (channel in 2 until outputChannels) {
            output.writeNormalizedSample(offset + channel, 0.0)
        }
    }
}

private fun ByteArray.readPcmS16(sampleIndex: Int): Int {
    val offset = sampleIndex * Short.SIZE_BYTES
    return ((this[offset].toInt() and 0xff) or (this[offset + 1].toInt() shl 8)).toShort().toInt()
}

private fun ByteArray.writeNormalizedSample(sampleIndex: Int, value: Double) {
    val sample = (value.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).roundToInt()
    writePcmS16(sampleIndex, sample)
}

private fun ByteArray.writePcmS16(sampleIndex: Int, value: Int) {
    val offset = sampleIndex * Short.SIZE_BYTES
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = (value shr 8 and 0xff).toByte()
}

private val MIXED_AUDIO_SOURCE_ID = AudioSourceId("audio:mixed")
private const val MIX_CHUNKS_PER_SECOND = 100
private const val NANOS_PER_SECOND = 1_000_000_000L
