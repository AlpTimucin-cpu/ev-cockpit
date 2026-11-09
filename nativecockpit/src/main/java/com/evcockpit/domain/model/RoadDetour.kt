package com.evcockpit.domain.model

/** Road-network check only: does not imply charger occupancy, access or uptime. */
data class RoadDetour(
    val routeId: String,
    val stationId: String,
    val additionalKm: Double,
    val roadDistanceToStationKm: Double,
    val totalViaDistanceKm: Double,
    val additionalMinutes: Double?,
    val verifiedAtMillis: Long
) {
    fun validFor(routeId: String, stationId: String): Boolean =
        this.routeId == routeId && this.stationId == stationId &&
            this.routeId.isNotBlank() && this.stationId.isNotBlank() &&
            additionalKm.isFinite() && additionalKm in 0.0..100.0 &&
            roadDistanceToStationKm.isFinite() && roadDistanceToStationKm > 0.0 &&
            totalViaDistanceKm.isFinite() &&
            totalViaDistanceKm >= roadDistanceToStationKm &&
            verifiedAtMillis > 0L
}

data class RoadDetourCatalog(
    val routeId: String? = null,
    val checked: List<RoadDetour> = emptyList(),
    val candidates: List<ChargingStation> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null
) {
    fun evidence(stationId: String): RoadDetour? =
        checked.firstOrNull { it.validFor(routeId.orEmpty(), stationId) }
}
