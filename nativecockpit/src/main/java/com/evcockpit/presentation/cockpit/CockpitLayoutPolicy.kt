package com.evcockpit.presentation.cockpit

/**
 * A small landscape phone needs a short two-row telemetry summary.
 * Larger screens retain the full technical summary alongside the map.
 * Units are dp, never raw pixels.
 */
internal object CockpitLayoutPolicy {
    fun isCompact(widthDp: Float, heightDp: Float): Boolean =
        widthDp < 760f || heightDp < 400f

    /** A focused map is always the only weighted child in its driving row. */
    fun mapWeight(compact: Boolean, focused: Boolean): Float = when {
        focused -> 1f
        compact -> .67f
        else -> .64f
    }
}
