package io.aequicor.capture.platform

import io.aequicor.capture.core.CaptureSource

class CompositeCaptureSourceRepository(
    private val repositories: List<CaptureSourceRepository>,
) : CaptureSourceRepository {
    init {
        require(repositories.isNotEmpty()) { "At least one capture source repository is required." }
    }

    override suspend fun listSources(request: CaptureSourceRequest): List<CaptureSource> =
        repositories
            .flatMap { repository -> repository.listSources(request) }
            .distinctBy { source -> source.id }
}
