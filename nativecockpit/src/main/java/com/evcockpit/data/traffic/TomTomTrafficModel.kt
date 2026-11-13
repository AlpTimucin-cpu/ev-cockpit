package com.evcockpit.data.traffic

import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.RouteSnapshot
import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.model.validRouteGeometry
import com.evcockpit.domain.usecase.TrafficFailure
import java.time.Instant
import kotlin.math.*

/** Only provider-returned incident data, scoped to a real GPS-matched OSRM route. */
data class TomTomIncident(
    val coordinate: GeoCoordinate,
    val category: String,
    val description: String,
    val reportedAtMillis: Long?
)
data class TomTomFlowTile(
    val zoom: Int, val x: Int, val y: Int,
    val png: ByteArray, val serverAtMillis: Long
)
data class TomTomTrafficState(
    val routeId: String? = null,
    val fetchedAtMillis: Long? = null,
    /** Provider HTTP response Date, not a claim about sensor/model sample time. */
    val serverAtMillis: Long? = null,
    val incidents: List<TomTomIncident> = emptyList(),
    val flowTiles: List<TomTomFlowTile> = emptyList(),
    val incidentRequestVerified: Boolean = false,
    val failure: TrafficFailure? = null,
    val loading: Boolean = false
) {
    fun freshFor(id: String?, nowMs: Long): Boolean =
        id != null && id == routeId && failure == null &&
            fetchedAtMillis?.let { nowMs >= it && nowMs - it <= 5L * 60_000L } == true &&
            serverAtMillis?.let {
                nowMs >= it - 30_000L && nowMs - it <= 5L * 60_000L
            } == true && (incidentRequestVerified || flowTiles.isNotEmpty())

    fun label(id: String?, nowMs: Long): String {
        if (!freshFor(id, nowMs)) return "Trafik: kullanılamıyor"
        val minutes = (nowMs - requireNotNull(fetchedAtMillis)).coerceAtLeast(0L) / 60_000L
        return "TomTom Traffic · ${minutes} dk önce · olay: ${incidents.size} · akış karo: ${flowTiles.size}"
    }
}

/** Geometrically bounded look-ahead, not free-floating city-wide incidents. */
internal data class TomTomBounds(val west: Double, val south: Double,
    val east: Double, val north: Double) {
    fun query(): String = listOf(west, south, east, north)
        .joinToString(",") { "%.5f".format(java.util.Locale.US, it) }
    fun valid(): Boolean {
        if (!(west in -180.0..180.0 && east in -180.0..180.0 &&
              south in -85.0..85.0 && north in -85.0..85.0 &&
              east > west && north > south)) return false
        // Approximate area in km2; TomTom API maximum is 10,000 km2.
        val width = (east - west) * 111.32 * cos(Math.toRadians((north + south) / 2))
        val height = (north - south) * 111.2
        return width > 0 && height > 0 && width * height < 9_000.0
    }
}
internal data class TomTomTileId(val z: Int, val x: Int, val y: Int) {
    init { require(z in 0..22 && x in 0 until (1 shl z) && y in 0 until (1 shl z)) }
}
internal data class TomTomCorridor(val bounds: TomTomBounds,
    val routePoints: List<GeoCoordinate>, val tileIds: List<TomTomTileId>)

internal object TomTomCorridorPolicy {
    /**
     * Requires validated real route progress supplied by RouteProgressTracker,
     * and a fresh accurate actual GPS fix (checked by caller).
     * At most 40 km ahead for incidents, up to six raster tiles on the route.
     */
    fun from(route: RouteSnapshot, fix: GpsFix, nowMs: Long): TomTomCorridor? {
        if (route.routeId.isNullOrBlank() || !validRouteGeometry(route.routePoints) ||
            !fix.usable(nowMs) || fix.accuracyMeters > 35f) return null
        val progress = route.validProgress()?.toDouble() ?: return null
        val coords = route.routePoints
        val distances = DoubleArray(coords.size)
        for (i in 1 until coords.size) {
            distances[i] = distances[i - 1] + haversine(coords[i - 1], coords[i])
        }
        val total = distances.last()
        if (!total.isFinite() || total < 100.0) return null
        val travelled = total * progress
        val begin = travelled - 1_000.0
        val end = travelled + 40_000.0
        val upcoming = coords.indices.filter { distances[it] in begin..end }.map { coords[it] }
        val close = (listOf(fix.coordinate) + upcoming).take(10_000)
        val west = close.minOf { it.longitude } - 0.018
        val east = close.maxOf { it.longitude } + 0.018
        val south = close.minOf { it.latitude } - 0.018
        val north = close.maxOf { it.latitude } + 0.018
        val bounds = TomTomBounds(west, south, east, north)
        if (!bounds.valid()) return null
        val tileTargets = listOf(travelled, travelled + 45_000.0, travelled + 90_000.0,
            travelled + 135_000.0, travelled + 180_000.0, travelled + 225_000.0)
            .filter { it <= total }
        val tiles = tileTargets.map { distance ->
            val index = distances.indices.minByOrNull { abs(distances[it] - distance) }
                ?: return null
            tileId(coords[index])
        }.distinct().take(6)
        if (tiles.isEmpty()) return null
        return TomTomCorridor(bounds, close, tiles)
    }

    fun tileId(point: GeoCoordinate, zoom: Int = 8): TomTomTileId {
        require(point.valid() && zoom in 0..22)
        val n = 1 shl zoom
        val latitude = point.latitude.coerceIn(-85.051128, 85.051128)
        val latRad = Math.toRadians(latitude)
        val x = floor((point.longitude + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val y = floor((1.0 - asinh(tan(latRad)) / Math.PI) / 2.0 * n)
            .toInt().coerceIn(0, n - 1)
        return TomTomTileId(zoom, x, y)
    }

    fun kmToRoute(point: GeoCoordinate, route: List<GeoCoordinate>): Double {
        if (!point.valid() || route.isEmpty()) return Double.POSITIVE_INFINITY
        return route.minOf { haversine(point, it) } / 1000.0
    }

    private fun haversine(a: GeoCoordinate, b: GeoCoordinate): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat/2).pow(2) + cos(lat1)*cos(lat2)*sin(dLon/2).pow(2)
        return 2 * 6_371_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
