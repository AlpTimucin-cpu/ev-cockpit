package com.evcockpit.domain.usecase

import com.evcockpit.domain.model.CoreEvidence
import com.evcockpit.domain.model.EvidenceClass
import com.evcockpit.domain.model.EvidenceSource

/**
 * No live writer is connected: neither a validated cumulative BMS discharge
 * counter nor a trip-distance accumulator is available on this ID.4 yet.
 * This pure algorithm is deliberately incapable of making its own evidence.
 */
data class TripEnergyEvidence(
    val distanceStartKm: CoreEvidence.Available<Double>,
    val distanceEndKm: CoreEvidence.Available<Double>,
    val dischargedStartKwh: CoreEvidence.Available<Double>,
    val dischargedEndKwh: CoreEvidence.Available<Double>,
    /** A confirmed charging event invalidates discharge-only calibration. */
    val chargedDuringTrip: Boolean
)

data class TripConsumptionSample(
    val distanceKm: Double,
    val dischargedKwh: Double,
    val consumptionKwhPer100Km: Double,
    val endedAtMillis: Long
)

/** Proposal only, not an automatic rewrite of the user's reference profile. */
data class ConsumptionCalibrationProposal(
    val samples: Int,
    val totalDistanceKm: Double,
    val totalDischargedKwh: Double,
    val estimatedKwhPer100Km: Double
)

object TripEnergyCalibration {
    fun sample(input: TripEnergyEvidence): TripConsumptionSample? {
        if (input.chargedDuringTrip) return null
        fun valid(
            evidence: CoreEvidence.Available<Double>, source: EvidenceSource
        ): Boolean = evidence.source == source &&
            evidence.classification == EvidenceClass.MEASUREMENT &&
            evidence.value.isFinite() && evidence.value >= 0.0

        if (!valid(input.distanceStartKm, EvidenceSource.FUSED_GPS) ||
            !valid(input.distanceEndKm, EvidenceSource.FUSED_GPS) ||
            !valid(input.dischargedStartKwh, EvidenceSource.VERIFIED_OBD) ||
            !valid(input.dischargedEndKwh, EvidenceSource.VERIFIED_OBD)
        ) return null
        val startAt = input.distanceStartKm.recordedAtMillis
        val endAt = input.distanceEndKm.recordedAtMillis
        if (endAt <= startAt || endAt - startAt !in 10 * 60_000L..24 * 60 * 60_000L ||
            kotlin.math.abs(input.dischargedStartKwh.recordedAtMillis - startAt) > 15_000L ||
            kotlin.math.abs(input.dischargedEndKwh.recordedAtMillis - endAt) > 15_000L
        ) return null

        val distance = input.distanceEndKm.value - input.distanceStartKm.value
        val energy = input.dischargedEndKwh.value - input.dischargedStartKwh.value
        if (!distance.isFinite() || distance !in 20.0..600.0 ||
            !energy.isFinite() || energy !in 1.0..120.0
        ) return null
        val consumption = 100.0 * energy / distance
        if (!consumption.isFinite() || consumption !in 5.0..45.0) return null
        return TripConsumptionSample(distance, energy, consumption, endAt)
    }

    /** Weighted by actual distance, never averaging unweighted short trips. */
    fun propose(samples: List<TripConsumptionSample>): ConsumptionCalibrationProposal? {
        if (samples.size < 3 || samples.size > 200 ||
            samples.any {
                !it.distanceKm.isFinite() || it.distanceKm !in 20.0..600.0 ||
                !it.dischargedKwh.isFinite() || it.dischargedKwh !in 1.0..120.0 ||
                it.endedAtMillis <= 0L
            }
        ) return null
        val km = samples.sumOf { it.distanceKm }
        val kwh = samples.sumOf { it.dischargedKwh }
        val result = 100.0 * kwh / km
        if (km < 150.0 || !result.isFinite() || result !in 5.0..45.0)
            return null
        return ConsumptionCalibrationProposal(samples.size, km, kwh, result)
    }
}
