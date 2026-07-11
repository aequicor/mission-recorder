package io.aequicor.capture.core

fun estimateDroppedVideoFrames(
    previousTimestampNanoseconds: Long?,
    timestampNanoseconds: Long,
    frameRate: Int,
): Long {
    require(frameRate > 0) { "Frame rate must be positive." }
    val previous = previousTimestampNanoseconds ?: return 0
    val gap = timestampNanoseconds - previous
    if (gap <= 0) return 0

    val frameInterval = NANOS_PER_SECOND / frameRate
    val roundedIntervals = gap / frameInterval +
        if (gap % frameInterval >= (frameInterval + 1) / 2) 1 else 0
    return (roundedIntervals - 1).coerceAtLeast(0)
}

private const val NANOS_PER_SECOND = 1_000_000_000L
