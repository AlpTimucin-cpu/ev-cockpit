package com.evcockpit.car

import com.evcockpit.domain.model.ChargingCatalog
import com.evcockpit.domain.model.ChargingStation
import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.model.RoadDetourCatalog
import com.evcockpit.domain.model.RouteSnapshot
import com.evcockpit.domain.model.VehicleProfileConfig
import com.evcockpit.domain.model.VehicleTelemetry
import com.evcockpit.domain.model.validRouteGeometry
import com.evcockpit.domain.usecase.AutomaticDcChargingPlanner
import com.evcockpit.domain.usecase.DCCorridorCandidate
import com.evcockpit.domain.usecase.RouteProgressTracker
import com.evcockpit.domain.usecase.VerifiedBatterySocPolicy
import java.time.Duration
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** An EPDK location is not proof of real-time charger availability or a tariff. */
internal data class CarChargeItem(
    val station: ChargingStation,
    val corridorKmFromStart: Double,
    val verifiedCcsPowerKw: Double?,
    /** Straight-line GPS distance only. It is NOT a driving detour. */
    val directDistanceMeters: Double?,
    /** User-calibrated MODEL only; null unless verified road detour and BMS exist. */
    val modelArrivalSocPercent: Double?
)

internal data class CarChargeList(
    val items: List<CarChargeItem> = emptyList(),
    val message: String? = null,
    val catalogLabel: String = "EPDK verisi kullanılamıyor"
)

/**
 * Pure projection. Heavy corridor indexing must run on Dispatchers.Default,
 * never in Android Auto onGetTemplate() or the UI main thread.
 */
internal object CarChargingListPolicy {
    fun candidates(route: RouteSnapshot?, catalog: ChargingCatalog): List<DCCorridorCandidate> {
        if (route == null || !validRouteGeometry(route.routePoints) ||
            route.routeId.isNullOrBlank() || route.totalKm == null ||
            !route.totalKm.isFinite() || route.totalKm <= 0.0
        ) return emptyList()
        return AutomaticDcChargingPlanner.candidates(route, catalog.stations)
            .filter { it.station.hasDcCcs }
    }

    fun build(
        route: RouteSnapshot?,
        catalog: ChargingCatalog,
        candidates: List<DCCorridorCandidate>,
        fix: GpsFix?,
        telemetry: VehicleTelemetry,
        profile: VehicleProfileConfig,
        roads: RoadDetourCatalog?,
        nowMs: Long,
        hostLimit: Int
    ): CarChargeList {
        val stamp = catalog.verifiedAtUtc?.take(20) ?: "tarih bilinmiyor"
        val label = when {
            catalog.staleCache -> "EPDK önbelleği · $stamp"
            catalog.loading -> "EPDK yükleniyor · $stamp"
            catalog.stations.isNotEmpty() -> "EPDK kaydı · $stamp"
            else -> "EPDK verisi kullanılamıyor"
        }
        if (route == null || route.routeId.isNullOrBlank() ||
            !validRouteGeometry(route.routePoints)
        ) return CarChargeList(message = "Telefondan gerçek OSRM rotası seçin.", catalogLabel = label)
        if (catalog.stations.isEmpty()) return CarChargeList(
            message = if (catalog.loading) "EPDK istasyon verisi yükleniyor."
                else "EPDK istasyon verisi kullanılamıyor.",
            catalogLabel = label
        )
        if (hostLimit <= 0) return CarChargeList(
            message = "Araç ekranı istasyon listesine izin vermiyor.", catalogLabel = label
        )

        val validGps = fix?.takeIf { it.usable(nowMs) && it.accuracyMeters <= 35f }
        val tracker = RouteProgressTracker()
        val fraction = tracker.enrich(route, validGps, nowMs)?.validProgress()
        val originMatched = validGps != null && fraction != null &&
            fraction <= 0.001f &&
            haversine(validGps.coordinate.latitude, validGps.coordinate.longitude,
                route.routePoints.first().latitude, route.routePoints.first().longitude) <= 250.0
        val verifiedSoc = if (originMatched && profile.readyForEstimate())
            VerifiedBatterySocPolicy.read(telemetry, nowMs)?.percent else null
        val consumption = profile.referenceWhPerKm()
        val usableCapacity = profile.usableBatteryKwh
        val routeId = route.routeId
        val selected = candidates.asSequence()
            .filter { it.station.hasDcCcs && it.distanceFromStartKm.isFinite() }
            // Do not hide a charger immediately ahead of current GPS
            // progress. Any station already passed along this route is omitted.
            .filter { fraction == null || it.distanceFromStartKm >
                (route.totalKm ?: 0.0) * fraction }
            .take(hostLimit)
            .map { candidate ->
                val station = candidate.station
                val verifiedPower = station.sockets.asSequence()
                    .filter { it.isDc && it.isCcs }
                    .mapNotNull { explicitPowerKw(it.powerRaw) }
                    .maxOrNull()
                val meters = validGps?.let {
                    haversine(it.coordinate.latitude, it.coordinate.longitude,
                        station.coordinate.latitude, station.coordinate.longitude)
                }
                val detour = roads?.takeIf { it.routeId == routeId }
                    ?.evidence(station.id)
                    ?.takeIf { it.validFor(routeId.orEmpty(), station.id) &&
                        nowMs >= it.verifiedAtMillis &&
                        nowMs - it.verifiedAtMillis <= Duration.ofMinutes(30).toMillis() }
                val soc = if (verifiedSoc != null && detour != null &&
                    usableCapacity != null && usableCapacity > 0.0 &&
                    consumption != null && consumption > 0.0
                ) {
                    val projected = verifiedSoc -
                        detour.roadDistanceToStationKm * consumption /
                        (usableCapacity * 1_000.0) * 100.0
                    projected.takeIf { it.isFinite() && it in 0.0..100.0 }
                } else null
                CarChargeItem(station, candidate.distanceFromStartKm,
                    verifiedPower, meters, soc)
            }.toList()
        return CarChargeList(
            items = selected,
            message = if (selected.isEmpty()) {
                "Rotaya 5 km yakın EPDK DC/CCS istasyonu bulunamadı."
            } else null,
            catalogLabel = label
        )
    }

    fun summary(item: CarChargeItem): String {
        val connector = "DC CCS"
        val power = item.verifiedCcsPowerKw?.let {
            " · " + String.format(Locale.forLanguageTag("tr-TR"), "%.0f kW", it)
        } ?: " · güç kullanılamıyor"
        return connector + power
    }

    fun detail(item: CarChargeItem): String =
        "Fiyat: kullanılamıyor · " +
            (item.modelArrivalSocPercent?.let {
                "Varış SoC: model ≈" +
                    String.format(Locale.forLanguageTag("tr-TR"), "%.0f%%", it)
            } ?: "Varış SoC: kullanılamıyor")

    /**
     * Only a self-describing EPDK value like "150 kW" is accepted.
     * Raw "150" may be W, kW or a provider code, and cannot be converted.
     */
    internal fun explicitPowerKw(raw: String): Double? {
        val m = Regex("^\\s*(\\d+(?:[.,]\\d+)?)\\s*kW\\s*$",
            RegexOption.IGNORE_CASE).matchEntire(raw) ?: return null
        return m.groupValues[1].replace(',', '.').toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in 1.0..700.0 }
    }

    private fun haversine(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val lat1 = Math.toRadians(aLat)
        val lat2 = Math.toRadians(bLat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(bLon - aLon)
        val v = sin(dLat / 2).pow(2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2.0 * 6_371_008.8 * asin(sqrt(v.coerceIn(0.0, 1.0)))
    }
}
