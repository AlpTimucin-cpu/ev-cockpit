package com.evcockpit.car

import com.evcockpit.domain.model.GpsFix
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process, opt-in navigation continuity for one real route.
 * No GPS coordinates, OBD values, or stale maneuvers are persisted to disk.
 * A killed process cannot silently resurrect a journey.
 */
internal enum class CarContinuityPhase {
    OFF,
    STARTING,
    ACTIVE,
    GPS_UNAVAILABLE,
    PHONE_ACTION_REQUIRED
}

internal data class CarContinuityState(
    val routeId: String? = null,
    val phase: CarContinuityPhase = CarContinuityPhase.OFF,
    val lastGpsAtMs: Long? = null
) {
    val hasRunningService: Boolean get() =
        phase == CarContinuityPhase.ACTIVE ||
            phase == CarContinuityPhase.GPS_UNAVAILABLE
}

/**
 * Service ownership is token-based: delayed onDestroy/stop from a previous
 * service can never cancel its replacement, even for the same routeId.
 */
@Singleton
internal class CarNavigationContinuity @Inject constructor() {
    private val mutable = MutableStateFlow(CarContinuityState())
    val state: StateFlow<CarContinuityState> = mutable.asStateFlow()
    private var serviceOwner: Any? = null

    @Synchronized
    fun hostStarted(routeId: String): Boolean {
        if (routeId.isBlank()) return false
        val current = mutable.value
        if (current.routeId == routeId && current.hasRunningService) return true
        // Only an explicit host user action authorizes continuity.
        serviceOwner = null
        mutable.value = CarContinuityState(routeId, CarContinuityPhase.STARTING)
        return true
    }

    @Synchronized
    fun launchRestricted(routeId: String) {
        if (mutable.value.routeId != routeId || serviceOwner != null) return
        mutable.value = CarContinuityState(
            routeId, CarContinuityPhase.PHONE_ACTION_REQUIRED
        )
    }

    /** Retry from a VISIBLE phone Activity after a denied car-host FGS start. */
    @Synchronized
    fun retryFromPhone(routeId: String): Boolean {
        if (mutable.value.routeId != routeId ||
            mutable.value.phase != CarContinuityPhase.PHONE_ACTION_REQUIRED
        ) return false
        mutable.value = CarContinuityState(routeId, CarContinuityPhase.STARTING)
        return true
    }

    @Synchronized
    fun serviceStarted(routeId: String, token: Any): Boolean {
        if (routeId.isBlank() || mutable.value.routeId != routeId ||
            mutable.value.phase == CarContinuityPhase.OFF
        ) return false
        serviceOwner = token
        mutable.value = CarContinuityState(
            routeId, CarContinuityPhase.GPS_UNAVAILABLE
        )
        return true
    }

    @Synchronized
    fun serviceGps(routeId: String, token: Any, fix: GpsFix?, nowMs: Long) {
        if (serviceOwner !== token || mutable.value.routeId != routeId) return
        // Coarse, mock, future or stale GPS must never become an active position.
        val valid = fix?.takeIf {
            it.usable(nowMs) && it.accuracyMeters <= 35f
        }
        mutable.value = CarContinuityState(
            routeId,
            if (valid != null) CarContinuityPhase.ACTIVE
                else CarContinuityPhase.GPS_UNAVAILABLE,
            valid?.recordedAtMillis
        )
    }

    @Synchronized
    fun canResume(routeId: String?, carFix: GpsFix?, nowMs: Long): Boolean {
        if (routeId.isNullOrBlank() || serviceOwner == null ||
            mutable.value.routeId != routeId ||
            !mutable.value.hasRunningService
        ) return false
        return carFix?.let {
            it.usable(nowMs) && it.accuracyMeters <= 35f
        } == true
    }

    @Synchronized
    fun serviceEnded(token: Any) {
        if (serviceOwner !== token) return
        serviceOwner = null
        mutable.value = CarContinuityState()
    }

    @Synchronized
    fun endRoute(routeId: String?) {
        if (routeId == null || mutable.value.routeId != routeId) return
        serviceOwner = null
        mutable.value = CarContinuityState()
    }
}
