package io.aequicor.replay

import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.RecordingSession
import io.aequicor.capture.core.VideoFrame
import kotlin.time.Duration

interface ReplayMediaBuffer {
    suspend fun open(session: RecordingSession, duration: Duration)

    suspend fun writeVideoFrame(frame: VideoFrame): ReplayBufferStats

    suspend fun writeAudioFrame(frame: AudioFrame): ReplayBufferStats

    suspend fun save(outputPath: String): ReplaySaveResult

    suspend fun close()
}
