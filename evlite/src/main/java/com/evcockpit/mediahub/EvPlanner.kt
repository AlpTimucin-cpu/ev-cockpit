package com.evcockpit.mediahub

import kotlin.math.max
import kotlin.math.min

/**
 * Offline, user-input EV planning. No VW credentials, vehicle commands,
 * GPS, Bluetooth or OBD permissions are required.
 *
 * Charging estimates are energy-based planning numbers, NOT DC charge-curve
 * predictions. Charger power below is the chosen AVERAGE delivered to the
 * battery, not the charger's rated maximum.
 */
object EvPlanner {
    const val EVCOCKPIT_PURE_2024_USABLE_KWH = 52.0

    data class Profile(
        val usableKwh: Double = EVCOCKPIT_PURE_2024_USABLE_KWH,
        val socPercent: Double = 60.0,
        val targetPercent: Double = 80.0,
        val reservePercent: Double = 10.0,
        val consumptionKwhPer100Km: Double = 18.0,
        val distanceKm: Double = 100.0,
        val averageChargingKw: Double = 11.0,
        val gridEfficiency: Double = 0.9,
        val priceTlPerKwh: Double = 9.0
    )

    data class Charge(
        val batteryKwh: Double,
        val gridKwh: Double,
        val costTl: Double,
        val idealHoursAtChosenAverageKw: Double
    )

    data class Trip(
        val currentRangeKm: Double,
        val usableRangeUntilReserveKm: Double,
        val tripConsumptionKwh: Double,
        val arrivalPercentWithoutCharge: Double,
        val batteryKwhRequiredToArriveWithReserve: Double,
        val gridKwhForRequiredStop: Double,
        val nominalChargeCostTl: Double,
        val canArriveWithReserve: Boolean
    )

    fun valid(p: Profile): Boolean =
        p.usableKwh in 10.0..200.0 && p.socPercent in 0.0..100.0 &&
        p.targetPercent in 0.0..100.0 && p.reservePercent in 0.0..50.0 &&
        p.consumptionKwhPer100Km in 5.0..60.0 &&
        p.distanceKm in 0.0..3000.0 &&
        p.averageChargingKw in 0.5..400.0 &&
        p.gridEfficiency in 0.60..1.0 &&
        p.priceTlPerKwh in 0.0..200.0

    fun charge(p: Profile): Charge {
        require(valid(p)) { "EV plan inputs invalid" }
        val delta = max(0.0, p.targetPercent - p.socPercent)
        val batteryKwh = p.usableKwh * delta / 100.0
        val gridKwh = batteryKwh / p.gridEfficiency
        return Charge(batteryKwh, gridKwh, gridKwh * p.priceTlPerKwh,
            batteryKwh / p.averageChargingKw)
    }

    fun trip(p: Profile): Trip {
        require(valid(p)) { "EV plan inputs invalid" }
        val consumed = p.distanceKm * p.consumptionKwhPer100Km / 100.0
        val initialBatteryKwh = p.usableKwh * p.socPercent / 100.0
        val reserveKwh = p.usableKwh * p.reservePercent / 100.0
        val deficit = max(0.0, consumed + reserveKwh - initialBatteryKwh)
        val grid = deficit / p.gridEfficiency
        return Trip(
            currentRangeKm = initialBatteryKwh / p.consumptionKwhPer100Km * 100.0,
            usableRangeUntilReserveKm = max(0.0,
                (initialBatteryKwh - reserveKwh) / p.consumptionKwhPer100Km * 100.0),
            tripConsumptionKwh = consumed,
            arrivalPercentWithoutCharge =
                p.socPercent - consumed / p.usableKwh * 100.0,
            batteryKwhRequiredToArriveWithReserve = deficit,
            gridKwhForRequiredStop = grid,
            nominalChargeCostTl = grid * p.priceTlPerKwh,
            canArriveWithReserve = deficit < 1e-8
        )
    }

    /** An estimate only when capacity is measured independently; no SoH claim. */
    fun indicativeCapacityRatio(measuredUsableKwh: Double, referenceKwh: Double): Double? =
        if (measuredUsableKwh > 0.0 && referenceKwh > 0.0 &&
            measuredUsableKwh.isFinite() && referenceKwh.isFinite())
            (measuredUsableKwh / referenceKwh) * 100.0
        else null

    fun chargingSessionsTotal(sessions: List<ChargeSession>): Pair<Double, Double> =
        sessions.sumOf { it.gridKwh } to sessions.sumOf { it.costTl }
}

data class ChargeSession(
    val createdAtUtcMs: Long,
    val description: String,
    val gridKwh: Double,
    val costTl: Double
)
