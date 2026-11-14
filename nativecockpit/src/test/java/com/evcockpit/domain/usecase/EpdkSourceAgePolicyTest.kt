package com.evcockpit.domain.usecase

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class EpdkSourceAgePolicyTest {
    private val now = Instant.parse("2026-11-14T03:00:00Z").toEpochMilli()
    @Test fun acceptsGenuineRecentIsoTimestampButNeverClaimsLive() {
        val result = EpdkSourceAgePolicy.assess("2026-11-13T20:55:43+00:00", now)
        assertEquals(EpdkSourceAgePolicy.Status.FRESH_SNAPSHOT, result.status)
        assertEquals(0L, result.ageDays)
        assertTrue(result.labelTr.contains("CANLI DEĞİL"))
        assertFalse(result.needsCaution)
    }
    @Test fun oldBranchArchiveIsExplicitlyOutdated() {
        val result = EpdkSourceAgePolicy.assess("2026-10-17T21:58:05+00:00", now)
        assertEquals(EpdkSourceAgePolicy.Status.OUTDATED_SNAPSHOT, result.status)
        assertTrue(result.ageDays!! >= 27)
        assertTrue(result.needsCaution)
        assertTrue(result.labelTr.contains("ESKİ VERİ"))
    }
    @Test fun atSevenDaysExactlyFreshAfterSevenDaysOutdated() {
        val stamp = Instant.ofEpochMilli(now-EpdkSourceAgePolicy.MAX_AGE_MS).toString()
        assertTrue(EpdkSourceAgePolicy.assess(stamp, now).isFresh)
        assertEquals(EpdkSourceAgePolicy.Status.OUTDATED_SNAPSHOT,
            EpdkSourceAgePolicy.assess(stamp, now+1L).status)
    }
    @Test fun wrongMissingAndFutureDatesNeverLookFresh() {
        for (s in listOf(null, "", "today", "2026-11-14T05:00:00Z")) {
            assertEquals(EpdkSourceAgePolicy.Status.UNVERIFIED_DATE,
                EpdkSourceAgePolicy.assess(s, now).status)
        }
        assertTrue(EpdkSourceAgePolicy.assess(null, now).needsCaution)
    }
    @Test fun timeZoneOffsetsNormalizeWithoutLocalDateAssumptions() {
        val a = EpdkSourceAgePolicy.assess("2026-11-13T23:55:43+03:00", now)
        val b = EpdkSourceAgePolicy.assess("2026-11-13T20:55:43Z", now)
        assertEquals(a.sourceUtc, b.sourceUtc)
        assertEquals(a.status, b.status)
    }
}
