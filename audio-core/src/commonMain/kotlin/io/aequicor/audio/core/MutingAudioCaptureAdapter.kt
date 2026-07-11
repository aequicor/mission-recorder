package io.aequicor.audio.core

import io.aequicor.capture.core.AudioCaptureAdapter
import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.AudioSourceId
import io.aequicor.capture.core.RecordingSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

interface AudioMuteController {
    val mutedSourceIds: StateFlow<Set<AudioSourceId>>
    val soloSourceId: StateFlow<AudioSourceId?>

    fun setMuted(sourceId: AudioSourceId, muted: Boolean)
    fun setSolo(sourceId: AudioSourceId?)
    fun clear()
}

class MutableAudioMuteController : AudioMuteController {
    private val mutableMutedSourceIds = MutableStateFlow<Set<AudioSourceId>>(emptySet())
    private val mutableSoloSourceId = MutableStateFlow<AudioSourceId?>(null)

    override val mutedSourceIds: StateFlow<Set<AudioSourceId>> = mutableMutedSourceIds.asStateFlow()
    override val soloSourceId: StateFlow<AudioSourceId?> = mutableSoloSourceId.asStateFlow()

    override fun setMuted(sourceId: AudioSourceId, muted: Boolean) {
        mutableMutedSourceIds.update { current ->
            if (muted) current + sourceId else current - sourceId
        }
    }

    override fun setSolo(sourceId: AudioSourceId?) {
        mutableSoloSourceId.value = sourceId
    }

    override fun clear() {
        mutableMutedSourceIds.value = emptySet()
        mutableSoloSourceId.value = null
    }
}

data object NoopAudioMuteController : AudioMuteController {
    private val emptyMutedSourceIds = MutableStateFlow<Set<AudioSourceId>>(emptySet())
    private val emptySoloSourceId = MutableStateFlow<AudioSourceId?>(null)

    override val mutedSourceIds: StateFlow<Set<AudioSourceId>> = emptyMutedSourceIds.asStateFlow()
    override val soloSourceId: StateFlow<AudioSourceId?> = emptySoloSourceId.asStateFlow()

    override fun setMuted(sourceId: AudioSourceId, muted: Boolean) = Unit

    override fun setSolo(sourceId: AudioSourceId?) = Unit

    override fun clear() = Unit
}

class MutingAudioCaptureAdapter(
    private val delegate: AudioCaptureAdapter,
    private val muteController: AudioMuteController,
) : AudioCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<AudioFrame> =
        delegate.frames(settings).map { frame ->
            val soloSourceId = muteController.soloSourceId.value
            val gated = frame.sourceId in muteController.mutedSourceIds.value ||
                (soloSourceId != null && frame.sourceId != soloSourceId)
            if (gated) frame.silenced() else frame
        }
}

private fun AudioFrame.silenced(): AudioFrame = copy(
    audioData = audioData?.let { payload -> ByteArray(payload.size) },
)
