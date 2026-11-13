package com.evcockpit.data.traffic

import org.junit.Assert.*
import org.junit.Test

class TomTomQuotaLimitsTest {
    @Test fun explicitLocalSafetyCapsAreBelowPublishedFreePlans() {
        assertTrue(TomTomQuotaLimits.INCIDENTS_PER_MONTH < 2500)
        assertTrue(TomTomQuotaLimits.FLOW_TILES_PER_MONTH < 200_000)
    }

    @Test fun quotaRejectsAtThresholdWithoutZeroReset() {
        assertTrue(TomTomQuotaLimits.allowed(0, 2000))
        assertTrue(TomTomQuotaLimits.allowed(1999, 2000))
        assertFalse(TomTomQuotaLimits.allowed(2000, 2000))
        assertFalse(TomTomQuotaLimits.allowed(-1, 2000))
    }

    @Test fun sourceAgeAndRouteMustMatchBeforeDisplay() {
        val now = 1_800_000_000_000L
        val s = TomTomTrafficState(
            routeId = "real-route", fetchedAtMillis = now - 60_000L,
            serverAtMillis = now - 61_000L, incidentRequestVerified = true)
        assertTrue(s.freshFor("real-route", now))
        assertFalse(s.freshFor("other", now))
        assertFalse(s.freshFor("real-route", now + 6 * 60_000L))
        assertEquals("Trafik: kullanılamıyor", s.label("other", now))
        assertTrue(s.label("real-route", now).contains("TomTom Traffic"))
        assertFalse(s.copy(failure = com.evcockpit.domain.usecase.TrafficFailure.QUOTA_EXCEEDED)
            .freshFor("real-route", now))
    }
}
