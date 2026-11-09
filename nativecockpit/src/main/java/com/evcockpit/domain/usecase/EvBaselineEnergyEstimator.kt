package com.evcockpit.domain.usecase

import com.evcockpit.domain.model.RouteSnapshot
import com.evcockpit.domain.model.validRouteGeometry
import kotlin.math.ln

/** Real OBD readings and explicitly assumed SoC must never be conflated. */
sealed interface DepartureSoc {
    data class FreshObd(val percent: Double, val measuredAtMs: Long) : DepartureSoc
    data class ExplicitAssumption(val percent: Double) : DepartureSoc
}

enum class ConsumptionBasis { CALIBRATED, REFERENCE_MODEL }

/** All inputs explicit: do not invent EV Cockpit battery capacity or consumption. */
data class BaselineEnergyInputs(
    val route: RouteSnapshot,
    val departureSoc: DepartureSoc,
    val usableBatteryKwh: Double,
    val consumptionWhPerKm: Double,
    val consumptionBasis: ConsumptionBasis,
    val requiredArrivalSocPercent: Double,
    val nowMs: Long,
    // Optional route scenario measurements; absent = preserve original baseline.
    val speedKmh: Double? = null,
    val outsideTemperatureC: Double? = null,
    val climateActive: Boolean = false,
    val calibration: EnergyCalibration = EnergyCalibration()
)

/** Transparent tunable heuristic, NOT an ABRP proprietary model. */
data class EnergyCalibration(
    val speedReferenceKmh: Double = 110.0,
    val speedLogGain: Double = 0.12,
    val degreesPerLogStep: Double = 20.0,
    val coldThresholdC: Double = 15.0,
    val hotThresholdC: Double = 30.0,
    val coldGainPerDegree: Double = 0.012,
    val hotGainPerDegree: Double = 0.008
) {
    fun valid(): Boolean =
        listOf(speedReferenceKmh, speedLogGain, degreesPerLogStep,
            coldThresholdC, hotThresholdC,
            coldGainPerDegree, hotGainPerDegree).all { it.isFinite() } &&
            speedReferenceKmh > 0 && degreesPerLogStep > 0 &&
            speedLogGain in 0.0..1.0 &&
            coldGainPerDegree in 0.0..0.05 &&
            hotGainPerDegree in 0.0..0.05 && coldThresholdC < hotThresholdC
}


/** Pure, testable route-scenario multipliers. Unknown inputs stay neutral. */
fun calculateConsumptionFactors(
    speedKmh: Double?,
    outsideTemperatureC: Double?,
    climateActive: Boolean,
    calibration: EnergyCalibration
): Pair<Double, Double> {
    require(calibration.valid())
    val speedFactor = speedKmh?.let { speed ->
        require(speed.isFinite() && speed in 0.0..250.0)
        if (speed <= calibration.speedReferenceKmh) 1.0
        else (
            1.0 + calibration.speedLogGain * ln(
                1.0 + (speed - calibration.speedReferenceKmh) /
                    calibration.degreesPerLogStep
            )
        ).coerceIn(1.0, 1.8)
    } ?: 1.0

    val climateFactor =
        if (climateActive && outsideTemperatureC != null) {
            val temperature = outsideTemperatureC
            require(temperature.isFinite() && temperature in -40.0..65.0)
            val cold = (calibration.coldThresholdC - temperature).coerceAtLeast(0.0)
            val hot = (temperature - calibration.hotThresholdC).coerceAtLeast(0.0)
            (1.0 + cold * calibration.coldGainPerDegree +
                hot * calibration.hotGainPerDegree).coerceIn(1.0, 1.65)
        } else 1.0
    return speedFactor to climateFactor
}

sealed interface BaselineEnergyResult {
    data class Unavailable(val reason: String) : BaselineEnergyResult
    data class Estimate(
        val routeKm: Double,
        val driveEnergyKwh: Double,
        val arrivalSocPercent: Double,
        val requiredChargingEnergyKwh: Double,
        val chargingStopNeeded: Boolean,
        val usedLiveObd: Boolean,
        val consumptionBasis: ConsumptionBasis,
        val speedMultiplier: Double = 1.0,
        val climateMultiplier: Double = 1.0,
        val effectiveWhPerKm: Double = 0.0,
        val isScenarioProjection: Boolean = false
    ) : BaselineEnergyResult
}

/** Baseline only. No unsupported weather, road-grade, charger or traffic claims. */
object EvBaselineEnergyEstimator {
    private const val MAX_OBD_AGE_MS = 10_000L

    fun estimate(input: BaselineEnergyInputs): BaselineEnergyResult {
        val route = input.route
        val km = route.totalKm
        if (route.routeId.isNullOrBlank() ||
            !validRouteGeometry(route.routePoints) ||
            km == null || !km.isFinite() || km <= 0.0
        ) return BaselineEnergyResult.Unavailable("A validated OSRM route distance is required")

        if (!input.usableBatteryKwh.isFinite() || input.usableBatteryKwh <= 0.0 ||
            !input.consumptionWhPerKm.isFinite() || input.consumptionWhPerKm <= 0.0 ||
            !input.requiredArrivalSocPercent.isFinite() ||
            input.requiredArrivalSocPercent !in 0.0..100.0
        ) return BaselineEnergyResult.Unavailable("Invalid battery, consumption, or reserve data")

        val soc = when (val source = input.departureSoc) {
            is DepartureSoc.FreshObd -> {
                if (source.measuredAtMs > input.nowMs ||
                    input.nowMs - source.measuredAtMs > MAX_OBD_AGE_MS
                ) return BaselineEnergyResult.Unavailable("OBD SoC reading is stale")
                source.percent
            }
            is DepartureSoc.ExplicitAssumption -> source.percent
        }
        if (!soc.isFinite() || soc !in 0.0..100.0)
            return BaselineEnergyResult.Unavailable("Invalid starting SoC")

        if (!input.calibration.valid() ||
            (input.speedKmh != null && (!input.speedKmh.isFinite() ||
                input.speedKmh !in 0.0..250.0)) ||
            (input.outsideTemperatureC != null && (!input.outsideTemperatureC.isFinite() ||
                input.outsideTemperatureC !in -40.0..65.0))
        ) return BaselineEnergyResult.Unavailable("Invalid speed/temperature calibration input")

        val (speedFactor, climateFactor) = calculateConsumptionFactors(
            input.speedKmh,
            input.outsideTemperatureC,
            input.climateActive,
            input.calibration
        )

        val effectiveWhPerKm = input.consumptionWhPerKm * speedFactor * climateFactor
        val driveKwh = km * effectiveWhPerKm / 1000.0
        if (!driveKwh.isFinite())
            return BaselineEnergyResult.Unavailable("Route energy calculation overflowed")
        val arrivalPercent = soc - 100.0 * driveKwh / input.usableBatteryKwh
        val deficit = (input.requiredArrivalSocPercent - arrivalPercent).coerceAtLeast(0.0)
        val chargingKwh = deficit * input.usableBatteryKwh / 100.0
        return BaselineEnergyResult.Estimate(
            routeKm = km,
            driveEnergyKwh = driveKwh,
            arrivalSocPercent = arrivalPercent,
            requiredChargingEnergyKwh = chargingKwh,
            chargingStopNeeded = arrivalPercent < input.requiredArrivalSocPercent,
            usedLiveObd = input.departureSoc is DepartureSoc.FreshObd,
            consumptionBasis = input.consumptionBasis,
            speedMultiplier = speedFactor,
            climateMultiplier = climateFactor,
            effectiveWhPerKm = effectiveWhPerKm,
            isScenarioProjection = input.speedKmh != null ||
                (input.climateActive && input.outsideTemperatureC != null)
        )
    }
}
