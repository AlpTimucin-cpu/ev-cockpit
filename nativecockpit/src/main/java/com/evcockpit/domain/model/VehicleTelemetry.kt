package com.evcockpit.domain.model

// A timestamp belongs to each measured field, not to the whole packet.
data class TimedValue<T>(
    val value: T? = null,
    val measuredAtMillis: Long? = null
) {
    fun current(nowMillis: Long, ttlMillis: Long = 10_000L): T? {
        val at = measuredAtMillis ?: return null
        return value?.takeIf { nowMillis >= at && nowMillis - at <= ttlMillis }
    }
}

data class VehicleTelemetry(
    val socPercent: TimedValue<Double> = TimedValue(),
    val speedKmh: TimedValue<Double> = TimedValue(),
    val voltageV: TimedValue<Double> = TimedValue(),
    val batteryTemperatureC: TimedValue<Double> = TimedValue(),
    val powerKw: TimedValue<Double> = TimedValue(),
    /**
     * Optional, independently sourced BMS SoC measurement proof.
     * Never populated by raw UDS guesses or a user-entered scenario.
     */
    val socEvidence: CoreEvidence<Double>? = null
)
