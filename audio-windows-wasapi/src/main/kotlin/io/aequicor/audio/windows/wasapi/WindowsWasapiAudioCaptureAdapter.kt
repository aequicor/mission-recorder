package io.aequicor.audio.windows.wasapi

import io.aequicor.capture.core.AudioCaptureAdapter
import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.AudioSampleFormat
import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.MediaTimestamp
import io.aequicor.capture.core.RecordingError
import io.aequicor.capture.core.RecordingException
import io.aequicor.capture.core.RecordingSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class WindowsWasapiAudioCaptureAdapter internal constructor(
    private val clientFactory: WasapiLoopbackClientFactory,
    private val dispatcher: CoroutineDispatcher,
    private val clock: WasapiTimestampClock = SystemWasapiTimestampClock,
    private val pollInterval: Duration = 5.milliseconds,
) : AudioCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<AudioFrame> {
        if (settings.audioSources.isEmpty()) {
            return emptyFlow()
        }
        val loopbackSources = settings.audioSources.filterIsInstance<AudioSource.SystemLoopback>()
        if (loopbackSources.size != settings.audioSources.size || loopbackSources.size != 1) {
            return flow {
                throw sourceUnavailable("WASAPI adapter requires exactly one system loopback source.")
            }
        }
        return capture(loopbackSources.single()).flowOn(dispatcher)
    }

    private fun capture(source: AudioSource.SystemLoopback): Flow<AudioFrame> = flow {
        val client = try {
            clientFactory.open(source.id)
        } catch (failure: RuntimeException) {
            throw sourceUnavailable(failure.message ?: "WASAPI loopback is unavailable.")
        }
        try {
            val format = client.format
            val outputChannels = format.outputChannelCount
            if (format.sampleRate != source.sampleRate || outputChannels != source.channelCount) {
                throw sourceUnavailable("The default output device changed. Refresh audio sources and try again.")
            }
            val silenceFrames = max(format.sampleRate / SILENCE_CHUNKS_PER_SECOND, 1)
            val startedAt = clock.nowNanoseconds()
            var emittedFrames = 0L
            client.start()
            while (currentCoroutineContext().isActive) {
                var packet = client.nextPacket()
                var emittedPacket = false
                while (packet != null) {
                    if (packet.frameCount > 0) {
                        emit(source.toFrame(packet, format, outputChannels, emittedFrames))
                        emittedFrames += packet.frameCount
                        emittedPacket = true
                    }
                    packet = client.nextPacket()
                }

                val elapsed = (clock.nowNanoseconds() - startedAt).coerceAtLeast(0)
                val expectedFrames = elapsed * format.sampleRate / NANOS_PER_SECOND
                val missingFrames = expectedFrames - emittedFrames
                if (!emittedPacket && missingFrames >= silenceFrames * 2L) {
                    val frameCount = min(silenceFrames.toLong(), missingFrames - silenceFrames).toInt()
                    if (frameCount > 0) {
                        emit(source.toSilentFrame(frameCount, emittedFrames))
                        emittedFrames += frameCount
                    }
                }
                delay(pollInterval)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (recording: RecordingException) {
            throw recording
        } catch (failure: RuntimeException) {
            throw sourceUnavailable(failure.message ?: "WASAPI loopback capture failed.")
        } finally {
            runCatching { client.stop() }
            client.close()
        }
    }
}

fun interface WasapiTimestampClock {
    fun nowNanoseconds(): Long
}

private data object SystemWasapiTimestampClock : WasapiTimestampClock {
    override fun nowNanoseconds(): Long = System.nanoTime()
}

private fun AudioSource.SystemLoopback.toFrame(
    packet: WasapiAudioPacket,
    format: WasapiNativeFormat,
    outputChannels: Int,
    startFrame: Long,
): AudioFrame = AudioFrame(
    timestamp = MediaTimestamp(startFrame * NANOS_PER_SECOND / sampleRate),
    sampleRate = sampleRate,
    channelCount = outputChannels,
    sampleCount = packet.frameCount,
    sourceId = id,
    sampleFormat = AudioSampleFormat.PcmS16Le,
    audioData = WasapiPcmConverter.toPcmS16Le(packet, format, outputChannels),
)

private fun AudioSource.SystemLoopback.toSilentFrame(frameCount: Int, startFrame: Long): AudioFrame = AudioFrame(
    timestamp = MediaTimestamp(startFrame * NANOS_PER_SECOND / sampleRate),
    sampleRate = sampleRate,
    channelCount = channelCount,
    sampleCount = frameCount,
    sourceId = id,
    sampleFormat = AudioSampleFormat.PcmS16Le,
    audioData = ByteArray(frameCount * channelCount * Short.SIZE_BYTES),
)

private fun sourceUnavailable(message: String): RecordingException =
    RecordingException(RecordingError.SourceUnavailable(message))

private const val SILENCE_CHUNKS_PER_SECOND = 100
private const val NANOS_PER_SECOND = 1_000_000_000L
