package com.evcockpit.domain.model

import org.junit.Assert.*
import org.junit.Test

class CoreEvidenceTest {
    @Test fun missingValuesNeverTurnIntoMeasurements() {
        val unknown: CoreEvidence<Double> = CoreEvidence.Unavailable(CoreIssue.OBD_DISCONNECTED)
        assertTrue(unknown is CoreEvidence.Unavailable)
        assertEquals("ELM327 bağlantısı yok. Batarya ölçümleri kullanılamıyor.",
            (unknown as CoreEvidence.Unavailable).issue.messageTr)
    }

    @Test fun everyAvailableValueCarriesTimestampSourceAndClass() {
        val sample = CoreEvidence.Available(
            value = 32.0, recordedAtMillis = 1_000L,
            source = EvidenceSource.FUSED_GPS,
            classification = EvidenceClass.MEASUREMENT
        )
        assertEquals(32.0, sample.currentOrNull(10_999L, 10_000L) ?: -1.0, 0.0001)
        assertNull(sample.currentOrNull(11_001L, 10_000L))
        assertNull(sample.currentOrNull(999L, 10_000L))
        assertTrue(sample.isStale(11_001L, 10_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun userScenarioCannotBeLabeledMeasured() {
        CoreEvidence.Available(75.0, 1_000L,
            EvidenceSource.USER_INPUT, EvidenceClass.MEASUREMENT)
    }

    @Test(expected = IllegalArgumentException::class)
    fun estimatesCannotMasqueradeAsLiveObd() {
        CoreEvidence.Available(250.0, 1_000L,
            EvidenceSource.VERIFIED_OBD, EvidenceClass.MODEL_ESTIMATE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun timestampIsRequired() {
        CoreEvidence.Available(1.0, 0L, EvidenceSource.FUSED_GPS, EvidenceClass.MEASUREMENT)
    }
}
