package io.aequicor.media.desktop.ffmpeg

import io.aequicor.capture.core.VideoFramePoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.math.sqrt

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
        if (position == null) return
        val first = firstTimestampMicros ?: timestampMicros.coerceAtLeast(0L).also {
            firstTimestampMicros = it
        }
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

/** Renders one semi-transparent ribbon that tapers from the cursor to the trail's older end. */
internal fun Graphics2D.drawMouseTrail(points: List<RecordedMousePoint>) {
    if (points.size < 2) return
    val previousAntialiasing = getRenderingHint(RenderingHints.KEY_ANTIALIASING)
    val previousStrokeControl = getRenderingHint(RenderingHints.KEY_STROKE_CONTROL)
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    try {
        color = Color(TRAIL_RED, TRAIL_GREEN, TRAIL_BLUE, TRAIL_ALPHA)
        fill(points.toTaperedRibbon())
    } finally {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, previousAntialiasing)
        setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, previousStrokeControl)
    }
}

private fun List<RecordedMousePoint>.toTaperedRibbon(): Path2D {
    val left = indices.map { index -> offsetPoint(index, side = 1) }
    val right = indices.map { index -> offsetPoint(index, side = -1) }
    return Path2D.Double().apply {
        moveTo(left.first().x, left.first().y)
        left.drop(1).forEach { point -> lineTo(point.x, point.y) }
        right.asReversed().forEach { point -> lineTo(point.x, point.y) }
        closePath()
    }
}

private fun List<RecordedMousePoint>.offsetPoint(index: Int, side: Int): TrailPoint {
    val point = this[index]
    val previous = getOrNull(index - 1) ?: point
    val next = getOrNull(index + 1) ?: point
    val deltaX = (next.x - previous.x).toDouble()
    val deltaY = (next.y - previous.y).toDouble()
    val length = sqrt(deltaX * deltaX + deltaY * deltaY)
    val normalX = if (length == 0.0) 0.0 else -deltaY / length
    val normalY = if (length == 0.0) 1.0 else deltaX / length
    val progress = index.toDouble() / lastIndex.coerceAtLeast(1)
    val radius = TRAIL_TAIL_RADIUS + (TRAIL_HEAD_RADIUS - TRAIL_TAIL_RADIUS) * progress
    return TrailPoint(
        x = point.x + normalX * radius * side,
        y = point.y + normalY * radius * side,
    )
}

private data class TrailPoint(
    val x: Double,
    val y: Double,
)

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
private const val TRAIL_TAIL_RADIUS = 3.0
private const val TRAIL_HEAD_RADIUS = 16.0
private const val TRAIL_RED = 38
private const val TRAIL_GREEN = 150
private const val TRAIL_BLUE = 218
private const val TRAIL_ALPHA = 118
