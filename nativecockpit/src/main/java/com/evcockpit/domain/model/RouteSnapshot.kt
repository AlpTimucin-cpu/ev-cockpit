package com.evcockpit.domain.model

/** OSRM routing-profile speeds, NEVER live measured traffic. */
data class ProfileSpeedSegment(
    val from: GeoCoordinate,
    val to: GeoCoordinate,
    val speedKmh: Double
)

/** Only data from a real route engine and GPS may populate this model.
 * All estimates are distinctly named and nullable.
 */
data class RouteSnapshot(
    val destination: String? = null,
    val remainingKm: Double? = null,
    val estimatedArrivalEpochMs: Long? = null,
    val estimatedDriveMinutes: Long? = null,
    val estimatedArrivalSocPercent: Double? = null,
    val nextTurnInstruction: String? = null,
    val nextTurnMeters: Double? = null,
    val gpsProgressFraction: Float? = null,
    val suggestedDcStops: Int? = null,
    // Supplied by a real router only; must be stable throughout a route.
    val routeId: String? = null,
    val nextManeuver: NavigationManeuver? = null,
    /** Validated real OSRM GeoJSON polyline when a route provider is connected. */
    val routePoints: List<GeoCoordinate> = emptyList(),
    /** Original OSRM measured route distance; unrelated to GPS progress. */
    val totalKm: Double? = null,
    /** Actual route-service steps, not voice text fixtures. */
    val maneuvers: List<NavigationManeuver> = emptyList(),
    /** OSRM duration is not live-traffic aware. */
    val osrmDurationSeconds: Double? = null,
    val profileSpeeds: List<ProfileSpeedSegment> = emptyList()
) {
    // Never transform an absent or out-of-range GPS progress into 0%.
    fun validProgress(): Float? = gpsProgressFraction?.takeIf {
        it.isFinite() && it in 0f..1f
    }
}
