package com.evcockpit.domain.usecase

import com.evcockpit.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class VerifiedBatterySocPolicyTest {
    private val now = 1_800_000_000_000L
    private val measured = now - 2_000L

    private fun verified(value: Double = 70.0, at: Long = measured) =
        VehicleTelemetry(
            socPercent = TimedValue(value, at),
            socEvidence = CoreEvidence.Available(
                value = value,
                recordedAtMillis = at,
                source = EvidenceSource.VERIFIED_OBD,
                classification = EvidenceClass.MEASUREMENT
            )
        )

    @Test fun freshMatchedMeasurementPasses() {
        assertEquals(70.0, VerifiedBatterySocPolicy.read(verified(), now)!!.percent, .0001)
    }

    @Test fun rawUnverifiedValueIsNotBmsTruth() {
        val raw = VehicleTelemetry(socPercent = TimedValue(70.0, measured))
        assertNull(VerifiedBatterySocPolicy.read(raw, now))
    }

    @Test fun assumptionCannotBecomeLiveBms() {
        val scenario = verified().copy(
            socEvidence = CoreEvidence.Available(
                70.0, measured, EvidenceSource.USER_INPUT, EvidenceClass.USER_SCENARIO
            )
        )
        assertNull(VerifiedBatterySocPolicy.read(scenario, now))
    }

    @Test fun timestampValueMismatchAndStalenessAreRejected() {
        assertNull(VerifiedBatterySocPolicy.read(
            verified().copy(socPercent = TimedValue(70.0, measured - 1)), now
        ))
        assertNull(VerifiedBatterySocPolicy.read(
            verified().copy(socPercent = TimedValue(71.0, measured)), now
        ))
        assertNull(VerifiedBatterySocPolicy.read(verified(), now + 12_000L))
        assertNull(VerifiedBatterySocPolicy.read(verified(101.0), now))
        assertNull(VerifiedBatterySocPolicy.read(verified(), now - 5_000L))
    }
}
