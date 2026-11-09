package com.evcockpit.domain.model

/** Geographic coordinates; storage is lat,lon, MapLibre GeoJSON needs lon,lat. */
data class GeoCoordinate(val latitude: Double, val longitude: Double) {
    fun valid(): Boolean = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
}

/** Only measured, accurate and recent GPS fixes may create the vehicle pin. */
data class GpsFix(
    val coordinate: GeoCoordinate,
    val accuracyMeters: Float,
    val recordedAtMillis: Long,
    // Provided ONLY when Android Location exposes a genuine measurement.
    val bearingDegrees: Float? = null,
    val speedMetersPerSecond: Float? = null
) {
    fun usable(now: Long, ttlMillis: Long = 10_000L): Boolean =
        coordinate.valid() && accuracyMeters.isFinite() &&
        accuracyMeters in 0f..40f && recordedAtMillis > 0 &&
        now >= recordedAtMillis && now - recordedAtMillis <= ttlMillis
}

/** No route provider in step 3. Future OSRM must produce a real geometry. */
fun validRouteGeometry(points: List<GeoCoordinate>): Boolean =
    points.size in 2..20_000 && points.all { it.valid() } &&
        points.zipWithNext().any { (a, b) -> a != b }
