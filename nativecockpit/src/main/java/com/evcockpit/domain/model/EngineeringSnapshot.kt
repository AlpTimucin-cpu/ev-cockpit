package com.evcockpit.domain.model

/**
 * Read-only MEB technical observations. Every value has its own timestamp.
 * Neither BMS capacity content nor accumulated energy is an official SoH test.
 */
data class EngineeringSnapshot(
    val socPercent: TimedValue<Double> = TimedValue(),
    val speedKmh: TimedValue<Double> = TimedValue(),
    val packVoltageV: TimedValue<Double> = TimedValue(),
    val packCurrentA: TimedValue<Double> = TimedValue(),
    val temperatureMaxC: TimedValue<Double> = TimedValue(),
    val temperatureMinC: TimedValue<Double> = TimedValue(),
    val cellMaxV: TimedValue<Double> = TimedValue(),
    val cellMinV: TimedValue<Double> = TimedValue(),
    val capacityParameterKwh: TimedValue<Double> = TimedValue(),
    val accumulatedChargedKwh: TimedValue<Double> = TimedValue(),
    val accumulatedDischargedKwh: TimedValue<Double> = TimedValue(),
    val chargeCurrentLimitA: TimedValue<Double> = TimedValue(),
    val lastValidResponseAtMs: Long? = null,
    val unsuccessfulReads: Int = 0
)

data class EngineeringMetrics(
    /** Positive = energy entering the battery, negative = battery discharge. */
    val batteryPowerKw: Double? = null,
    val absoluteCrate: Double? = null,
    val instantaneousConsumptionKwh100Km: Double? = null,
    val batteryCellSpreadMv: Double? = null,
    val batteryTemperatureSpreadC: Double? = null,
    /** Energy-throughput equivalent; NOT full depth-of-discharge cycle count. */
    val dischargeEquivalentFullCycles: Double? = null,
    /** User-specific seasonal estimates at 10.5 / 18 kWh per 100km, NOT measured range. */
    val estimatedSummerRangeKm: Double? = null,
    val estimatedWinterRangeKm: Double? = null
)
