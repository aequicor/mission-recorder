package io.aequicor.capture.platform

import io.aequicor.capture.core.AudioSource

class CompositeAudioSourceRepository(
    private val repositories: List<AudioSourceRepository>,
) : AudioSourceRepository {
    init {
        require(repositories.isNotEmpty()) { "At least one audio source repository is required." }
    }

    override suspend fun listAudioSources(request: AudioSourceRequest): List<AudioSource> =
        repositories
            .flatMap { repository -> repository.listAudioSources(request) }
            .distinctBy { source -> source.id }
}
