package io.aequicor.capture.platform

import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class CompositeCaptureSourceRepositoryTest {
    @Test
    fun combinesRepositoriesAndRemovesDuplicateIds() = runBlocking {
        val screen = CaptureSource.Screen(CaptureSourceId("screen:all"), "All screens")
        val window = CaptureSource.Window(CaptureSourceId("window:test"), "Editor")
        val repository = CompositeCaptureSourceRepository(
            listOf(
                StaticRepository(listOf(screen)),
                StaticRepository(listOf(screen, window)),
            ),
        )

        assertEquals(listOf(screen, window), repository.listSources())
    }
}

private class StaticRepository(
    private val sources: List<CaptureSource>,
) : CaptureSourceRepository {
    override suspend fun listSources(request: CaptureSourceRequest): List<CaptureSource> = sources
}
