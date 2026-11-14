package com.evcockpit.presentation.media

/**
 * Phone-only video admission. Unknown, inaccurate, or stale velocity fails closed.
 * A passenger confirmation never overrides motion safety or Android Auto policy.
 */
object PassengerMediaSafetyPolicy {
    private const val MAX_SAMPLE_AGE_MS = 3_000L
    private const val MAX_GPS_ACCURACY_METERS = 20f
    private const val MOVING_THRESHOLD_KMH = 1.0

    enum class Reason {
        ALLOWED, ANDROID_AUTO, NO_PASSENGER_CONFIRMATION,
        NO_RELIABLE_SPEED, VEHICLE_MOVING, APP_NOT_FOREGROUND
    }

    data class Observation(
        val speedKmh: Double?,
        val measuredAtMs: Long?,
        val accuracyMeters: Float?,
        val fromVerifiedObd: Boolean
    )

    fun evaluate(
        projectedToAndroidAuto: Boolean,
        passengerConfirmed: Boolean,
        appForeground: Boolean,
        nowMs: Long,
        obd: Observation?,
        gps: Observation?
    ): Reason {
        if (projectedToAndroidAuto) return Reason.ANDROID_AUTO
        if (!appForeground) return Reason.APP_NOT_FOREGROUND
        if (!passengerConfirmed) return Reason.NO_PASSENGER_CONFIRMATION

        fun fresh(observation: Observation?): Boolean {
            val at = observation?.measuredAtMs ?: return false
            val speed = observation.speedKmh ?: return false
            return at <= nowMs && nowMs - at <= MAX_SAMPLE_AGE_MS &&
                speed.isFinite() && speed >= 0.0
        }
        val safeObd = obd?.takeIf { it.fromVerifiedObd && fresh(it) }
        val safeGps = gps?.takeIf {
            fresh(it) && it.accuracyMeters != null &&
                it.accuracyMeters.isFinite() &&
                it.accuracyMeters in 0f..MAX_GPS_ACCURACY_METERS
        }
        // A positive movement indication from either credible source is a veto.
        if (listOfNotNull(safeObd, safeGps).any {
                (it.speedKmh ?: Double.POSITIVE_INFINITY) > MOVING_THRESHOLD_KMH
            }) return Reason.VEHICLE_MOVING
        if (safeObd == null && safeGps == null) return Reason.NO_RELIABLE_SPEED
        return Reason.ALLOWED
    }
}
