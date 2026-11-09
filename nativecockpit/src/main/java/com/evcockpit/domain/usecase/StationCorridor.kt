package com.evcockpit.domain.usecase

import kotlin.math.*
import com.evcockpit.domain.model.ChargingStation
import com.evcockpit.domain.model.GeoCoordinate
import org.maplibre.geojson.Point
import org.maplibre.turf.TurfConstants
import org.maplibre.turf.TurfMeasurement
import org.maplibre.turf.TurfMisc

/** Exact projected distance to the real OSRM line; never a driving detour. */
object StationCorridor {
    const val MAX_DISTANCE_METERS = 5_000.0
    private const val EARTH_METERS = 6_371_008.8
    private const val CELL_DEGREES = 0.05
    private const val LAT_PAD = 0.065
    private const val LON_PAD = 0.11

    private fun segmentDistanceMeters(
        station: GeoCoordinate,
        a: GeoCoordinate,
        b: GeoCoordinate
    ): Double {
        if (station.latitude < min(a.latitude, b.latitude) - LAT_PAD ||
            station.latitude > max(a.latitude, b.latitude) + LAT_PAD ||
            station.longitude < min(a.longitude, b.longitude) - LON_PAD ||
            station.longitude > max(a.longitude, b.longitude) + LON_PAD
        ) return Double.POSITIVE_INFINITY

        val latScale = EARTH_METERS * Math.PI / 180.0
        val lonScale = latScale * cos(Math.toRadians(station.latitude))
        val ax = (a.longitude - station.longitude) * lonScale
        val ay = (a.latitude - station.latitude) * latScale
        val bx = (b.longitude - station.longitude) * lonScale
        val by = (b.latitude - station.latitude) * latScale
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 < 1e-9) 0.0
            else (-ax * dx - ay * dy).div(len2).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy)
    }

    /**
     * MapLibre Java Turf has no pointToLineDistance symbol. The equivalent is
     * nearestPointOnLine(point, [segment endpoints]) + TurfMeasurement.distance
     * in metres. Use it AFTER indexed prefiltering for 13k+ EPDK records.
     */
    private fun turfSegmentMeters(
        position: GeoCoordinate, start: GeoCoordinate, end: GeoCoordinate
    ): Double {
        if (start == end) return Double.POSITIVE_INFINITY
        // A point exactly on the segment must remain zero; Turf nearest-point
        // interpolation can introduce small floating-point error here.
        val projected = segmentDistanceMeters(position, start, end)
        if (projected < 0.01) return projected
        val p = Point.fromLngLat(position.longitude, position.latitude)
        val line = listOf(
            Point.fromLngLat(start.longitude, start.latitude),
            Point.fromLngLat(end.longitude, end.latitude)
        )
        val snapped = TurfMisc.nearestPointOnLine(
            p, line, TurfConstants.UNIT_METRES
        ).geometry() as? Point ?: return Double.POSITIVE_INFINITY
        return TurfMeasurement.distance(p, snapped, TurfConstants.UNIT_METRES)
    }

    fun distanceMeters(station: GeoCoordinate, route: List<GeoCoordinate>): Double? {
        if (!station.valid() || route.size < 2 || route.any { !it.valid() }) return null
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until route.lastIndex) {
            best = min(best, turfSegmentMeters(station, route[i], route[i + 1]))
        }
        return best.takeIf { it.isFinite() }
    }

    fun isAlongRoute(station: ChargingStation, route: List<GeoCoordinate>): Boolean =
        station.hasDcCcs &&
            (distanceMeters(station.coordinate, route) ?: Double.POSITIVE_INFINITY) <=
                MAX_DISTANCE_METERS

    // DC is sufficient; never infer kW from an unverified powerRaw string.
    fun isDcAlongRoute(station: ChargingStation, route: List<GeoCoordinate>): Boolean =
        station.hasDc &&
            (distanceMeters(station.coordinate, route) ?: Double.POSITIVE_INFINITY) <=
                MAX_DISTANCE_METERS

    /** Index real route segments into small geographic cells, once per route. */
    class RouteIndex internal constructor(private val route: List<GeoCoordinate>) {
        private val cells = HashMap<Long, MutableList<Int>>()
        private val valid = route.size >= 2 && route.all { it.valid() }

        init {
            if (valid) {
                for (i in 0 until route.lastIndex) {
                    val a = route[i]
                    val b = route[i + 1]
                    val south = cell(min(a.latitude, b.latitude) - LAT_PAD)
                    val north = cell(max(a.latitude, b.latitude) + LAT_PAD)
                    val west = cell(min(a.longitude, b.longitude) - LON_PAD)
                    val east = cell(max(a.longitude, b.longitude) + LON_PAD)
                    for (lat in south..north) {
                        for (lon in west..east) {
                            cells.getOrPut(key(lat, lon)) { ArrayList() }.add(i)
                        }
                    }
                }
            }
        }

        fun isAlongRoute(station: ChargingStation): Boolean =
            nearRoute(station, requireCcs = true)

        fun isDcAlongRoute(station: ChargingStation): Boolean =
            nearRoute(station, requireCcs = false)

        private fun nearRoute(station: ChargingStation, requireCcs: Boolean): Boolean {
            if (!valid || !station.hasDc || (requireCcs && !station.hasDcCcs) ||
                !station.coordinate.valid()) return false
            val p = station.coordinate
            val segmentIndices = cells[key(cell(p.latitude), cell(p.longitude))]
                ?: return false
            return segmentIndices.any { i ->
                // Fast planar reject; Turf computes the accepted geodesic distance.
                segmentDistanceMeters(p, route[i], route[i + 1]) < MAX_DISTANCE_METERS + 200.0 &&
                    turfSegmentMeters(p, route[i], route[i + 1]) <= MAX_DISTANCE_METERS
            }
        }

        private fun cell(v: Double): Int = floor(v / CELL_DEGREES).toInt()
        private fun key(lat: Int, lon: Int): Long =
            (lat.toLong() shl 32) or (lon.toLong() and 0xFFFF_FFFFL)
    }

    fun index(route: List<GeoCoordinate>): RouteIndex = RouteIndex(route)
}
