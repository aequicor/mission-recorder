package io.aequicor.capture.platform

import io.aequicor.capture.core.AudioSource
import io.aequicor.capture.core.CaptureSource

interface CaptureSourceRepository {
    suspend fun listSources(request: CaptureSourceRequest = CaptureSourceRequest()): List<CaptureSource>
}

data class CaptureSourceRequest(
    val includeScreens: Boolean = true,
    val includeMonitors: Boolean = true,
    val includeWindows: Boolean = true,
    val includeApplications: Boolean = true,
)

interface AudioSourceRepository {
    suspend fun listAudioSources(request: AudioSourceRequest = AudioSourceRequest()): List<AudioSource>
}

data class AudioSourceRequest(
    val includeMicrophones: Boolean = true,
    val includeSystemLoopback: Boolean = true,
)

data object EmptyCaptureSourceRepository : CaptureSourceRepository {
    override suspend fun listSources(request: CaptureSourceRequest): List<CaptureSource> = emptyList()
}

data object EmptyAudioSourceRepository : AudioSourceRepository {
    override suspend fun listAudioSources(request: AudioSourceRequest): List<AudioSource> = emptyList()
}
