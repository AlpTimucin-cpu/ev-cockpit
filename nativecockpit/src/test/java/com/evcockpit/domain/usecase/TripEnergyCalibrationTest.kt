package com.evcockpit.domain.usecase

import com.evcockpit.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class TripEnergyCalibrationTest {
    private val now = 1_800_000_000_000L
    private val end = now + 3_600_000L
    private fun gps(km: Double, at: Long) = CoreEvidence.Available(
        km, at, EvidenceSource.FUSED_GPS, EvidenceClass.MEASUREMENT
    )
    private fun energy(kwh: Double, at: Long) = CoreEvidence.Available(
        kwh, at, EvidenceSource.VERIFIED_OBD, EvidenceClass.MEASUREMENT
    )
    private fun evidence(charged: Boolean = false) = TripEnergyEvidence(
        gps(1_000.0, now), gps(1_100.0, end),
        energy(500.0, now), energy(515.0, end), charged
    )

    @Test fun producesOnlyAnExplicitProposalFromMeasuredTripCounters() {
        val sample = TripEnergyCalibration.sample(evidence())!!
        assertEquals(100.0, sample.distanceKm, 0.00001)
        assertEquals(15.0, sample.dischargedKwh, 0.00001)
        assertEquals(15.0, sample.consumptionKwhPer100Km, 0.00001)
        val proposed = TripEnergyCalibration.propose(listOf(sample, sample, sample))!!
        assertEquals(3, proposed.samples)
        assertEquals(300.0, proposed.totalDistanceKm, 0.00001)
        assertEquals(15.0, proposed.estimatedKwhPer100Km, 0.00001)
    }

    @Test fun chargingCounterRollbackAndUnverifiedEnergyCannotCalibrate() {
        assertNull(TripEnergyCalibration.sample(evidence(charged = true)))
        assertNull(TripEnergyCalibration.sample(
            evidence().copy(dischargedEndKwh = energy(490.0, end))
        ))
        val untrusted = evidence().copy(
            dischargedEndKwh = CoreEvidence.Available(
                515.0, end, EvidenceSource.USER_INPUT, EvidenceClass.USER_SCENARIO
            )
        )
        assertNull(TripEnergyCalibration.sample(untrusted))
    }

    @Test fun insufficientDistanceAndSampleCountAreRejected() {
        assertNull(TripEnergyCalibration.sample(
            evidence().copy(distanceEndKm = gps(1_010.0, end))
        ))
        val one = TripEnergyCalibration.sample(evidence())!!
        assertNull(TripEnergyCalibration.propose(listOf(one)))
        assertNull(TripEnergyCalibration.propose(listOf(
            one.copy(distanceKm = 30.0, dischargedKwh = 3.0),
            one.copy(distanceKm = 30.0, dischargedKwh = 3.0),
            one.copy(distanceKm = 30.0, dischargedKwh = 3.0)
        )))
    }
}
