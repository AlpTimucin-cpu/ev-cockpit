package com.evcockpit.domain.model

import kotlin.math.abs

/**
 * Pure Kotlin engineering calculations using only validated, timestamped UDS replies.
 * PID scales are community reverse-engineered and must be verified against ODIS before
 * using them for safety/repair decisions. No commands are sent by this class.
 */
object EngineeringMath {
    const val REFERENCE_NET_CAPACITY_KWH = 52.0
    private const val PAIR_TOLERANCE_MS = 12_000L
    private const val LIVE_TTL_MS = 120_000L
    private const val SLOW_TTL_MS = 900_000L
    private const val COUNTER_SCALE = 8583.07123641215

    fun update(s: EngineeringSnapshot, request: String, payloadHex: String?, atMs: Long): EngineeringSnapshot {
        val hex = payloadHex ?: return s.copy(unsuccessfulReads = s.unsuccessfulReads + 1)
        val expected = "62" + request.removePrefix("22").uppercase()
        if (!hex.startsWith(expected) || hex.length % 2 != 0) {
            return s.copy(unsuccessfulReads = s.unsuccessfulReads + 1)
        }
        val data = hex.drop(6).chunked(2).mapNotNull { it.toIntOrNull(16) }
        if (data.size * 2 != hex.length - 6) return s.copy(unsuccessfulReads = s.unsuccessfulReads + 1)
        fun u16(i: Int): Int? = if (data.size >= i + 2) (data[i] shl 8) or data[i + 1] else null
        fun u32(i: Int): Long? = if (data.size >= i + 4) {
            (data[i].toLong() shl 24) or (data[i + 1].toLong() shl 16) or
                (data[i + 2].toLong() shl 8) or data[i + 3].toLong()
        } else null
        fun valid(value: Double?, min: Double, max: Double): TimedValue<Double>? =
            value?.takeIf { it.isFinite() && it in min..max }?.let { TimedValue(it, atMs) }

        val result: EngineeringSnapshot? = when (request) {
            "22028C" -> valid(data.firstOrNull()?.div(2.5), 0.0, 100.0)
                ?.let { s.copy(socPercent = it) }
            "221E3B" -> valid(u16(0)?.div(4.0), 200.0, 550.0)
                ?.let { s.copy(packVoltageV = it) }
            "221E3D" -> valid(u32(0)?.let { (it - 150_000L) / 100.0 }, -1100.0, 1100.0)
                ?.let { s.copy(packCurrentA = it) }
            "221E0E" -> valid(u16(0)?.div(64.0), -40.0, 105.0)
                ?.let { s.copy(temperatureMaxC = it) }
            "221E0F" -> valid(u16(0)?.div(64.0), -40.0, 105.0)
                ?.let { s.copy(temperatureMinC = it) }
            "221E33" -> valid(u16(0)?.div(4096.0), 2.0, 5.0)
                ?.let { s.copy(cellMaxV = it) }
            "221E34" -> valid(u16(0)?.div(4096.0), 2.0, 5.0)
                ?.let { s.copy(cellMinV = it) }
            "221E1B" -> valid(u16(0)?.div(5.0), 0.0, 1000.0)
                ?.let { s.copy(chargeCurrentLimitA = it) }
            "222AB2" -> valid(u32(0)?.div(1310.77 * 1000.0), 35.0, 90.0)
                ?.let { s.copy(capacityParameterKwh = it) }
            "221E32" -> {
                // 19-byte UDS response: 62 1E 32 + 16 data bytes.
                // MEB evDash uses bytes 11..14 and 15..18 of UDS payload.
                val charge = u32(8)?.div(COUNTER_SCALE)
                val dischargeUnsigned = u32(12)
                val signedDischarge = dischargeUnsigned?.let {
                    if (it >= 0x80000000L) it - 0x100000000L else it
                }
                val discharge = signedDischarge?.let { abs(it.toDouble() / COUNTER_SCALE) }
                if (data.size != 16 || charge == null || discharge == null ||
                    charge !in 0.0..1_000_000.0 || discharge !in 0.0..1_000_000.0) null
                else s.copy(
                    accumulatedChargedKwh = TimedValue(charge, atMs),
                    accumulatedDischargedKwh = TimedValue(discharge, atMs)
                )
            }
            else -> null
        }
        return if (result == null) s.copy(unsuccessfulReads = s.unsuccessfulReads + 1)
        else result.copy(lastValidResponseAtMs = atMs, unsuccessfulReads = 0)
    }

    fun calculate(s: EngineeringSnapshot, nowMs: Long): EngineeringMetrics {
        fun pair(a: TimedValue<Double>, b: TimedValue<Double>): Pair<Double, Double>? {
            val ta = a.measuredAtMillis ?: return null
            val tb = b.measuredAtMillis ?: return null
            if (abs(ta - tb) > PAIR_TOLERANCE_MS) return null
            val av = a.current(nowMs, LIVE_TTL_MS) ?: return null
            val bv = b.current(nowMs, LIVE_TTL_MS) ?: return null
            return av to bv
        }
        val vi = pair(s.packVoltageV, s.packCurrentA)
        val cells = pair(s.cellMaxV, s.cellMinV)?.takeIf { it.first >= it.second }
        val temps = pair(s.temperatureMaxC, s.temperatureMinC)?.takeIf { it.first >= it.second }
        val soc = s.socPercent.current(nowMs, LIVE_TTL_MS)
        val discharged = s.accumulatedDischargedKwh.current(nowMs, SLOW_TTL_MS)
        val powerKw = vi?.let { it.first * it.second / 1000.0 }
        val observedSpeed = s.speedKmh.current(nowMs, LIVE_TTL_MS)
        val speedAt = s.speedKmh.measuredAtMillis
        val voltageAt = s.packVoltageV.measuredAtMillis
        val currentAt = s.packCurrentA.measuredAtMillis
        val speed = if (observedSpeed != null && speedAt != null &&
            voltageAt != null && currentAt != null &&
            abs(speedAt - maxOf(voltageAt, currentAt)) <= PAIR_TOLERANCE_MS
        ) observedSpeed else null
        return EngineeringMetrics(
            batteryPowerKw = powerKw,
            absoluteCrate = powerKw?.let { abs(it) / REFERENCE_NET_CAPACITY_KWH },
            instantaneousConsumptionKwh100Km =
                if (powerKw != null && speed != null && speed > 20.0)
                    -powerKw * 100.0 / speed else null,
            batteryCellSpreadMv = cells?.let { (it.first - it.second) * 1000.0 },
            batteryTemperatureSpreadC = temps?.let { it.first - it.second },
            dischargeEquivalentFullCycles = discharged?.div(REFERENCE_NET_CAPACITY_KWH),
            estimatedSummerRangeKm = soc?.let { REFERENCE_NET_CAPACITY_KWH * it / 10.5 },
            estimatedWinterRangeKm = soc?.let { REFERENCE_NET_CAPACITY_KWH * it / 18.0 }
        )
    }
}
