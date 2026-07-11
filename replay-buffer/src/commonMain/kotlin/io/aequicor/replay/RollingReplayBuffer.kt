package io.aequicor.replay

import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.MediaEncoder
import io.aequicor.capture.core.RecordingMetrics
import io.aequicor.capture.core.RecordingOutput
import io.aequicor.capture.core.RecordingSession
import io.aequicor.capture.core.RecordingSessionId
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.VideoFrame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

class RollingReplayBuffer(
    private val settings: ReplayBufferSettings,
) {
    private val videoFrames = ArrayDeque<VideoFrame>()
    private val audioFrames = ArrayDeque<AudioFrame>()

    init {
        require(settings.duration.isPositive()) { "Replay duration must be positive." }
        require(settings.maxVideoFrames == null || settings.maxVideoFrames > 0) {
            "Max video frames must be positive when provided."
        }
        require(settings.maxAudioFrames == null || settings.maxAudioFrames > 0) {
            "Max audio frames must be positive when provided."
        }
    }

    @Synchronized
    fun append(frame: VideoFrame): ReplayBufferStats {
        videoFrames.addLast(frame.retainedCopy())
        trimFrames()
        return stats()
    }

    @Synchronized
    fun append(frame: AudioFrame): ReplayBufferStats {
        audioFrames.addLast(frame.retainedCopy())
        trimFrames()
        return stats()
    }

    @Synchronized
    fun snapshot(): ReplayBufferSnapshot =
        ReplayBufferSnapshot(
            videoFrames = videoFrames.map { it.retainedCopy() },
            audioFrames = audioFrames.map { it.retainedCopy() },
            duration = settings.duration,
        )

    @Synchronized
    fun clear() {
        videoFrames.clear()
        audioFrames.clear()
    }

    @Synchronized
    fun stats(): ReplayBufferStats =
        ReplayBufferStats(
            videoFrameCount = videoFrames.size,
            audioFrameCount = audioFrames.size,
            retainedDuration = retainedDuration(),
            storagePolicy = settings.storagePolicy,
        )

    private fun trimFrames() {
        val minimumTimestamp = minimumTimestamp(latestTimestampNanoseconds() ?: return)
        while (videoFrames.firstOrNull()?.timestamp?.nanoseconds?.let { it < minimumTimestamp } == true) {
            videoFrames.removeFirst()
        }
        while (audioFrames.firstOrNull()?.timestamp?.nanoseconds?.let { it < minimumTimestamp } == true) {
            audioFrames.removeFirst()
        }

        val maxFrames = settings.maxVideoFrames
        if (maxFrames != null) {
            while (videoFrames.size > maxFrames) {
                videoFrames.removeFirst()
            }
        }
        val maxAudioFrames = settings.maxAudioFrames
        if (maxAudioFrames != null) {
            while (audioFrames.size > maxAudioFrames) {
                audioFrames.removeFirst()
            }
        }
    }

    private fun minimumTimestamp(newestTimestampNanoseconds: Long): Long =
        (newestTimestampNanoseconds - settings.duration.inWholeNanoseconds).coerceAtLeast(0)

    private fun latestTimestampNanoseconds(): Long? =
        listOfNotNull(
            videoFrames.lastOrNull()?.timestamp?.nanoseconds,
            audioFrames.lastOrNull()?.timestamp?.nanoseconds,
        ).maxOrNull()

    private fun retainedDuration(): Duration {
        val first = listOfNotNull(
            videoFrames.firstOrNull()?.timestamp?.nanoseconds,
            audioFrames.firstOrNull()?.timestamp?.nanoseconds,
        ).minOrNull() ?: return Duration.ZERO
        val last = listOfNotNull(
            videoFrames.lastOrNull()?.timestamp?.nanoseconds,
            audioFrames.lastOrNull()?.timestamp?.nanoseconds,
        ).maxOrNull() ?: return Duration.ZERO
        return (last - first).coerceAtLeast(0).nanoseconds
    }
}

data class ReplayBufferSettings(
    val duration: Duration,
    val maxVideoFrames: Int? = null,
    val maxAudioFrames: Int? = null,
    val storagePolicy: ReplayStoragePolicy = ReplayStoragePolicy.Memory,
)

enum class ReplayStoragePolicy {
    Memory,
    DiskSegments,
}

data class ReplayBufferStats(
    val videoFrameCount: Int,
    val audioFrameCount: Int,
    val retainedDuration: Duration,
    val storagePolicy: ReplayStoragePolicy,
)

data class ReplayBufferSnapshot(
    val videoFrames: List<VideoFrame>,
    val audioFrames: List<AudioFrame>,
    val duration: Duration,
) {
    val isEmpty: Boolean
        get() = videoFrames.isEmpty() && audioFrames.isEmpty()
}

class ReplayBufferSaver(
    private val encoder: MediaEncoder,
) {
    suspend fun save(request: ReplaySaveRequest): ReplaySaveResult {
        if (request.snapshot.isEmpty) {
            throw ReplayBufferException("Replay buffer is empty.")
        }

        val session = RecordingSession(
            id = request.sessionId,
            settings = RecordingSettings(
                captureSource = request.captureSource,
                audioSources = request.audioSources,
                outputPath = request.outputPath,
                frameRate = request.frameRate,
                captureCursor = request.captureCursor,
                encoder = request.encoderSettings,
            ),
            startedAtNanoseconds = request.snapshot.startTimestampNanoseconds(),
        )

        return try {
            encoder.open(session, session.settings)
            request.snapshot.videoFrames.forEach { encoder.writeVideoFrame(it) }
            request.snapshot.audioFrames.forEach { encoder.writeAudioFrame(it) }
            val metrics = request.snapshot.metrics()
            val output = encoder.finish(session, metrics)
            ReplaySaveResult(
                output = output,
                videoFrames = metrics.videoFrames,
                audioFrames = metrics.audioFrames,
                duration = metrics.duration,
            )
        } catch (throwable: Throwable) {
            encoder.cancel(session)
            throw throwable
        }
    }
}

data class ReplaySaveRequest(
    val snapshot: ReplayBufferSnapshot,
    val outputPath: String,
    val captureSource: CaptureSource,
    val frameRate: Int,
    val sessionId: RecordingSessionId,
    val audioSources: List<AudioSource> = emptyList(),
    val captureCursor: Boolean = false,
    val encoderSettings: io.aequicor.capture.core.EncoderSettings = io.aequicor.capture.core.EncoderSettings(),
)

data class ReplaySaveResult(
    val output: RecordingOutput,
    val videoFrames: Long,
    val audioFrames: Long,
    val duration: Duration,
)

class ReplayBufferException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private fun ReplayBufferSnapshot.startTimestampNanoseconds(): Long =
    (videoFrames.asSequence().map { it.timestamp.nanoseconds } +
        audioFrames.asSequence().map { it.timestamp.nanoseconds })
        .minOrNull() ?: 0

private fun ReplayBufferSnapshot.endTimestampNanoseconds(): Long =
    (videoFrames.asSequence().map { it.timestamp.nanoseconds } +
        audioFrames.asSequence().map { it.timestamp.nanoseconds })
        .maxOrNull() ?: startTimestampNanoseconds()

private fun ReplayBufferSnapshot.metrics(): RecordingMetrics =
    RecordingMetrics(
        duration = (endTimestampNanoseconds() - startTimestampNanoseconds()).coerceAtLeast(0).nanoseconds,
        videoFrames = videoFrames.size.toLong(),
        audioFrames = audioFrames.size.toLong(),
    )

private fun VideoFrame.retainedCopy(): VideoFrame =
    copy(pixelData = pixelData?.copyOf())

private fun AudioFrame.retainedCopy(): AudioFrame =
    copy(audioData = audioData?.copyOf())
