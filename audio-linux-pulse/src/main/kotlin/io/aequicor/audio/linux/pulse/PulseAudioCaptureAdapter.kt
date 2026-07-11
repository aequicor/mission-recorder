package io.aequicor.audio.linux.pulse

import io.aequicor.capture.core.AudioCaptureAdapter
import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.AudioSampleFormat
import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.MediaTimestamp
import io.aequicor.capture.core.RecordingError
import io.aequicor.capture.core.RecordingException
import io.aequicor.capture.core.RecordingSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

internal class PulseAudioCaptureAdapter(
    private val backend: PulseAudioBackend,
    private val dispatcher: CoroutineDispatcher,
) : AudioCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<AudioFrame> {
        if (settings.audioSources.isEmpty()) {
            return emptyFlow()
        }
        val sources = settings.audioSources.filterIsInstance<AudioSource.SystemLoopback>()
        if (sources.size != settings.audioSources.size || sources.size != 1) {
            return callbackFlow {
                close(sourceUnavailable("PulseAudio adapter requires exactly one system loopback source."))
            }
        }
        val source = sources.single()
        val deviceName = source.id.value.removePrefix(PULSE_MONITOR_ID_PREFIX)
        if (deviceName == source.id.value || deviceName.isBlank()) {
            return callbackFlow {
                close(sourceUnavailable("The selected system-audio source is not a PulseAudio monitor."))
            }
        }
        return capture(source, deviceName)
    }

    private fun capture(source: AudioSource.SystemLoopback, deviceName: String): Flow<AudioFrame> = callbackFlow {
        val processReference = AtomicReference<PulseCaptureProcess?>()
        val reader = launch(dispatcher) {
            val process = try {
                backend.openCapture(deviceName, source.sampleRate, source.channelCount)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                close(sourceUnavailable(failure.message ?: "Could not start PulseAudio capture."))
                return@launch
            }
            processReference.set(process)
            val bytesPerFrame = source.channelCount * AudioSampleFormat.PcmS16Le.bytesPerSample
            val readBuffer = ByteArray((source.sampleRate / CHUNKS_PER_SECOND).coerceAtLeast(1) * bytesPerFrame)
            var pending = ByteArray(0)
            var emittedSamples = 0L
            try {
                while (isActive) {
                    val read = process.inputStream.read(readBuffer)
                    if (read < 0) {
                        val exitCode = process.exitCodeOrNull()
                        throw sourceUnavailable(
                            if (exitCode == null || exitCode == 0) {
                                "PulseAudio capture stream ended unexpectedly."
                            } else {
                                "parec exited with code $exitCode."
                            },
                        )
                    }
                    if (read == 0) {
                        continue
                    }
                    val combined = ByteArray(pending.size + read)
                    pending.copyInto(combined)
                    readBuffer.copyInto(combined, destinationOffset = pending.size, endIndex = read)
                    val completeBytes = combined.size - combined.size % bytesPerFrame
                    if (completeBytes == 0) {
                        pending = combined
                        continue
                    }
                    val payload = combined.copyOf(completeBytes)
                    val sampleCount = payload.size / bytesPerFrame
                    send(
                        AudioFrame(
                            timestamp = MediaTimestamp(emittedSamples * NANOS_PER_SECOND / source.sampleRate),
                            sampleRate = source.sampleRate,
                            channelCount = source.channelCount,
                            sampleCount = sampleCount,
                            sourceId = source.id,
                            sampleFormat = AudioSampleFormat.PcmS16Le,
                            audioData = payload,
                        ),
                    )
                    emittedSamples += sampleCount
                    pending = combined.copyOfRange(completeBytes, combined.size)
                }
            } catch (recording: RecordingException) {
                close(recording)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                close(sourceUnavailable(failure.message ?: "PulseAudio capture failed."))
            } finally {
                processReference.compareAndSet(process, null)
                process.close()
            }
        }
        awaitClose {
            processReference.getAndSet(null)?.close()
            reader.cancel()
        }
    }
}

private fun sourceUnavailable(message: String): RecordingException =
    RecordingException(RecordingError.SourceUnavailable(message))

private const val CHUNKS_PER_SECOND = 50
private const val NANOS_PER_SECOND = 1_000_000_000L
