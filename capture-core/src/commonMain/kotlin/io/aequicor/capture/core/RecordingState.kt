package io.aequicor.capture.core

import kotlin.time.Duration

sealed interface RecordingState {
    data object Idle : RecordingState
    data class Preparing(val session: RecordingSession) : RecordingState
    data class Recording(val session: RecordingSession, val metrics: RecordingMetrics) : RecordingState
    data class Paused(val session: RecordingSession, val metrics: RecordingMetrics) : RecordingState
    data class Stopping(val session: RecordingSession, val metrics: RecordingMetrics) : RecordingState
    data class Completed(
        val session: RecordingSession,
        val metrics: RecordingMetrics,
        val outputPath: String,
    ) : RecordingState

    data class Failed(
        val session: RecordingSession?,
        val metrics: RecordingMetrics,
        val error: RecordingError,
    ) : RecordingState

    data class Cancelled(val session: RecordingSession?, val metrics: RecordingMetrics) : RecordingState
}

data class RecordingSession(
    val id: RecordingSessionId,
    val settings: RecordingSettings,
    val startedAtNanoseconds: Long,
)

data class RecordingMetrics(
    val duration: Duration = Duration.ZERO,
    val videoFrames: Long = 0,
    val audioFrames: Long = 0,
    val droppedFrames: Long = 0,
) {
    val effectiveFramesPerSecond: Double
        get() = if (duration.isPositive()) {
            videoFrames * NANOS_PER_SECOND.toDouble() / duration.inWholeNanoseconds
        } else {
            0.0
        }
}

private const val NANOS_PER_SECOND = 1_000_000_000L

sealed interface RecordingError {
    val message: String

    data class ValidationFailed(val issues: List<ValidationIssue>) : RecordingError {
        override val message: String = issues.joinToString("; ") { it.message }
    }

    data class PermissionDenied(override val message: String) : RecordingError
    data class SourceUnavailable(override val message: String) : RecordingError
    data class EncoderFailed(override val message: String) : RecordingError
    data class DiskFull(override val message: String) : RecordingError
    data class DeviceLost(override val message: String) : RecordingError
    data class Unknown(override val message: String) : RecordingError
}

data class ValidationIssue(
    val field: String,
    val message: String,
)
