package com.evcockpit.domain.usecase

import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.model.RouteSnapshot

/**
 * Five DISTINCT, fresh and accurate GPS fixes more than 150m from the original
 * OSRM geometry trigger at most one automatic reroute per route ID.
 * This is not an automotive-grade lane-matching algorithm.
 */
class RouteDeviationPolicy(
    private val tracker: RouteProgressTracker = RouteProgressTracker()
) {
    private var activeRouteId: String? = null
    private var consecutive = 0
    private var lastFixTimestamp = 0L
    private var triggered = false

    fun reset() {
        activeRouteId = null
        consecutive = 0
        lastFixTimestamp = 0L
        triggered = false
    }

    fun observe(route: RouteSnapshot?, fix: GpsFix?, nowMs: Long): Boolean {
        val routeId = route?.routeId?.takeIf { it.isNotBlank() }
        if (routeId == null) {
            reset()
            return false
        }
        if (routeId != activeRouteId) {
            activeRouteId = routeId
            consecutive = 0
            lastFixTimestamp = 0L
            triggered = false
        }
        if (triggered || fix == null || fix.recordedAtMillis <= lastFixTimestamp ||
            !fix.usable(nowMs) || fix.accuracyMeters > 35f
        ) return false
        val offset = tracker.offRouteMeters(route, fix, nowMs) ?: return false
        lastFixTimestamp = fix.recordedAtMillis
        consecutive = if (offset > 150.0) consecutive + 1 else 0
        if (consecutive < 5) return false
        triggered = true
        return true
    }
}
