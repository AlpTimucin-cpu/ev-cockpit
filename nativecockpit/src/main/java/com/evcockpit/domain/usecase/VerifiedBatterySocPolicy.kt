package com.evcockpit.domain.usecase

import com.evcockpit.domain.model.CoreEvidence
import com.evcockpit.domain.model.EvidenceClass
import com.evcockpit.domain.model.EvidenceSource
import com.evcockpit.domain.model.VehicleTelemetry
import kotlin.math.abs

/**
 * Battery SoC is not the generic Mode 01 speed PID, a raw UDS payload,
 * a user scenario, nor a value guessed from an unknown CAN frame.
 *
 * The future car-specific adapter must supply BOTH a timestamped value and
 * a matching VERIFIED_OBD measurement-evidence record after bench/car QA.
 * Current ELM327 reader does not produce that evidence; thus SoC stays unknown.
 */
object VerifiedBatterySocPolicy {
    const val MAX_AGE_MS = 10_000L

    data class Reading(val percent: Double, val measuredAtMs: Long)

    fun read(telemetry: VehicleTelemetry, nowMs: Long): Reading? {
        val evidence = telemetry.socEvidence as? CoreEvidence.Available<Double>
            ?: return null
        if (evidence.source != EvidenceSource.VERIFIED_OBD ||
            evidence.classification != EvidenceClass.MEASUREMENT) return null

        val measuredAt = telemetry.socPercent.measuredAtMillis ?: return null
        if (measuredAt != evidence.recordedAtMillis) return null
        val value = telemetry.socPercent.current(nowMs, MAX_AGE_MS) ?: return null
        val approved = evidence.currentOrNull(nowMs, MAX_AGE_MS) ?: return null
        if (!value.isFinite() || !approved.isFinite() || value !in 0.0..100.0 ||
            approved !in 0.0..100.0 || abs(value - approved) > 0.001
        ) return null
        return Reading(value, measuredAt)
    }
}
