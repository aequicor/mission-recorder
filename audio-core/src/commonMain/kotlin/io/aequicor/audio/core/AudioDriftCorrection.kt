package io.aequicor.audio.core

import io.aequicor.capture.core.AudioCaptureAdapter
import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.MediaClock
import io.aequicor.capture.core.MediaTimestamp
import io.aequicor.capture.core.RecordingSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlin.math.absoluteValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class AudioDriftCorrectionPolicy(
    val measurementWindow: Duration = 5.seconds,
    val tolerance: Duration = 10.milliseconds,
    val maxCorrectionPerWindow: Duration = 5.milliseconds,
) {
    init {
        require(measurementWindow.isPositive()) { "Audio drift measurement window must be positive." }
        require(!tolerance.isNegative()) { "Audio drift tolerance must not be negative." }
        require(maxCorrectionPerWindow.isPositive()) { "Audio drift correction limit must be positive." }
    }
}

data class AudioDriftCorrectionStats(
    val estimatedDriftNanoseconds: Long = 0,
    val insertedSamples: Long = 0,
    val droppedSamples: Long = 0,
)

class AudioDriftCorrectionMonitor {
    private val mutableStats = MutableStateFlow<Map<AudioSourceId, AudioDriftCorrectionStats>>(emptyMap())

    val stats: StateFlow<Map<AudioSourceId, AudioDriftCorrectionStats>> = mutableStats.asStateFlow()

    internal fun publish(sourceId: AudioSourceId, stats: AudioDriftCorrectionStats) {
        mutableStats.update { current -> current + (sourceId to stats) }
    }

    fun clear() {
        mutableStats.value = emptyMap()
    }
}

class DriftCorrectingAudioCaptureAdapter(
    private val delegate: AudioCaptureAdapter,
    private val clock: MediaClock,
    private val policy: AudioDriftCorrectionPolicy = AudioDriftCorrectionPolicy(),
    private val monitor: AudioDriftCorrectionMonitor = AudioDriftCorrectionMonitor(),
) : AudioCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<AudioFrame> = flow {
        val correctors = mutableMapOf<AudioSourceId, PcmTimelineDriftCorrector>()
        delegate.frames(settings).collect { frame ->
            val corrector = correctors.getOrPut(frame.sourceId) {
                PcmTimelineDriftCorrector(policy)
            }
            val corrected = corrector.correct(frame, clock.nowNanoseconds())
            monitor.publish(frame.sourceId, corrected.stats)
            emit(corrected.frame)
        }
    }
}

private class PcmTimelineDriftCorrector(
    private val policy: AudioDriftCorrectionPolicy,
) {
    private var sampleRate: Int? = null
    private var windowStartedAtNanoseconds: Long? = null
    private var minimumTransportOffsetNanoseconds: Long? = null
    private var baselineTransportOffsetNanoseconds: Long? = null
    private var timelineOffsetSamples = 0L
    private var insertedSamples = 0L
    private var droppedSamples = 0L
    private var lastEstimatedDriftNanoseconds = 0L

    fun correct(frame: AudioFrame, observedAtNanoseconds: Long): CorrectedAudioFrame {
        validate(frame)
        val stableSampleRate = sampleRate ?: frame.sampleRate.also { sampleRate = it }
        require(frame.sampleRate == stableSampleRate) { "Audio sample rate changed during drift correction." }

        val frameEndNanoseconds = frame.timestamp.nanoseconds +
            frame.sampleCount.toLong() * NANOS_PER_SECOND / stableSampleRate
        val transportOffset = observedAtNanoseconds - frameEndNanoseconds
        val windowStartedAt = windowStartedAtNanoseconds
        if (windowStartedAt == null) {
            windowStartedAtNanoseconds = observedAtNanoseconds
            minimumTransportOffsetNanoseconds = transportOffset
        } else {
            minimumTransportOffsetNanoseconds = minOf(
                minimumTransportOffsetNanoseconds ?: transportOffset,
                transportOffset,
            )
        }

        val correctionSamples = if (
            windowStartedAt != null &&
            observedAtNanoseconds - windowStartedAt >= policy.measurementWindow.inWholeNanoseconds
        ) {
            closeMeasurementWindow(observedAtNanoseconds, transportOffset, stableSampleRate)
        } else {
            0
        }

        val applicableCorrection = correctionSamples.coerceAtLeast(-(frame.sampleCount - 1))
        val correctedTimestamp = frame.timestamp.shiftBySamples(timelineOffsetSamples, stableSampleRate)
        val correctedFrame = when {
            applicableCorrection > 0 -> frame.prependSilence(applicableCorrection, correctedTimestamp)
            applicableCorrection < 0 -> frame.dropPrefix(-applicableCorrection, correctedTimestamp)
            else -> frame.copy(timestamp = correctedTimestamp)
        }
        timelineOffsetSamples += applicableCorrection
        if (applicableCorrection > 0) {
            insertedSamples += applicableCorrection
        } else {
            droppedSamples += -applicableCorrection
        }
        return CorrectedAudioFrame(
            frame = correctedFrame,
            stats = AudioDriftCorrectionStats(
                estimatedDriftNanoseconds = estimatedDriftNanoseconds(),
                insertedSamples = insertedSamples,
                droppedSamples = droppedSamples,
            ),
        )
    }

    private fun closeMeasurementWindow(
        observedAtNanoseconds: Long,
        transportOffset: Long,
        sampleRate: Int,
    ): Int {
        val minimumOffset = minimumTransportOffsetNanoseconds ?: transportOffset
        val baseline = baselineTransportOffsetNanoseconds
        val correction = if (baseline == null) {
            baselineTransportOffsetNanoseconds = minimumOffset
            lastEstimatedDriftNanoseconds = 0
            0
        } else {
            val residualDrift = minimumOffset - baseline - samplesToNanoseconds(timelineOffsetSamples, sampleRate)
            if (residualDrift.absoluteValue <= policy.tolerance.inWholeNanoseconds) {
                lastEstimatedDriftNanoseconds = residualDrift
                0
            } else {
                val requested = residualDrift * sampleRate / NANOS_PER_SECOND
                val limit = (policy.maxCorrectionPerWindow.inWholeNanoseconds * sampleRate / NANOS_PER_SECOND)
                    .coerceAtLeast(1)
                requested.coerceIn(-limit, limit).toInt().also { applied ->
                    lastEstimatedDriftNanoseconds = residualDrift - samplesToNanoseconds(applied.toLong(), sampleRate)
                }
            }
        }
        windowStartedAtNanoseconds = observedAtNanoseconds
        minimumTransportOffsetNanoseconds = null
        return correction
    }

    private fun estimatedDriftNanoseconds(): Long {
        val rate = sampleRate ?: return 0
        val baseline = baselineTransportOffsetNanoseconds ?: return 0
        val minimum = minimumTransportOffsetNanoseconds ?: return lastEstimatedDriftNanoseconds
        return minimum - baseline - samplesToNanoseconds(timelineOffsetSamples, rate)
    }

    private fun validate(frame: AudioFrame) {
        require(frame.sampleRate > 0 && frame.channelCount > 0 && frame.sampleCount > 0) {
            "Audio frame format must be positive."
        }
        val payload = requireNotNull(frame.audioData) { "Audio drift correction requires PCM payload." }
        require(payload.size >= frame.requiredBytes()) { "Audio frame PCM payload is too short." }
    }
}

private data class CorrectedAudioFrame(
    val frame: AudioFrame,
    val stats: AudioDriftCorrectionStats,
)

private fun AudioFrame.prependSilence(samples: Int, timestamp: MediaTimestamp): AudioFrame {
    val bytesPerFrame = channelCount * sampleFormat.bytesPerSample
    val originalBytes = requireNotNull(audioData)
    val output = ByteArray((sampleCount + samples) * bytesPerFrame)
    originalBytes.copyInto(
        destination = output,
        destinationOffset = samples * bytesPerFrame,
        endIndex = requiredBytes(),
    )
    return copy(
        timestamp = timestamp,
        sampleCount = sampleCount + samples,
        audioData = output,
    )
}

private fun AudioFrame.dropPrefix(samples: Int, timestamp: MediaTimestamp): AudioFrame {
    val dropped = samples.coerceAtMost(sampleCount - 1)
    if (dropped == 0) {
        return copy(timestamp = timestamp)
    }
    val bytesPerFrame = channelCount * sampleFormat.bytesPerSample
    val source = requireNotNull(audioData)
    val outputSamples = sampleCount - dropped
    return copy(
        timestamp = timestamp,
        sampleCount = outputSamples,
        audioData = source.copyOfRange(dropped * bytesPerFrame, sampleCount * bytesPerFrame),
    )
}

private fun MediaTimestamp.shiftBySamples(samples: Long, sampleRate: Int): MediaTimestamp =
    MediaTimestamp((nanoseconds + samplesToNanoseconds(samples, sampleRate)).coerceAtLeast(0))

private fun samplesToNanoseconds(samples: Long, sampleRate: Int): Long =
    samples * NANOS_PER_SECOND / sampleRate

private const val NANOS_PER_SECOND = 1_000_000_000L
