package com.evcockpit.domain.usecase

import com.evcockpit.domain.model.*
import kotlin.math.*

/** Approximate GPS projection onto real OSRM GeoJSON, not lane-level map matching. */
class RouteProgressTracker {
    private data class Projection(
        val meters: Double,
        val offset: Double,
        val segment: Int
    )

    private var cachedId: String? = null
    private var coords: List<GeoCoordinate> = emptyList()
    private var distanceMeters = doubleArrayOf()
    private var total = 0.0
    private var maneuvers = emptyList<Pair<NavigationManeuver, Double>>()
    private var lastMatchedSegment: Int? = null

    private fun load(route: RouteSnapshot) {
        if (route.routeId == cachedId && coords === route.routePoints) return
        cachedId = route.routeId
        coords = route.routePoints
        lastMatchedSegment = null
        distanceMeters = DoubleArray(coords.size)
        for (i in 1 until coords.size) {
            distanceMeters[i] = distanceMeters[i - 1] +
                haversine(coords[i - 1], coords[i])
        }
        total = distanceMeters.lastOrNull() ?: 0.0
        maneuvers = route.maneuvers.mapNotNull { maneuver ->
            project(maneuver.location, windowed = false)
                ?.takeIf { it.offset <= 75.0 }
                ?.let { maneuver to it.meters }
        }.sortedBy { it.second }
    }

    /**
     * Computes distance to the real route even when outside the normal 35m snap gate.
     * Null means missing, inaccurate, stale or invalid GPS/route; never zero-fills it.
     */
    fun offRouteMeters(route: RouteSnapshot?, fix: GpsFix?, nowMs: Long): Double? {
        if (route == null || route.routeId.isNullOrBlank() || fix?.usable(nowMs) != true ||
            fix.accuracyMeters > 35f || !validRouteGeometry(route.routePoints)
        ) return null
        load(route)
        return project(fix.coordinate, windowed = true)?.offset
    }

    fun enrich(route: RouteSnapshot?, fix: GpsFix?, nowMs: Long): RouteSnapshot? {
        if (route == null) return null
        val empty = route.copy(
            remainingKm = null, estimatedArrivalEpochMs = null,
            estimatedDriveMinutes = null,
            nextManeuver = null, nextTurnMeters = null,
            nextTurnInstruction = null, gpsProgressFraction = null
        )
        if (fix?.usable(nowMs) != true || fix.accuracyMeters > 35f ||
            !validRouteGeometry(route.routePoints)
        ) return empty
        load(route)
        if (total <= 0.0) return empty
        val location = project(fix.coordinate, windowed = true) ?: return empty
        if (location.offset > 35.0) return empty // do not snap parallel roads
        lastMatchedSegment = location.segment
        val fraction = (location.meters / total).coerceIn(0.0, 1.0)
        val distanceKm = route.totalKm?.takeIf { it.isFinite() && it >= 0 } ?: return empty
        val duration = route.osrmDurationSeconds?.takeIf { it.isFinite() && it > 0 }
            ?: return empty
        val remaining = distanceKm * (1.0 - fraction)
        val remainingSeconds = duration * (1.0 - fraction)
        val next = maneuvers.firstOrNull { it.second >= location.meters - 9.0 }
        return empty.copy(
            remainingKm = remaining,
            estimatedDriveMinutes = ceil(remainingSeconds / 60.0).toLong(),
            estimatedArrivalEpochMs = nowMs + (remainingSeconds * 1000).toLong(),
            gpsProgressFraction = fraction.toFloat(),
            nextManeuver = next?.first,
            nextTurnInstruction = next?.first?.instructionTr,
            nextTurnMeters = next?.let { (it.second - location.meters).coerceAtLeast(0.0) }
        )
    }

    private fun project(position: GeoCoordinate, windowed: Boolean): Projection? {
        if (coords.size < 2 || distanceMeters.size != coords.size) return null
        val cosLat = cos(Math.toRadians(position.latitude))
        val unitX = 111_320.0 * cosLat
        val unitY = 111_195.0

        fun findIn(first: Int, lastExclusive: Int): Projection? {
            var best = Double.POSITIVE_INFINITY
            var along = 0.0
            var matched = -1
            for (i in first until lastExclusive) {
                val a = coords[i]
                val b = coords[i + 1]
                val ax = (a.longitude - position.longitude) * unitX
                val ay = (a.latitude - position.latitude) * unitY
                val dx = (b.longitude - a.longitude) * unitX
                val dy = (b.latitude - a.latitude) * unitY
                val len2 = dx * dx + dy * dy
                if (!len2.isFinite() || len2 < 0.01) continue
                val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                val offset = hypot(ax + t * dx, ay + t * dy)
                if (offset < best) {
                    best = offset
                    matched = i
                    along = distanceMeters[i] + t * (distanceMeters[i + 1] - distanceMeters[i])
                }
            }
            return if (matched >= 0 && best.isFinite()) {
                Projection(along, best, matched)
            } else null
        }

        val last = coords.lastIndex
        val anchor = lastMatchedSegment
        if (windowed && anchor != null) {
            val near = findIn(maxOf(0, anchor - 80), minOf(last, anchor + 81))
            // Fall back to the full route if the vehicle moved outside the window.
            // This avoids false "off route" results after GPS jumps or long gaps.
            if (near != null && near.offset <= 150.0) return near
        }
        return findIn(0, last)
    }

    private fun haversine(a: GeoCoordinate, b: GeoCoordinate): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(b.longitude - a.longitude)
        val t = sin(deltaLat / 2).pow(2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
        return 2.0 * 6_371_000.0 * asin(sqrt(t.coerceIn(0.0, 1.0)))
    }
}
