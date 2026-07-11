package io.aequicor.audio.core

import io.aequicor.capture.core.AudioCaptureAdapter
import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.RecordingSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class AudioLevelMonitor {
    private val mutableLevels = MutableStateFlow<Map<AudioSourceId, AudioLevels>>(emptyMap())

    val levels: StateFlow<Map<AudioSourceId, AudioLevels>> = mutableLevels.asStateFlow()

    fun observe(frame: AudioFrame) {
        val measured = runCatching { AudioLevelMeter.measure(frame) }.getOrNull() ?: return
        mutableLevels.update { current -> current + (frame.sourceId to measured) }
    }

    fun clear(sourceIds: Set<AudioSourceId>) {
        if (sourceIds.isEmpty()) {
            return
        }
        mutableLevels.update { current -> current - sourceIds }
    }
}

class MeteringAudioCaptureAdapter(
    private val delegate: AudioCaptureAdapter,
    private val monitor: AudioLevelMonitor,
    private val publishInterval: Duration = 50.milliseconds,
) : AudioCaptureAdapter {
    init {
        require(!publishInterval.isNegative()) { "Audio meter publish interval cannot be negative." }
    }

    override fun frames(settings: RecordingSettings): Flow<AudioFrame> = flow {
        val observedSourceIds = settings.audioSources.mapTo(mutableSetOf()) { source -> source.id }
        val lastPublishedAt = mutableMapOf<AudioSourceId, Long>()
        try {
            delegate.frames(settings).collect { frame ->
                observedSourceIds += frame.sourceId
                val timestamp = frame.timestamp.nanoseconds
                val previous = lastPublishedAt[frame.sourceId]
                if (
                    previous == null ||
                    timestamp < previous ||
                    timestamp - previous >= publishInterval.inWholeNanoseconds
                ) {
                    monitor.observe(frame)
                    lastPublishedAt[frame.sourceId] = timestamp
                }
                emit(frame)
            }
        } finally {
            monitor.clear(observedSourceIds)
        }
    }
}
