package io.aequicor.desktop

import java.awt.Dimension
import java.awt.Rectangle

internal fun clampWindowPosition(
    preferred: DesktopWindowPosition,
    windowSize: Dimension,
    screenBounds: List<Rectangle>,
): DesktopWindowPosition {
    val usableScreens = screenBounds.filter { bounds -> bounds.width > 0 && bounds.height > 0 }
    if (usableScreens.isEmpty()) {
        return preferred
    }

    val width = windowSize.width.coerceAtLeast(1)
    val height = windowSize.height.coerceAtLeast(1)
    return usableScreens
        .map { bounds ->
            val candidate = DesktopWindowPosition(
                x = clampCoordinate(preferred.x, bounds.x, bounds.width, width),
                y = clampCoordinate(preferred.y, bounds.y, bounds.height, height),
            )
            PlacementCandidate(
                position = candidate,
                distanceSquared = squaredDistance(preferred, candidate),
            )
        }
        .minBy(PlacementCandidate::distanceSquared)
        .position
}

private fun clampCoordinate(
    preferred: Int,
    screenOrigin: Int,
    screenLength: Int,
    windowLength: Int,
): Int {
    if (windowLength >= screenLength) {
        return screenOrigin
    }
    val maximum = screenOrigin + screenLength - windowLength
    return preferred.coerceIn(screenOrigin, maximum)
}

private fun squaredDistance(
    first: DesktopWindowPosition,
    second: DesktopWindowPosition,
): Double {
    val deltaX = first.x.toDouble() - second.x.toDouble()
    val deltaY = first.y.toDouble() - second.y.toDouble()
    return deltaX * deltaX + deltaY * deltaY
}

private data class PlacementCandidate(
    val position: DesktopWindowPosition,
    val distanceSquared: Double,
)
