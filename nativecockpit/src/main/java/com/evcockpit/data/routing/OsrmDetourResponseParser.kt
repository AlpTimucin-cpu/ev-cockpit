package com.evcockpit.data.routing

import com.evcockpit.domain.model.RoadDetour
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.abs

/**
 * A route through [origin, station, destination] must provide two actual
 * road legs. OSRM's snapped middle waypoint must be near the EPDK coordinate.
 */
internal object OsrmDetourResponseParser {
    fun parse(
        json: String,
        routeId: String,
        stationId: String,
        baselineKm: Double,
        baselineSeconds: Double?,
        checkedAtMillis: Long
    ): RoadDetour? {
        if (json.length !in 20..200_000 || routeId.isBlank() ||
            stationId.isBlank() || !baselineKm.isFinite() ||
            baselineKm !in 0.05..5_000.0 || checkedAtMillis <= 0L
        ) return null
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            if (root.primitiveString("code") != "Ok") return null
            val waypoints = root.getAsJsonArray("waypoints") ?: return null
            if (waypoints.size() != 3) return null
            val middle = waypoints[1].asJsonObject
            // OSRM can otherwise snap an off-road charger to a distant motorway.
            val snapMeters = middle.finite("distance") ?: return null
            if (snapMeters !in 0.0..500.0) return null
            val routes = root.getAsJsonArray("routes") ?: return null
            if (routes.size() != 1) return null
            val via = routes[0].asJsonObject
            val totalMeters = via.finite("distance") ?: return null
            val legs = via.getAsJsonArray("legs") ?: return null
            if (legs.size() != 2) return null
            val first = legs[0].asJsonObject.finite("distance") ?: return null
            val second = legs[1].asJsonObject.finite("distance") ?: return null
            if (first <= 0.0 || second <= 0.0 || totalMeters !in 50.0..5_000_000.0 ||
                abs(first + second - totalMeters) > 100.0
            ) return null
            val differenceKm = totalMeters / 1000.0 - baselineKm
            // A different OSRM alternative may be shorter than the selected
            // baseline: don't mislabel that as a measured positive detour.
            if (!differenceKm.isFinite() || differenceKm !in 0.0..100.0) return null
            val extraMinutes = via.finite("duration")
                ?.takeIf { it in 10.0..259_200.0 }
                ?.let { seconds ->
                    baselineSeconds?.takeIf { it.isFinite() && it > 0.0 }
                        ?.let { base -> (seconds - base).takeIf { delta -> delta >= 0.0 } }
                        ?.div(60.0)
                }
            RoadDetour(
                routeId, stationId,
                additionalKm = differenceKm,
                roadDistanceToStationKm = first / 1000.0,
                totalViaDistanceKm = totalMeters / 1000.0,
                additionalMinutes = extraMinutes,
                verifiedAtMillis = checkedAtMillis
            )
        } catch (_: RuntimeException) { null }
    }

    private fun JsonObject.finite(key: String): Double? = try {
        get(key)?.takeIf { !it.isJsonNull }
            ?.asDouble?.takeIf { it.isFinite() }
    } catch (_: RuntimeException) { null }

    private fun JsonObject.primitiveString(key: String): String? = try {
        get(key)?.takeIf { !it.isJsonNull }?.asString
    } catch (_: RuntimeException) { null }
}
