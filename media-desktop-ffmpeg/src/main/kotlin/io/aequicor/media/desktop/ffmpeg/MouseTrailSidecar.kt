package io.aequicor.media.desktop.ffmpeg

import io.aequicor.capture.core.VideoFramePoint
import io.aequicor.capture.platform.MouseTrailPainter
import io.aequicor.capture.platform.MouseTrailPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Graphics2D
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.math.roundToInt

/** Cursor position captured alongside one encoded video frame. */
@Serializable
internal data class RecordedMousePoint(
    val timestampMicros: Long,
    val x: Int,
    val y: Int,
)

/** Collects a decimated cursor path for the optional editor sidecar. */
internal class MouseTrailRecorder {
    private val points = mutableListOf<RecordedMousePoint>()
    private var firstTimestampMicros: Long? = null

    fun record(timestampMicros: Long, position: VideoFramePoint?) {
        val first = firstTimestampMicros ?: timestampMicros.coerceAtLeast(0L).also {
            firstTimestampMicros = it
        }
        if (position == null) return
        val point = RecordedMousePoint(
            timestampMicros = (timestampMicros - first).coerceAtLeast(0L),
            x = position.x,
            y = position.y,
        )
        val previous = points.lastOrNull()
        if (previous == null || shouldKeep(previous, point)) {
            points += point
        }
    }

    fun write(recordingPath: Path): Boolean {
        if (points.isEmpty()) return false
        val target = mouseTrailSidecarPath(recordingPath)
        Files.writeString(
            target,
            mouseTrailJson.encodeToString(
                MouseTrailDocument(
                    samples = points,
                ),
            ),
        )
        return true
    }

    private fun shouldKeep(previous: RecordedMousePoint, current: RecordedMousePoint): Boolean {
        val deltaX = previous.x.toLong() - current.x
        val deltaY = previous.y.toLong() - current.y
        return deltaX * deltaX + deltaY * deltaY >= MINIMUM_DISTANCE_SQUARED ||
            current.timestampMicros - previous.timestampMicros >= MAXIMUM_SAMPLE_INTERVAL_MICROS
    }

    private companion object {
        const val MINIMUM_DISTANCE_SQUARED: Long = 9L
        const val MAXIMUM_SAMPLE_INTERVAL_MICROS: Long = 1_000_000L
    }
}

/** Decoded cursor path for one recorded media asset. */
internal class MouseTrail private constructor(
    private val samples: List<RecordedMousePoint>,
) {
    fun pointsBetween(startMicros: Long, endMicros: Long): List<RecordedMousePoint> {
        if (endMicros < startMicros || samples.isEmpty()) return emptyList()
        val endIndex = samples.indexOfLast { sample -> sample.timestampMicros <= endMicros }
        if (endIndex < 0) return emptyList()
        val startIndex = samples.indexOfLast { sample -> sample.timestampMicros <= startMicros }
            .takeIf { index -> index >= 0 }
            ?: 0
        return samples.subList(startIndex, endIndex + 1)
    }

    fun pointsWithin(startMicros: Long, endMicros: Long): List<RecordedMousePoint> {
        if (endMicros < startMicros || samples.isEmpty()) return emptyList()
        val firstInsideIndex = samples.indexOfFirst { sample -> sample.timestampMicros >= startMicros }
        val endIndex = samples.indexOfLast { sample -> sample.timestampMicros <= endMicros }
        if (firstInsideIndex < 0 || endIndex < firstInsideIndex) return emptyList()
        val inside = samples.subList(firstInsideIndex, endIndex + 1)
        val previous = samples.getOrNull(firstInsideIndex - 1) ?: return inside
        val firstInside = inside.first()
        if (firstInside.timestampMicros == startMicros || firstInside.timestampMicros == previous.timestampMicros) {
            return inside
        }
        val progress = (startMicros - previous.timestampMicros).toDouble() /
            (firstInside.timestampMicros - previous.timestampMicros)
        val boundary = RecordedMousePoint(
            timestampMicros = startMicros,
            x = interpolateCoordinate(previous.x, firstInside.x, progress),
            y = interpolateCoordinate(previous.y, firstInside.y, progress),
        )
        return listOf(boundary) + inside
    }

    companion object {
        fun load(recordingPath: String): MouseTrail? {
            val sidecar = runCatching { mouseTrailSidecarPath(Path.of(recordingPath)) }.getOrNull() ?: return null
            if (!sidecar.exists()) return null
            val document = runCatching {
                mouseTrailJson.decodeFromString<MouseTrailDocument>(Files.readString(sidecar))
            }.getOrNull() ?: return null
            if (document.format != MOUSE_TRAIL_FORMAT || document.version != MOUSE_TRAIL_VERSION) return null
            val samples = document.samples
                .asSequence()
                .filter { sample -> sample.timestampMicros >= 0L }
                .sortedBy(RecordedMousePoint::timestampMicros)
                .toList()
            return MouseTrail(samples).takeIf { trail -> trail.samples.isNotEmpty() }
        }
    }
}

private fun interpolateCoordinate(start: Int, end: Int, progress: Double): Int =
    (start.toDouble() + (end.toDouble() - start) * progress).roundToInt()

/** Renders a time-faded cursor trail whose newest end is the most prominent. */
internal fun Graphics2D.drawMouseTrail(
    points: List<RecordedMousePoint>,
    referenceTimestampMicros: Long = points.lastOrNull()?.timestampMicros ?: 0L,
    maximumLengthPixels: Double = Double.POSITIVE_INFINITY,
) {
    MouseTrailPainter.draw(
        graphics = this,
        points = points.map { point ->
            MouseTrailPoint(
                timestampMicros = point.timestampMicros,
                x = point.x,
                y = point.y,
            )
        },
        referenceTimestampMicros = referenceTimestampMicros,
        maximumLengthPixels = maximumLengthPixels,
    )
}

internal fun mouseTrailSidecarPath(recordingPath: Path): Path =
    recordingPath.resolveSibling("${recordingPath.fileName}$MOUSE_TRAIL_SIDECAR_SUFFIX")

@Serializable
private data class MouseTrailDocument(
    val format: String = MOUSE_TRAIL_FORMAT,
    val version: Int = MOUSE_TRAIL_VERSION,
    val samples: List<RecordedMousePoint>,
)

private val mouseTrailJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private const val MOUSE_TRAIL_FORMAT = "mission-recorder-mouse-trail"
private const val MOUSE_TRAIL_VERSION = 1
private const val MOUSE_TRAIL_SIDECAR_SUFFIX = ".mission-recorder-mouse-trail.json"
