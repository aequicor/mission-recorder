package io.aequicor.capture.platform

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Transparency
import java.awt.color.ColorSpace
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.awt.image.ComponentColorModel
import java.awt.image.DataBuffer
import java.awt.image.DataBufferByte
import java.awt.image.Raster
import java.util.ArrayDeque
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Default amount of recent pointer movement kept in a rendered mouse trail. */
public const val DEFAULT_MOUSE_TRAIL_DURATION_MICROS: Long = 2_000_000L

/** One timestamped cursor hotspot used to render a mouse trail. */
public class MouseTrailPoint(
    public val timestampMicros: Long,
    public val x: Int,
    public val y: Int,
) {
    init {
        require(timestampMicros >= 0L) { "Mouse trail timestamps must not be negative." }
    }
}

/**
 * Tracks a bounded mouse route for one capture session and paints it directly over desktop frames.
 *
 * A renderer instance is not thread-safe. Call [update] once per captured frame before drawing.
 */
public class MouseTrailOverlayRenderer(
    private val durationMicros: Long = DEFAULT_MOUSE_TRAIL_DURATION_MICROS,
) {
    private val points = ArrayDeque<MouseTrailPoint>()
    private var frameWidth: Int = 0
    private var frameHeight: Int = 0
    private var latestTimestampMicros: Long = Long.MIN_VALUE

    init {
        require(durationMicros > 0L) { "Mouse trail duration must be positive." }
    }

    /** Updates the recent route and discards points older than the configured duration. */
    public fun update(
        timestampMicros: Long,
        hotspotX: Int?,
        hotspotY: Int?,
        frameWidth: Int,
        frameHeight: Int,
    ) {
        require(timestampMicros >= 0L) { "Mouse trail timestamps must not be negative." }
        require(frameWidth > 0 && frameHeight > 0) { "Mouse trail frame dimensions must be positive." }
        if (
            this.frameWidth != frameWidth || this.frameHeight != frameHeight ||
            timestampMicros < latestTimestampMicros
        ) {
            points.clear()
        }
        this.frameWidth = frameWidth
        this.frameHeight = frameHeight
        latestTimestampMicros = timestampMicros

        val oldestTimestampMicros = (timestampMicros - durationMicros).coerceAtLeast(0L)
        while (points.firstOrNull()?.timestampMicros?.let { it < oldestTimestampMicros } == true) {
            points.removeFirst()
        }
        if (hotspotX == null || hotspotY == null || hotspotX !in 0 until frameWidth || hotspotY !in 0 until frameHeight) {
            return
        }
        val previous = points.lastOrNull()
        if (previous == null || previous.isFarEnoughFrom(hotspotX, hotspotY)) {
            points.addLast(MouseTrailPoint(timestampMicros, hotspotX, hotspotY))
        }
    }

    /** Paints the current route over an RGBA8888 frame. */
    public fun drawRgba(
        pixels: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
    ): Unit = draw(pixels, frameWidth, frameHeight, RGBA_BAND_OFFSETS)

    /** Paints the current route over a BGRA8888 frame. */
    public fun drawBgra(
        pixels: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
    ): Unit = draw(pixels, frameWidth, frameHeight, BGRA_BAND_OFFSETS)

    /** Paints the current route with an existing Java2D graphics context. */
    public fun draw(graphics: Graphics2D): Unit = MouseTrailPainter.draw(
        graphics = graphics,
        points = points.toList(),
        referenceTimestampMicros = latestTimestampMicros,
        durationMicros = durationMicros,
        maximumLengthPixels = MouseTrailPainter.maximumLength(frameWidth, frameHeight),
    )

    internal fun snapshot(): List<MouseTrailPoint> = points.toList()

    private fun draw(
        pixels: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        bandOffsets: IntArray,
    ) {
        if (points.size < 2) return
        val expectedBytes = frameWidth.toLong() * frameHeight * RGBA_CHANNEL_COUNT
        require(frameWidth > 0 && frameHeight > 0 && expectedBytes <= Int.MAX_VALUE && pixels.size >= expectedBytes.toInt()) {
            "Mouse trail requires a complete ${frameWidth}x$frameHeight frame."
        }
        val dataBuffer = DataBufferByte(pixels, pixels.size)
        val raster = Raster.createInterleavedRaster(
            dataBuffer,
            frameWidth,
            frameHeight,
            frameWidth * RGBA_CHANNEL_COUNT,
            RGBA_CHANNEL_COUNT,
            bandOffsets,
            null,
        )
        val image = BufferedImage(RGBA_COLOR_MODEL, raster, false, null)
        val graphics = image.createGraphics()
        try {
            MouseTrailPainter.draw(
                graphics = graphics,
                points = points.toList(),
                referenceTimestampMicros = latestTimestampMicros,
                durationMicros = durationMicros,
                maximumLengthPixels = MouseTrailPainter.maximumLength(frameWidth, frameHeight),
            )
        } finally {
            graphics.dispose()
        }
    }

    private fun MouseTrailPoint.isFarEnoughFrom(x: Int, y: Int): Boolean {
        val deltaX = this.x.toLong() - x
        val deltaY = this.y.toLong() - y
        return deltaX * deltaX + deltaY * deltaY >= MINIMUM_DISTANCE_SQUARED
    }

    private companion object {
        const val MINIMUM_DISTANCE_SQUARED: Long = 4L
        const val RGBA_CHANNEL_COUNT: Int = 4
        val RGBA_BAND_OFFSETS: IntArray = intArrayOf(0, 1, 2, 3)
        val BGRA_BAND_OFFSETS: IntArray = intArrayOf(2, 1, 0, 3)
        val RGBA_COLOR_MODEL: ComponentColorModel = ComponentColorModel(
            ColorSpace.getInstance(ColorSpace.CS_sRGB),
            true,
            false,
            Transparency.TRANSLUCENT,
            DataBuffer.TYPE_BYTE,
        )
    }
}

/** Paints a two-layer time-faded mouse trail whose newest end is the most prominent. */
public object MouseTrailPainter {
    /** Returns the recommended spatial limit for a trail rendered into the supplied frame size. */
    public fun maximumLength(frameWidth: Int, frameHeight: Int): Double {
        require(frameWidth > 0 && frameHeight > 0) { "Mouse trail frame dimensions must be positive." }
        return (min(frameWidth, frameHeight) * MAXIMUM_LENGTH_FRAME_RATIO)
            .coerceIn(MINIMUM_LENGTH_PIXELS, MAXIMUM_LENGTH_PIXELS)
    }

    /**
     * Paints [points] without retaining or mutating the supplied graphics state.
     *
     * Points are limited by [durationMicros] and [maximumLengthPixels]. The newest 0.4 seconds stay
     * prominent, the next 0.3 seconds transition to a medium style, and the remaining route fades
     * rapidly relative to [referenceTimestampMicros]. Within the visible route, style is normalized
     * by traveled distance from a thin pale tail to a thicker saturated end at the cursor so
     * direction remains legible even when the last samples are stationary.
     */
    public fun draw(
        graphics: Graphics2D,
        points: List<MouseTrailPoint>,
        referenceTimestampMicros: Long = points.lastOrNull()?.timestampMicros ?: 0L,
        durationMicros: Long = DEFAULT_MOUSE_TRAIL_DURATION_MICROS,
        maximumLengthPixels: Double = Double.POSITIVE_INFINITY,
    ) {
        require(referenceTimestampMicros >= 0L) { "Mouse trail reference timestamp must not be negative." }
        require(durationMicros > 0L) { "Mouse trail duration must be positive." }
        require(maximumLengthPixels > 0.0) { "Mouse trail maximum length must be positive." }
        if (points.size < 2) return
        val visiblePoints = points
            .takeRecentTime(referenceTimestampMicros, durationMicros)
            .takeRecentDistance(maximumLengthPixels)
        if (visiblePoints.size < 2) return
        val fadingPaths = visiblePoints.toFadingPaths(referenceTimestampMicros, durationMicros)
        if (fadingPaths.isEmpty()) return
        val previousColor = graphics.color
        val previousStroke = graphics.stroke
        val previousRenderingHints = graphics.renderingHints
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        try {
            graphics.drawLayer(
                fadingPaths = fadingPaths,
                fadedWidth = GLOW_FADED_WIDTH,
                activeWidth = GLOW_ACTIVE_WIDTH,
                activeAlpha = GLOW_ACTIVE_ALPHA,
                fadedColor = GLOW_FADED_COLOR,
                activeColor = GLOW_ACTIVE_COLOR,
            )
            graphics.drawLayer(
                fadingPaths = fadingPaths,
                fadedWidth = CORE_FADED_WIDTH,
                activeWidth = CORE_ACTIVE_WIDTH,
                activeAlpha = CORE_ACTIVE_ALPHA,
                fadedColor = CORE_FADED_COLOR,
                activeColor = CORE_ACTIVE_COLOR,
            )
        } finally {
            graphics.color = previousColor
            graphics.stroke = previousStroke
            graphics.setRenderingHints(previousRenderingHints)
        }
    }

    private fun Graphics2D.drawLayer(
        fadingPaths: List<FadingTrailPath>,
        fadedWidth: Double,
        activeWidth: Double,
        activeAlpha: Double,
        fadedColor: Color,
        activeColor: Color,
    ) {
        fadingPaths.forEach { fadingPath ->
            val directionProgress = smoothStep(fadingPath.routeProgress)
            val directionAlpha = interpolate(DIRECTIONAL_TAIL_ALPHA, 1.0, directionProgress)
            val alpha = (activeAlpha * fadingPath.temporalStrength * directionAlpha).roundToInt()
            if (alpha <= 0) return@forEach
            color = interpolateColor(
                start = fadedColor,
                end = activeColor,
                progress = directionProgress,
                alpha = alpha,
            )
            stroke = BasicStroke(
                interpolate(fadedWidth, activeWidth, directionProgress).toFloat(),
                BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_ROUND,
            )
            draw(fadingPath.path)
        }
    }

    private const val TRAIL_BAND_COUNT: Int = 24
    private const val MAXIMUM_LENGTH_FRAME_RATIO: Double = 0.18
    private const val MINIMUM_LENGTH_PIXELS: Double = 96.0
    private const val MAXIMUM_LENGTH_PIXELS: Double = 280.0
    private const val MAIN_PHASE_END_MICROS: Long = 400_000L
    private const val MEDIUM_PHASE_END_MICROS: Long = 700_000L
    private const val MEDIUM_PHASE_STRENGTH: Double = 0.58
    private const val OLD_PHASE_EXPONENT: Double = 1.5
    private const val DIRECTIONAL_TAIL_ALPHA: Double = 0.32
    private const val GLOW_FADED_WIDTH: Double = 8.0
    private const val GLOW_ACTIVE_WIDTH: Double = 24.0
    private const val GLOW_ACTIVE_ALPHA: Double = 48.0
    private const val CORE_FADED_WIDTH: Double = 2.0
    private const val CORE_ACTIVE_WIDTH: Double = 9.0
    private const val CORE_ACTIVE_ALPHA: Double = 190.0
    private val GLOW_FADED_COLOR: Color = Color(204, 232, 247)
    private val GLOW_ACTIVE_COLOR: Color = Color(131, 201, 244)
    private val CORE_FADED_COLOR: Color = Color(157, 210, 239)
    private val CORE_ACTIVE_COLOR: Color = Color(38, 150, 218)

    private data class FadingTrailPath(
        val path: Path2D,
        val temporalStrength: Double,
        val routeProgress: Double,
    )

    private fun List<MouseTrailPoint>.takeRecentTime(
        referenceTimestampMicros: Long,
        durationMicros: Long,
    ): List<MouseTrailPoint> {
        val endIndex = indexOfLast { point -> point.timestampMicros <= referenceTimestampMicros }
        if (endIndex < 0) return emptyList()
        val windowStartMicros = (referenceTimestampMicros - durationMicros).coerceAtLeast(0L)
        val firstInsideIndex = indexOfFirst { point -> point.timestampMicros >= windowStartMicros }
        if (firstInsideIndex < 0 || firstInsideIndex > endIndex) return emptyList()
        val inside = subList(firstInsideIndex, endIndex + 1)
        val previous = getOrNull(firstInsideIndex - 1) ?: return inside
        val firstInside = inside.first()
        if (
            firstInside.timestampMicros == windowStartMicros ||
            firstInside.timestampMicros == previous.timestampMicros
        ) {
            return inside
        }
        val progress = (windowStartMicros - previous.timestampMicros).toDouble() /
            (firstInside.timestampMicros - previous.timestampMicros)
        return listOf(
            MouseTrailPoint(
                timestampMicros = windowStartMicros,
                x = interpolate(previous.x.toDouble(), firstInside.x.toDouble(), progress).roundToInt(),
                y = interpolate(previous.y.toDouble(), firstInside.y.toDouble(), progress).roundToInt(),
            ),
        ) + inside
    }

    private fun List<MouseTrailPoint>.takeRecentDistance(maximumLengthPixels: Double): List<MouseTrailPoint> {
        if (maximumLengthPixels.isInfinite()) return this
        val recentPoints = mutableListOf(last())
        var remainingLength = maximumLengthPixels
        for (index in lastIndex downTo 1) {
            val start = this[index - 1]
            val end = this[index]
            val segmentLength = hypot(end.x.toDouble() - start.x, end.y.toDouble() - start.y)
            if (segmentLength == 0.0) continue
            if (segmentLength <= remainingLength) {
                recentPoints += start
                remainingLength -= segmentLength
                continue
            }
            val ratioFromEnd = remainingLength / segmentLength
            recentPoints += MouseTrailPoint(
                timestampMicros = interpolate(
                    end.timestampMicros.toDouble(),
                    start.timestampMicros.toDouble(),
                    ratioFromEnd,
                ).roundToLong(),
                x = interpolate(end.x.toDouble(), start.x.toDouble(), ratioFromEnd).roundToInt(),
                y = interpolate(end.y.toDouble(), start.y.toDouble(), ratioFromEnd).roundToInt(),
            )
            break
        }
        return recentPoints.asReversed()
    }

    private fun List<MouseTrailPoint>.toFadingPaths(
        referenceTimestampMicros: Long,
        durationMicros: Long,
    ): List<FadingTrailPath> {
        val progressByPoint = normalizedDistanceProgressByPoint()
        val paths = Array(TRAIL_BAND_COUNT) { Path2D.Double() }
        val lastXByBand = DoubleArray(TRAIL_BAND_COUNT) { Double.NaN }
        val lastYByBand = DoubleArray(TRAIL_BAND_COUNT) { Double.NaN }
        val timestampWeightByBand = DoubleArray(TRAIL_BAND_COUNT)
        val lengthByBand = DoubleArray(TRAIL_BAND_COUNT)
        val usedBands = BooleanArray(TRAIL_BAND_COUNT)

        fun appendSegment(
            bandIndex: Int,
            startX: Double,
            startY: Double,
            endX: Double,
            endY: Double,
            timestampMicros: Double,
        ) {
            val segmentLength = hypot(endX - startX, endY - startY)
            if (segmentLength == 0.0) return
            val path = paths[bandIndex]
            if (lastXByBand[bandIndex] != startX || lastYByBand[bandIndex] != startY) {
                path.moveTo(startX, startY)
            }
            path.lineTo(endX, endY)
            lastXByBand[bandIndex] = endX
            lastYByBand[bandIndex] = endY
            timestampWeightByBand[bandIndex] += timestampMicros * segmentLength
            lengthByBand[bandIndex] += segmentLength
            usedBands[bandIndex] = true
        }

        zipWithNext().forEachIndexed { index, (start, end) ->
            if (start.x == end.x && start.y == end.y) return@forEachIndexed
            val startProgress = progressByPoint[index]
            val endProgress = progressByPoint[index + 1]
            if (endProgress <= startProgress) {
                appendSegment(
                    bandIndex = endProgress.toBandIndex(),
                    startX = start.x.toDouble(),
                    startY = start.y.toDouble(),
                    endX = end.x.toDouble(),
                    endY = end.y.toDouble(),
                    timestampMicros = interpolate(
                        start.timestampMicros.toDouble(),
                        end.timestampMicros.toDouble(),
                        0.5,
                    ),
                )
                return@forEachIndexed
            }

            val firstBand = startProgress.toBandIndex()
            val lastBand = endProgress.toBandIndex()
            for (bandIndex in firstBand..lastBand) {
                val bandStart = bandIndex.toDouble() / TRAIL_BAND_COUNT
                val bandEnd = (bandIndex + 1).toDouble() / TRAIL_BAND_COUNT
                val clippedStart = maxOf(startProgress, bandStart)
                val clippedEnd = minOf(endProgress, bandEnd)
                if (clippedEnd <= clippedStart) continue
                val startRatio = (clippedStart - startProgress) / (endProgress - startProgress)
                val endRatio = (clippedEnd - startProgress) / (endProgress - startProgress)
                appendSegment(
                    bandIndex = bandIndex,
                    startX = interpolate(start.x.toDouble(), end.x.toDouble(), startRatio),
                    startY = interpolate(start.y.toDouble(), end.y.toDouble(), startRatio),
                    endX = interpolate(start.x.toDouble(), end.x.toDouble(), endRatio),
                    endY = interpolate(start.y.toDouble(), end.y.toDouble(), endRatio),
                    timestampMicros = interpolate(
                        start.timestampMicros.toDouble(),
                        end.timestampMicros.toDouble(),
                        (startRatio + endRatio) / 2.0,
                    ),
                )
            }
        }

        return paths.indices.mapNotNull { bandIndex ->
            paths[bandIndex].takeIf { usedBands[bandIndex] }?.let { path ->
                val bandProgress = (bandIndex + 0.5) / TRAIL_BAND_COUNT
                val timestampMicros = (timestampWeightByBand[bandIndex] / lengthByBand[bandIndex]).roundToLong()
                val ageMicros = (referenceTimestampMicros - timestampMicros).coerceAtLeast(0L)
                FadingTrailPath(
                    path = path,
                    temporalStrength = temporalStrength(ageMicros, durationMicros),
                    routeProgress = bandProgress,
                )
            }
        }
    }

    private fun temporalStrength(ageMicros: Long, durationMicros: Long): Double {
        if (ageMicros >= durationMicros) return 0.0
        val mainPhaseEnd = min(MAIN_PHASE_END_MICROS, durationMicros)
        if (ageMicros <= mainPhaseEnd) return 1.0
        val mediumPhaseEnd = min(MEDIUM_PHASE_END_MICROS, durationMicros)
        if (ageMicros <= mediumPhaseEnd) {
            val progress = (ageMicros - mainPhaseEnd).toDouble() /
                (mediumPhaseEnd - mainPhaseEnd).coerceAtLeast(1L)
            val endStrength = MEDIUM_PHASE_STRENGTH.takeIf { durationMicros > MEDIUM_PHASE_END_MICROS } ?: 0.0
            return interpolate(1.0, endStrength, smoothStep(progress))
        }
        val progress = (ageMicros - mediumPhaseEnd).toDouble() /
            (durationMicros - mediumPhaseEnd).coerceAtLeast(1L)
        return MEDIUM_PHASE_STRENGTH * (1.0 - progress.coerceIn(0.0, 1.0)).pow(OLD_PHASE_EXPONENT)
    }

    private fun List<MouseTrailPoint>.normalizedDistanceProgressByPoint(): DoubleArray {
        val cumulativeDistance = DoubleArray(size)
        for (index in 1..lastIndex) {
            cumulativeDistance[index] = cumulativeDistance[index - 1] + hypot(
                this[index].x.toDouble() - this[index - 1].x,
                this[index].y.toDouble() - this[index - 1].y,
            )
        }
        val routeLength = cumulativeDistance.last()
        return if (routeLength > 0.0) {
            DoubleArray(size) { index -> cumulativeDistance[index] / routeLength }
        } else {
            DoubleArray(size) { index -> index.toDouble() / lastIndex.coerceAtLeast(1) }
        }
    }

    private fun Double.toBandIndex(): Int =
        (coerceIn(0.0, 1.0) * TRAIL_BAND_COUNT).toInt().coerceAtMost(TRAIL_BAND_COUNT - 1)

    private fun interpolate(start: Double, end: Double, progress: Double): Double =
        start + (end - start) * progress

    private fun interpolateColor(start: Color, end: Color, progress: Double, alpha: Int): Color = Color(
        interpolate(start.red.toDouble(), end.red.toDouble(), progress).roundToInt(),
        interpolate(start.green.toDouble(), end.green.toDouble(), progress).roundToInt(),
        interpolate(start.blue.toDouble(), end.blue.toDouble(), progress).roundToInt(),
        alpha,
    )

    private fun smoothStep(progress: Double): Double = progress * progress * (3.0 - 2.0 * progress)
}
