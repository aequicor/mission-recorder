package io.aequicor.capture.core

import kotlinx.coroutines.flow.Flow

interface MediaClock {
    fun nowNanoseconds(): Long
}

fun interface RecordingSessionIdFactory {
    fun nextId(): RecordingSessionId
}

interface VideoCaptureAdapter {
    fun frames(settings: RecordingSettings): Flow<VideoFrame>
}

interface AudioCaptureAdapter {
    fun frames(settings: RecordingSettings): Flow<AudioFrame>
}

interface MediaEncoder {
    suspend fun open(session: RecordingSession, settings: RecordingSettings)
    suspend fun writeVideoFrame(frame: VideoFrame)
    suspend fun writeAudioFrame(frame: AudioFrame)
    suspend fun finish(session: RecordingSession, metrics: RecordingMetrics): RecordingOutput
    suspend fun cancel(session: RecordingSession?)
}

data class RecordingOutput(
    val path: String,
)

sealed interface StartRecordingResult {
    data class Started(val session: RecordingSession) : StartRecordingResult
    data class Rejected(val issues: List<ValidationIssue>) : StartRecordingResult
    data class Busy(val state: RecordingState) : StartRecordingResult
    data class Failed(val state: RecordingState.Failed) : StartRecordingResult
}

sealed interface StopRecordingResult {
    data class Stopped(val state: RecordingState.Completed) : StopRecordingResult
    data class NotRecording(val state: RecordingState) : StopRecordingResult
}

sealed interface PauseRecordingResult {
    data class Paused(val state: RecordingState.Paused) : PauseRecordingResult
    data class AlreadyPaused(val state: RecordingState.Paused) : PauseRecordingResult
    data class NotRecording(val state: RecordingState) : PauseRecordingResult
}

sealed interface ResumeRecordingResult {
    data class Resumed(val state: RecordingState.Recording) : ResumeRecordingResult
    data class NotPaused(val state: RecordingState) : ResumeRecordingResult
}

sealed interface CancelRecordingResult {
    data class Cancelled(val state: RecordingState.Cancelled) : CancelRecordingResult
    data class NotRecording(val state: RecordingState) : CancelRecordingResult
}
