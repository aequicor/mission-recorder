package io.aequicor.audio.desktop.javasound

import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.AudioSampleFormat
import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.MediaTimestamp
import io.aequicor.capture.core.RecordingError
import io.aequicor.capture.core.RecordingException
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.AudioCaptureAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class JavaSoundAudioCaptureAdapter(
    private val deviceProvider: JavaSoundAudioDeviceProvider = SystemJavaSoundAudioDeviceProvider(),
    private val timestampClock: AudioTimestampClock = SystemAudioTimestampClock,
    private val pollInterval: Duration = 5.milliseconds,
) : AudioCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<AudioFrame> {
        if (settings.audioSources.isEmpty()) {
            return emptyFlow()
        }
        val microphones = settings.audioSources.filterIsInstance<AudioSource.Microphone>()
        val unsupported = settings.audioSources.filterNot { it is AudioSource.Microphone }
        if (unsupported.isNotEmpty()) {
            return flow {
                throw RecordingException(
                    RecordingError.SourceUnavailable("Java Sound supports microphone capture only, not system loopback."),
                )
            }
        }
        return channelFlow {
            val captureStartedAt = timestampClock.nowNanoseconds().coerceAtLeast(0)
            microphones.forEach { microphone ->
                launch {
                    streamMicrophone(microphone, captureStartedAt) { frame ->
                        send(frame)
                    }
                }
            }
        }
    }

    private suspend fun streamMicrophone(
        source: AudioSource.Microphone,
        captureStartedAt: Long,
        emitFrame: suspend (AudioFrame) -> Unit,
    ) {
        val format = JavaSoundPcmFormat(sampleRate = source.sampleRate, channelCount = source.channelCount)
        val frameSize = format.bytesPerFrame
        val readBufferSize = max(source.sampleRate / 10, 1) * frameSize
        val line = deviceProvider.openTargetLine(
            sourceId = source.id,
            format = format,
            bufferSizeBytes = readBufferSize * 2,
        )
        val buffer = ByteArray(readBufferSize)
        var samplesEmitted = 0L
        val streamOffset = (timestampClock.nowNanoseconds() - captureStartedAt).coerceAtLeast(0)

        try {
            line.start()
            while (currentCoroutineContext().isActive) {
                val readableBytes = line.available()
                    .coerceAtMost(buffer.size)
                    .roundDownToFrame(frameSize)
                if (readableBytes <= 0) {
                    delay(pollInterval)
                    continue
                }
                val bytesRead = line.read(buffer, 0, readableBytes).roundDownToFrame(frameSize)
                if (bytesRead <= 0) {
                    delay(pollInterval)
                    continue
                }
                val sampleCount = bytesRead / frameSize
                emitFrame(
                    AudioFrame(
                        timestamp = MediaTimestamp(
                            streamOffset + samplesEmitted * NANOS_PER_SECOND / source.sampleRate,
                        ),
                        sampleRate = source.sampleRate,
                        channelCount = source.channelCount,
                        sampleCount = sampleCount,
                        sourceId = source.id,
                        sampleFormat = AudioSampleFormat.PcmS16Le,
                        audioData = buffer.copyOf(bytesRead),
                    ),
                )
                samplesEmitted += sampleCount
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (recording: RecordingException) {
            throw recording
        } catch (throwable: Throwable) {
            throw RecordingException(
                RecordingError.SourceUnavailable(
                    throwable.message ?: throwable::class.simpleName ?: "Java Sound microphone capture failed.",
                ),
            )
        } finally {
            runCatching { line.stop() }
            line.close()
        }
    }
}

fun interface AudioTimestampClock {
    fun nowNanoseconds(): Long
}

private data object SystemAudioTimestampClock : AudioTimestampClock {
    override fun nowNanoseconds(): Long = System.nanoTime()
}

private const val NANOS_PER_SECOND: Long = 1_000_000_000L

private fun Int.roundDownToFrame(frameSize: Int): Int = this - (this % frameSize)
