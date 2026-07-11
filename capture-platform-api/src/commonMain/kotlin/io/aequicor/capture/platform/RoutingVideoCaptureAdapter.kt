package io.aequicor.capture.platform

import io.aequicor.capture.core.CaptureSource
import io.aequicor.capture.core.RecordingError
import io.aequicor.capture.core.RecordingException
import io.aequicor.capture.core.RecordingSettings
import io.aequicor.capture.core.VideoCaptureAdapter
import io.aequicor.capture.core.VideoFrame
import kotlinx.coroutines.flow.Flow

data class VideoCaptureRoute(
    val matches: (CaptureSource) -> Boolean,
    val adapter: VideoCaptureAdapter,
)

class RoutingVideoCaptureAdapter(
    private val routes: List<VideoCaptureRoute>,
    private val fallback: VideoCaptureAdapter? = null,
) : VideoCaptureAdapter {
    override fun frames(settings: RecordingSettings): Flow<VideoFrame> {
        val adapter = routes.firstOrNull { route -> route.matches(settings.captureSource) }?.adapter
            ?: fallback
            ?: throw RecordingException(
                RecordingError.SourceUnavailable(
                    "No video capture adapter supports source ${settings.captureSource.id.value}.",
                ),
            )
        return adapter.frames(settings)
    }
}
