package com.evcockpit.domain.usecase

import org.junit.Assert.*
import org.junit.Test

class EpdkProvenancePolicyTest {
    private val now = 1_800_000_000_000L
    private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).toString()

    @Test fun onlySourceExportWithinSeventyTwoHoursIsFresh() {
        assertTrue(EpdkProvenancePolicy.isFresh(iso(now), now))
        assertTrue(EpdkProvenancePolicy.isFresh(
            iso(now - EpdkProvenancePolicy.MAX_AGE_MS), now))
        assertFalse(EpdkProvenancePolicy.isFresh(
            iso(now - EpdkProvenancePolicy.MAX_AGE_MS - 1L), now))
        assertFalse(EpdkProvenancePolicy.isFresh(iso(now + 60_000L), now))
        assertFalse(EpdkProvenancePolicy.isFresh(null, now))
        assertFalse(EpdkProvenancePolicy.isFresh("2026-11-13", now))
    }

    @Test fun theoreticalVehicleCeilingNeverMakesUpSocketPower() {
        assertEquals(110.0,
            EpdkProvenancePolicy.acceptedDcPowerKw(150.0,110.0,true)!!,.001)
        assertEquals(90.0,
            EpdkProvenancePolicy.acceptedDcPowerKw(90.0,110.0,true)!!,.001)
        assertNull(EpdkProvenancePolicy.acceptedDcPowerKw(150.0,110.0,false))
        assertNull(EpdkProvenancePolicy.acceptedDcPowerKw(null,110.0,true))
        assertNull(EpdkProvenancePolicy.acceptedDcPowerKw(150.0,null,true))
        assertNull(EpdkProvenancePolicy.acceptedDcPowerKw(150.0,Double.NaN,true))
    }

    @Test fun sourceIdentifierIsNotAClaimAboutLiveSocketData() {
        assertTrue(EpdkProvenancePolicy.sourceLabel(
            EpdkProvenancePolicy.OFFICIAL_SOURCE).contains("resmî"))
        assertTrue(EpdkProvenancePolicy.sourceLabel(null).contains("doğrulanamadı"))
    }
}
