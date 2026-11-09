package com.evcockpit.domain.model

/** Field names match the repository's versioned EPDK public snapshot. */
data class ChargingSocket(
    val socketId: String,
    val type: String,
    val kind: String,
    /** The snapshot stores raw power; do not assume its measurement unit. */
    val powerRaw: String
) {
    val isDc: Boolean get() = kind.trim().equals("DC", ignoreCase = true)
    val isCcs: Boolean get() = type.contains("CCS", ignoreCase = true) ||
        type.contains("COMBO", ignoreCase = true)
}

data class ChargingStation(
    val id: String,
    val name: String,
    val operator: String,
    val address: String,
    val coordinate: GeoCoordinate,
    val sockets: List<ChargingSocket>,
    val sourceVerifiedAtUtc: String
) {
    /** A DC station is not automatically CCS; filters distinguish both. */
    val hasDc: Boolean get() = sockets.any { it.isDc }
    val hasDcCcs: Boolean get() = sockets.any { it.isDc && it.isCcs }
}

enum class StationFilter { ALL, DC, ROUTE_CCS, ROUTE_DC }

/** State is explicit. A network error is not an empty set of stations. */
data class ChargingCatalog(
    val stations: List<ChargingStation> = emptyList(),
    val verifiedAtUtc: String? = null,
    val loading: Boolean = true,
    val staleCache: Boolean = false,
    val error: String? = null
)

data class GeoViewport(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun valid(): Boolean = south.isFinite() && north.isFinite() && west.isFinite() &&
        east.isFinite() && south in -90.0..90.0 && north in -90.0..90.0 &&
        west in -180.0..180.0 && east in -180.0..180.0 && north >= south && east >= west
    fun contains(c: GeoCoordinate): Boolean = c.latitude in south..north &&
        c.longitude in west..east
}
