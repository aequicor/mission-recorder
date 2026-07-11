package io.aequicor.capture.platform

import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.CaptureSourceId
import io.aequicor.capture.core.MediaTimestamp
import io.aequicor.capture.core.PixelFormat
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.VideoCaptureAdapter
import io.aequicor.capture.core.VideoFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutingVideoCaptureAdapterTest {
    @Test
    fun routesWindowSourcesAndFallsBackForScreens() = runBlocking {
        val fallback = MarkerVideoCaptureAdapter(width = 1)
        val windowAdapter = MarkerVideoCaptureAdapter(width = 2)
        val adapter = RoutingVideoCaptureAdapter(
            routes = listOf(
                VideoCaptureRoute(
                    matches = { source -> source is CaptureSource.Window || source is CaptureSource.Application },
                    adapter = windowAdapter,
                ),
            ),
            fallback = fallback,
        )

        assertEquals(2, adapter.frames(settings(CaptureSource.Window(CaptureSourceId("window:1"), "Window"))).first().width)
        assertEquals(1, adapter.frames(settings(CaptureSource.Screen(CaptureSourceId("screen:all"), "Screen"))).first().width)
    }
}

private class MarkerVideoCaptureAdapter(
    private val width: Int,
) : VideoCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<VideoFrame> = flowOf(
        VideoFrame(
            timestamp = MediaTimestamp(0),
            width = width,
            height = 1,
            pixelFormat = PixelFormat.Rgba8888,
            strideBytes = width * 4,
            sourceId = settings.captureSource.id,
        ),
    )
}

private fun settings(source: CaptureSource): RecordingSettings = RecordingSettings(
    captureSource = source,
    outputPath = "unused.mp4",
)
