package com.evcockpit.car

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Android Auto Surface dimensions and inset rectangles use physical surface pixels.
 * Do not use the phone's display density or a hard-coded head-unit resolution.
 *
 * This class deliberately has no Android dependencies so host geometry can be
 * validated using ordinary JVM tests without pretending to emulate a car.
 */
internal data class CarMapArea(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun clipped(widthPx: Int, heightPx: Int): CarMapArea? {
        if (width <= 0 || height <= 0) return null
        val result = CarMapArea(
            left.coerceIn(0, widthPx),
            top.coerceIn(0, heightPx),
            right.coerceIn(0, widthPx),
            bottom.coerceIn(0, heightPx)
        )
        return result.takeIf { it.width > 0 && it.height > 0 }
    }

    fun intersection(other: CarMapArea): CarMapArea? =
        CarMapArea(
            maxOf(left, other.left),
            maxOf(top, other.top),
            minOf(right, other.right),
            minOf(bottom, other.bottom)
        ).takeIf { it.width > 0 && it.height > 0 }
}

internal data class CarMapInsets(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/**
 * Host-provided screen geometry, not the diagonal size of a physical display.
 * An empty/unknown stable or visible area is not interpreted as an occlusion.
 */
internal data class CarMapViewport(
    val widthPx: Int,
    val heightPx: Int,
    val dpi: Int,
    val visible: CarMapArea? = null,
    val stable: CarMapArea? = null
) {
    init {
        require(widthPx > 0 && heightPx > 0 && dpi > 0)
    }

    private fun dp(value: Float): Int =
        (value * (dpi / 160f)).roundToInt().coerceAtLeast(1)

    fun stylePixels(designPxAt160Dpi: Float): Float =
        (designPxAt160Dpi * (dpi / 160f)).coerceIn(
            designPxAt160Dpi * 0.5f,
            designPxAt160Dpi * 3f
        )

    fun visibleSafeArea(): CarMapArea {
        val full = CarMapArea(0, 0, widthPx, heightPx)
        val a = visible?.clipped(widthPx, heightPx)
        val b = stable?.clipped(widthPx, heightPx)
        // Use their overlap when possible: no route marker behind permanent or
        // transient template overlays. If one is unknown, use the other.
        return when {
            a != null && b != null -> a.intersection(b) ?: b
            a != null -> a
            b != null -> b
            else -> full
        }
    }

    /**
     * Four independent edge paddings. Keep a minimum usable central area
     * rather than requesting an impossible CameraUpdate for a tiny inset.
     */
    fun cameraInsets(): CarMapInsets? {
        val safe = visibleSafeArea()
        if (safe.width < dp(80f) || safe.height < dp(65f)) return null
        val margin = min(dp(16f), min(safe.width / 10, safe.height / 10))
        val left = safe.left + margin
        val top = safe.top + margin
        val right = widthPx - safe.right + margin
        val bottom = heightPx - safe.bottom + margin
        if (widthPx - left - right < dp(50f) ||
            heightPx - top - bottom < dp(45f)
        ) return null
        return CarMapInsets(left, top, right, bottom)
    }
}
