package com.evcockpit.domain.usecase

import com.evcockpit.domain.model.RouteWeatherState
import com.evcockpit.domain.model.WeatherWaypoint

/**
 * Speak a route FORECAST only when its severity rises.
 *
 * Warning selection uses the same geometric-progress fraction as the ETA
 * forecast sites. Unlike the old algorithm, it never announces weather at
 * sites the vehicle already passed, at an expired ETA, or many hours away.
 * Missing verified GPS projection => silence rather than guessing "start".
 */
internal class WeatherChangeAlertGate {
    private var routeId: String? = null
    private var previousSeverity = 0

    fun increasedWarning(
        state: RouteWeatherState,
        nowMs: Long,
        progressFraction: Float?
    ): WeatherWaypoint? {
        val fraction = progressFraction?.takeIf { it.isFinite() && it in 0f..1f }
            ?: return null
        val routeLengthKm = state.routeLengthKm?.takeIf {
            it.isFinite() && it > 0.0
        } ?: return null
        val fetched = state.fetchedAtMillis ?: return null
        if (state.routeId.isNullOrBlank() || state.points.isEmpty() ||
            nowMs < fetched || nowMs - fetched > 45 * 60_000L
        ) return null

        if (routeId != state.routeId) {
            routeId = state.routeId
            previousSeverity = 0
        }

        val distanceKm = routeLengthKm * fraction.toDouble()
        val nearFuture = state.points.filter { point ->
            val eta = point.forecastEtaEpochMs
            point.coordinate.valid() &&
                point.distanceFromStartKm.isFinite() &&
                point.distanceFromStartKm in 0.0..(routeLengthKm + 0.5) &&
                // A stale point behind the vehicle is never a forward warning.
                point.distanceFromStartKm + 0.25 >= distanceKm &&
                eta != null && eta >= nowMs - 60_000L &&
                eta <= nowMs + 3 * 60 * 60_000L &&
                point.windSpeedKmh.isFinite() && point.windSpeedKmh in 0.0..350.0 &&
                point.weatherCode in 0..99
        }
        val strongest = nearFuture.maxByOrNull(::severity)
        val current = strongest?.let(::severity) ?: 0
        val increased = current > previousSeverity && current >= 1
        previousSeverity = current
        return strongest.takeIf { increased }
    }

    /** Domain buckets: normal=0, rain/fog=1, snow=2, storm=3. */
    internal fun severity(point: WeatherWaypoint): Int = when {
        point.weatherCode in setOf(95, 96, 99) || point.windSpeedKmh >= 75.0 -> 3
        point.weatherCode in setOf(71, 73, 75, 77, 85, 86) -> 2
        point.weatherCode in setOf(
            45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82
        ) || point.windSpeedKmh >= 50.0 -> 1
        else -> 0
    }

}
