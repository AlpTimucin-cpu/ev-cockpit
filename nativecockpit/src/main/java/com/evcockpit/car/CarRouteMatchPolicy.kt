package com.evcockpit.car

import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.model.RouteSnapshot
import com.evcockpit.domain.model.validRouteGeometry
import com.evcockpit.domain.usecase.RouteDeviationPolicy
import com.evcockpit.domain.usecase.RouteProgressTracker

/**
 * A measured fix must be matched to the real OSRM geometry before a maneuver
 * is shown or spoken. No road/lane matching, mock fix or estimated GPS position.
 */
internal enum class CarRouteMatch {
    NO_VALID_FIX,
    ON_ROUTE,
    VERIFYING,
    CONFIRMED_OFF_ROUTE
}

/**
 * RouteDeviationPolicy requires five DISTINCT, fresh, accurate fixes >150 m
 * from the route. An isolated GPS jump must not announce an off-route event.
 */
internal class CarRouteMatchPolicy(
    private val progress: RouteProgressTracker = RouteProgressTracker(),
    private val deviation: RouteDeviationPolicy = RouteDeviationPolicy()
) {
    private var trackedRouteId: String? = null
    private var offRouteConfirmed = false
    var state: CarRouteMatch = CarRouteMatch.NO_VALID_FIX
        private set

    fun reset() {
        trackedRouteId = null
        offRouteConfirmed = false
        deviation.reset()
        state = CarRouteMatch.NO_VALID_FIX
    }

    fun observe(route: RouteSnapshot?, fix: GpsFix?, nowMs: Long): CarRouteMatch {
        val id = route?.routeId?.takeIf { it.isNotBlank() }
        if (route == null || id == null || !validRouteGeometry(route.routePoints)) {
            reset()
            return state
        }
        if (id != trackedRouteId) {
            trackedRouteId = id
            offRouteConfirmed = false
            deviation.reset()
        }
        val distance = progress.offRouteMeters(route, fix, nowMs)
        if (distance == null) {
            state = CarRouteMatch.NO_VALID_FIX
            return state
        }
        if (distance <= 35.0) {
            offRouteConfirmed = false
            deviation.reset()
            state = CarRouteMatch.ON_ROUTE
            return state
        }
        if (deviation.observe(route, fix, nowMs)) offRouteConfirmed = true
        state = if (offRouteConfirmed) CarRouteMatch.CONFIRMED_OFF_ROUTE
            else CarRouteMatch.VERIFYING
        return state
    }
}

/**
 * Publish a host loading Trip only once per uncertainty window. Failed IPC
 * retries are bounded, but no failed update is treated as acknowledged.
 */
internal class CarHostLoadingGate {
    private var delivered = false
    private var lastFailureAtMs: Long? = null

    fun clear() {
        delivered = false
        lastFailureAtMs = null
    }

    fun shouldPublish(nowMs: Long): Boolean {
        if (nowMs <= 0 || delivered) return false
        val failure = lastFailureAtMs ?: return true
        return nowMs >= failure && nowMs - failure >= 2_000L
    }

    fun wasPublished(): Boolean = delivered

    fun onPublished() {
        delivered = true
        lastFailureAtMs = null
    }

    fun onFailed(nowMs: Long) {
        lastFailureAtMs = nowMs
    }
}
