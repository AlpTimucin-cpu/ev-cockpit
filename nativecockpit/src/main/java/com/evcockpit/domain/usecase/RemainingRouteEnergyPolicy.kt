package com.evcockpit.domain.usecase

import com.evcockpit.domain.model.RouteSnapshot
import com.evcockpit.domain.model.VehicleProfileConfig
import com.evcockpit.domain.model.VehicleTelemetry
import kotlin.math.abs
import kotlin.math.max

/**
 * Project a CURRENT verified battery SoC onto the REMAINING driven route only.
 * A whole-trip OSRM distance cannot be subtracted from a mid-trip SoC reading.
 *
 * RouteSnapshot.remainingKm must be derived by the existing GPS/OSRM matcher:
 * no GPS progress -> no estimated arrival SoC.
 */
object RemainingRouteEnergyPolicy {
    fun estimate(
        route: RouteSnapshot?,
        vehicle: VehicleTelemetry,
        profile: VehicleProfileConfig,
        nowMs: Long,
        reserveOverridePercent: Double? = null
    ): BaselineEnergyResult {
        val actualRoute = route?.takeIf { !it.routeId.isNullOrBlank() }
            ?: return BaselineEnergyResult.Unavailable("Doğrulanmış rota bulunamadı")
        if (!profile.readyForEstimate()) {
            return BaselineEnergyResult.Unavailable(
                "Batarya, tüketim ve rezerv için kullanıcı onaylı profil gerekli"
            )
        }
        val fraction = actualRoute.validProgress()?.toDouble()
            ?: return BaselineEnergyResult.Unavailable(
                "Kalan mesafe için güncel ve rotaya eşleşmiş GPS gerekli"
            )
        val fullKm = actualRoute.totalKm
        val remainingKm = actualRoute.remainingKm
        if (fullKm == null || !fullKm.isFinite() || fullKm <= 0.0 ||
            remainingKm == null || !remainingKm.isFinite() ||
            remainingKm <= 0.05 || remainingKm > fullKm
        ) return BaselineEnergyResult.Unavailable(
            "Geçerli kalan OSRM rota mesafesi bulunamadı"
        )
        // Detect stale/rerouted mismatch instead of displaying plausible fake SoC.
        val expected = fullKm * (1.0 - fraction)
        if (abs(remainingKm - expected) > max(0.25, fullKm * 0.005)) {
            return BaselineEnergyResult.Unavailable(
                "GPS ilerlemesi ile kalan rota mesafesi uyuşmuyor"
            )
        }
        val soc = VerifiedBatterySocPolicy.read(vehicle, nowMs)
            ?: return BaselineEnergyResult.Unavailable(
                "Doğrulanmış ve güncel BMS SoC ölçümü yok"
            )
        val capacity = profile.usableBatteryKwh
            ?: return BaselineEnergyResult.Unavailable("Kullanılabilir batarya bilinmiyor")
        val consumption = profile.referenceWhPerKm()
            ?: return BaselineEnergyResult.Unavailable("Referans tüketim bilinmiyor")
        val reserve = reserveOverridePercent ?: profile.arrivalReservePercent
            ?: return BaselineEnergyResult.Unavailable("Varış rezervi bilinmiyor")
        if (!reserve.isFinite() || reserve !in 0.0..50.0) {
            return BaselineEnergyResult.Unavailable("Geçersiz varış rezervi")
        }
        // A momentary speed value is not an estimate of the full future route.
        // No unverified temperature, grade, climate or charge speed factors.
        return EvBaselineEnergyEstimator.estimate(
            BaselineEnergyInputs(
                route = actualRoute.copy(totalKm = remainingKm),
                departureSoc = DepartureSoc.FreshObd(soc.percent, soc.measuredAtMs),
                usableBatteryKwh = capacity,
                consumptionWhPerKm = consumption,
                consumptionBasis = ConsumptionBasis.REFERENCE_MODEL,
                requiredArrivalSocPercent = reserve,
                nowMs = nowMs
            )
        )
    }
}
