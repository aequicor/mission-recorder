package io.aequicor.compose.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecorderUiModelTest {
    @Test
    fun startRequiresSourceOutputAndIdleRecorder() {
        val ready = RecorderUiState(
            sources = listOf(RecorderSourceUi("screen:all", "All screens", RecorderSourceKind.Screen)),
            selectedSourceId = "screen:all",
            outputPath = "recordings/mission.mrec",
        )

        assertTrue(ready.canStart)
        assertFalse(ready.copy(selectedSourceId = null).canStart)
        assertFalse(ready.copy(outputPath = " ").canStart)
        assertFalse(ready.copy(status = RecorderStatus.Recording).canStart)
        val paused = ready.copy(status = RecorderStatus.Paused)
        assertFalse(paused.canStart)
        assertTrue(paused.hasActiveRecording)
        assertTrue(paused.canResumeRecording)
        assertFalse(paused.canPauseRecording)
        assertTrue(ready.copy(status = RecorderStatus.Recording).canPauseRecording)
        assertFalse(ready.copy(isChoosingOutputFile = true).canStart)
        assertFalse(ready.canToggleMicrophoneMute)
        assertFalse(ready.canToggleSystemAudioMute)
        assertTrue(ready.copy(selectedMicrophoneId = "mic:test").canToggleMicrophoneMute)
        assertTrue(
            ready.copy(systemAudioAvailable = true, systemAudioEnabled = true).canToggleSystemAudioMute,
        )
    }
}
