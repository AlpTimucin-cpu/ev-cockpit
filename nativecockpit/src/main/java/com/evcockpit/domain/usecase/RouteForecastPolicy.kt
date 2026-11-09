package com.evcockpit.domain.usecase

import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.RouteSnapshot
import com.evcockpit.domain.model.validRouteGeometry
import kotlin.math.*

/** A position on a verified OSRM path, with a MODEL arrival time (never live traffic). */
internal data class ForecastStop(
    val coordinate: GeoCoordinate,
    val distanceFromStartKm: Double,
    val etaEpochMillis: Long
)

/**
 * At most eight sites along the REMAINING route, normally 40 km apart.
 * Long routes increase spacing to cover the destination instead of silently
 * omitting the tail. Progress must come from a fresh, snapped GPS fix.
 */
internal object RouteForecastPolicy {
    private const val SPACING_METERS = 40_000.0
    private const val MAX_STOPS = 8

    fun sample(route: RouteSnapshot, nowMs: Long): List<ForecastStop> {
        if (nowMs <= 0 || route.routeId.isNullOrBlank() ||
            !validRouteGeometry(route.routePoints)
        ) return emptyList()
        val fraction = route.validProgress()?.toDouble() ?: return emptyList()
        val duration = route.osrmDurationSeconds
            ?.takeIf { it.isFinite() && it in 1.0..604_800.0 }
            ?: return emptyList()
        val coordinates = route.routePoints
        val cumulative = DoubleArray(coordinates.size)
        for (i in 1 until coordinates.size) {
            val length = greatCircleMeters(coordinates[i - 1], coordinates[i])
            if (!length.isFinite() || length < 0) return emptyList()
            cumulative[i] = cumulative[i - 1] + length
        }
        val total = cumulative.last()
        if (!total.isFinite() || total <= 1.0) return emptyList()
        val start = total * fraction
        val remaining = total - start
        if (remaining <= 1.0) return emptyList()

        val spacing = max(SPACING_METERS, remaining / (MAX_STOPS - 1))
        val targets = mutableListOf(start)
        var next = start + spacing
        while (next < total - 1.0 && targets.size < MAX_STOPS - 1) {
            targets += next
            next += spacing
        }
        targets += total

        return targets.map { meters ->
            var segment = 0
            while (segment < coordinates.lastIndex - 1 &&
                cumulative[segment + 1] < meters) segment++
            val length = cumulative[segment + 1] - cumulative[segment]
            val weight = if (length > 0.0)
                ((meters - cumulative[segment]) / length).coerceIn(0.0, 1.0)
                else 0.0
            val a = coordinates[segment]
            val b = coordinates[segment + 1]
            val coordinate = if (meters == total) coordinates.last() else GeoCoordinate(
                a.latitude + (b.latitude - a.latitude) * weight,
                a.longitude + (b.longitude - a.longitude) * weight
            )
            // OSRM duration is a route-profile ESTIMATE, not live traffic.
            val secondsUntil = duration * (meters / total - fraction)
            ForecastStop(
                coordinate = coordinate,
                distanceFromStartKm = meters / 1000.0,
                etaEpochMillis = nowMs + (secondsUntil.coerceAtLeast(0.0) * 1000).toLong()
            )
        }
    }

    private fun greatCircleMeters(a: GeoCoordinate, b: GeoCoordinate): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dlat = lat2 - lat1
        val dlon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dlat / 2).pow(2) +
            cos(lat1) * cos(lat2) * sin(dlon / 2).pow(2)
        return 2 * 6_371_008.8 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
