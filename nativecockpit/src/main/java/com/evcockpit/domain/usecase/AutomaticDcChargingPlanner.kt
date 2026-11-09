package com.evcockpit.domain.usecase

import kotlin.math.*
import com.evcockpit.domain.model.*

/** Candidate status, not a guaranteed charger reservation or driving detour. */
sealed interface AutomaticChargePlan {
    data object Empty : AutomaticChargePlan
    data class Unavailable(val reason: String) : AutomaticChargePlan
    data class Estimate(
        val stops: List<ProposedDcStop>,
        val totalSuggestedChargeKwh: Double,
        val assumedChargeTargetPercent: Double = 80.0,
        val source: String = "MODEL/ESTIMATE · OSRM station detours checked; multi-stop route, availability and charger access are not guaranteed"
    ) : AutomaticChargePlan
}

data class ProposedDcStop(
    val station: ChargingStation,
    val distanceFromStartKm: Double,
    val suggestedEnergyKwh: Double,
    val estimatedArrivalSocPercent: Double,
    /** Only if EPDK powerRaw has an EXPLICIT kW unit. Never guess from a bare number. */
    val verifiedPowerKw: Double? = null,
    val verifiedAdditionalRoadKm: Double? = null
)

data class DCCorridorCandidate(
    val station: ChargingStation,
    val distanceFromStartKm: Double,
    /** Null means no road check, NOT zero additional driving. */
    val roadDetour: RoadDetour? = null
)

/** Pure offline feasibility proposal; requires separately calibrated energy inputs. */
object AutomaticDcChargingPlanner {
    fun candidates(route: RouteSnapshot, stations: List<ChargingStation>): List<DCCorridorCandidate> {
        if (route.routeId.isNullOrBlank() || !validRouteGeometry(route.routePoints) ||
            route.totalKm == null || route.totalKm <= 0
        ) return emptyList()
        val path = route.routePoints
        val cumulative = DoubleArray(path.size)
        for (i in 1 until path.size) {
            cumulative[i] = cumulative[i - 1] + haversine(path[i-1], path[i])
        }
        val length = cumulative.last()
        if (length < 1.0) return emptyList()
        val index = StationCorridor.index(path)
        return stations.asSequence()
            .filter(index::isDcAlongRoute)
            .mapNotNull { station ->
                val along = projectedDistance(path, cumulative, station.coordinate)
                val km = along / length * route.totalKm
                km.takeIf { it.isFinite() && it in 1.0..route.totalKm }
                    ?.let { DCCorridorCandidate(station, it) }
            }
            .distinctBy { it.station.id }
            .sortedBy { it.distanceFromStartKm }
            .toList()
    }

    fun plan(
        route: RouteSnapshot,
        corridor: List<DCCorridorCandidate>,
        baseline: BaselineEnergyResult,
        usableBatteryKwh: Double,
        startSocPercent: Double,
        reserveSocPercent: Double,
        chargingTargetPercent: Double = 80.0
    ): AutomaticChargePlan {
        if (baseline !is BaselineEnergyResult.Estimate ||
            route.routeId.isNullOrBlank() || !route.totalKm.isValidPositive() ||
            !usableBatteryKwh.isValidPositive() ||
            !startSocPercent.isFinite() || startSocPercent !in 0.0..100.0 ||
            !reserveSocPercent.isFinite() || reserveSocPercent !in 0.0..100.0 ||
            !chargingTargetPercent.isFinite() || chargingTargetPercent !in 80.0..100.0 ||
            !baseline.effectiveWhPerKm.isValidPositive()
        ) return AutomaticChargePlan.Unavailable("No validated route and energy calibration")

        val confirmedRouteId = requireNotNull(route.routeId)
        val energyPerKm = baseline.effectiveWhPerKm / 1000.0
        val batteryReserve = reserveSocPercent / 100.0 * usableBatteryKwh
        val destinationKm = route.totalKm!!
        var energyKwh = usableBatteryKwh * startSocPercent / 100.0
        var lastKm = 0.0
        // Charge the entire previous detour again on departure: deliberately
        // conservative, NOT a verified multi-stop vehicle itinerary.
        var previousDetourKm = 0.0
        val planned = mutableListOf<ProposedDcStop>()
        val used = HashSet<String>()
        // Charger speeds are NOT inferred from ambiguous EPDK powerRaw.
        // Charging target is an explicit user model setting, not a car command.
        repeat(12) {
            val destinationNeed = (destinationKm - lastKm + previousDetourKm) * energyPerKm
            if (energyKwh - destinationNeed >= batteryReserve) {
                return AutomaticChargePlan.Estimate(
                    planned, planned.sumOf { it.suggestedEnergyKwh },
                    assumedChargeTargetPercent = chargingTargetPercent
                )
            }
            val reachable = corridor.asSequence()
                .filter { it.station.id !in used && it.distanceFromStartKm > lastKm + 1.0 }
                .filter { it.roadDetour?.validFor(confirmedRouteId, it.station.id) == true }
                .filter { candidate ->
                    val extra = requireNotNull(candidate.roadDetour).additionalKm
                    val conservativeLeg = candidate.distanceFromStartKm - lastKm +
                        previousDetourKm + extra
                    val demand = conservativeLeg * energyPerKm
                    energyKwh - demand >= batteryReserve
                }
                .maxByOrNull { it.distanceFromStartKm }
                ?: return AutomaticChargePlan.Unavailable(
                    "No validated DC candidate is reachable while preserving the reserve"
                )

            val verifiedDetour = requireNotNull(reachable.roadDetour)
            val driveEnergy = (reachable.distanceFromStartKm - lastKm +
                previousDetourKm + verifiedDetour.additionalKm) * energyPerKm
            val arrivalKwh = energyKwh - driveEnergy
            val targetKwh = usableBatteryKwh * chargingTargetPercent / 100.0
            val chargeKwh = (targetKwh - arrivalKwh).coerceAtLeast(0.0)
            if (chargeKwh < 0.1) return AutomaticChargePlan.Unavailable(
                "The onward route is unreliable with an assumed 80% charge"
            )

            planned += ProposedDcStop(
                reachable.station,
                reachable.distanceFromStartKm,
                chargeKwh,
                100.0 * arrivalKwh / usableBatteryKwh,
                reachable.station.sockets.asSequence()
                    .filter { it.isDc }
                    .mapNotNull { verifiedDcKw(it.powerRaw) }
                    .maxOrNull(),
                verifiedAdditionalRoadKm = verifiedDetour.additionalKm
            )
            used += reachable.station.id
            energyKwh = targetKwh
            lastKm = reachable.distanceFromStartKm
            previousDetourKm = verifiedDetour.additionalKm
        }
        return AutomaticChargePlan.Unavailable("The route requires too many charging stops")
    }

    /** '150' is ambiguous; '150 kW' is usable metadata, not a charging-time guarantee. */
    private fun verifiedDcKw(raw: String): Double? {
        val match = Regex(
            "^\\s*(\\d+(?:[.,]\\d+)?)\\s*kW\\s*$",
            RegexOption.IGNORE_CASE
        ).matchEntire(raw) ?: return null
        return match.groupValues[1].replace(',', '.').toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in 1.0..700.0 }
    }

    private fun Double?.isValidPositive() = this != null && isFinite() && this > 0.0

    private fun haversine(a: GeoCoordinate, b: GeoCoordinate): Double {
        val p = Math.toRadians(a.latitude)
        val q = Math.toRadians(b.latitude)
        val dLat = q-p
        val dLon = Math.toRadians(b.longitude-a.longitude)
        val z = sin(dLat/2).pow(2) + cos(p)*cos(q)*sin(dLon/2).pow(2)
        return 2.0*6_371_008.8*asin(sqrt(z.coerceIn(0.0,1.0)))
    }

    private fun projectedDistance(
        path: List<GeoCoordinate>,
        cumulative: DoubleArray,
        point: GeoCoordinate
    ): Double {
        val factorLon = 111_320.0 * cos(Math.toRadians(point.latitude))
        val factorLat = 111_195.0
        var nearest = Double.POSITIVE_INFINITY
        var best = 0.0
        for (i in 0 until path.lastIndex) {
            val a = path[i]
            val b = path[i+1]
            val ax=(a.longitude-point.longitude)*factorLon
            val ay=(a.latitude-point.latitude)*factorLat
            val dx=(b.longitude-a.longitude)*factorLon
            val dy=(b.latitude-a.latitude)*factorLat
            val length2=dx*dx+dy*dy
            val fraction=if(length2>0.01) (-(ax*dx+ay*dy)/length2).coerceIn(0.0,1.0) else 0.0
            val off=hypot(ax+fraction*dx,ay+fraction*dy)
            if(off<nearest) {
                nearest=off
                best=cumulative[i]+(cumulative[i+1]-cumulative[i])*fraction
            }
        }
        return best
    }
}
