package com.evcockpit.presentation.cockpit

/**
 * A small landscape phone needs a short two-row telemetry summary.
 * Larger screens retain the full technical summary alongside the map.
 * Units are dp, never raw pixels.
 */
internal object CockpitLayoutPolicy {
    fun isCompact(widthDp: Float, heightDp: Float): Boolean =
        widthDp < 760f || heightDp < 400f
}
