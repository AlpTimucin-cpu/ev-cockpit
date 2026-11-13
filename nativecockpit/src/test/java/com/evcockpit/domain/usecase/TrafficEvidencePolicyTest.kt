package com.evcockpit.domain.usecase

import org.junit.Assert.*
import org.junit.Test

class TrafficEvidencePolicyTest {
    private val now = 1_800_000_000_000L
    private fun sample(
        routeId: String = "verified-osrm-1",
        sourceTime: Long = now - 60_000L,
        eta: Double? = null,
        verifiedRouting: Boolean = false
    ) = TrafficEvidence(
        TrafficProvider.TOMTOM, routeId, sourceTime, now - 30_000L,
        eta, verifiedRouting, segmentsVerified = 4, incidentsVerified = 2
    )
    private fun check(
        evidence: TrafficEvidence? = sample(),
        routeId: String? = "verified-osrm-1",
        approved: Boolean = true,
        key: Boolean = true,
        atMs: Long = now
    ) = TrafficEvidencePolicy.validate(evidence, routeId, atMs, approved, key)

    @Test fun noApprovalNeverEnablesTraffic() {
        assertEquals(TrafficFailure.PROVIDER_NOT_APPROVED, check(approved = false).reason)
        assertEquals("Trafik: kullanılamıyor", check(approved = false).label)
        assertNull(check(approved = false).trafficAwareEtaSeconds)
    }

    @Test fun noKeyAndNoResponseRemainUnavailable() {
        assertEquals(TrafficFailure.KEY_MISSING, check(key = false).reason)
        assertEquals(TrafficFailure.UNVERIFIED, check(evidence = null).reason)
        assertNull(check(evidence = null).sourceTimeMs)
    }

    @Test fun currentSampleNeverManufacturesTrafficEtaFromOsrm() {
        val item = check(sample(eta = 900.0, verifiedRouting = false))
        assertNull(item.reason)
        assertNull(item.trafficAwareEtaSeconds)
        assertEquals(2, item.incidentsVerified)
        assertEquals(4, item.segmentsVerified)
    }

    @Test fun onlyVerifiedTrafficRoutingMayProvideTrafficAwareEta() {
        assertEquals(900.0, check(sample(eta = 900.0, verifiedRouting = true))
            .trafficAwareEtaSeconds!!, .0001)
        assertNull(check(sample(eta = Double.NaN, verifiedRouting = true))
            .trafficAwareEtaSeconds)
    }

    @Test fun staleFutureAndWrongRouteFailClosed() {
        assertEquals(TrafficFailure.STALE, check(sample(sourceTime = now - 6 * 60_000L)).reason)
        assertEquals(TrafficFailure.UNVERIFIED, check(sample(sourceTime = now + 35_000L)).reason)
        assertEquals(TrafficFailure.WRONG_ROUTE, check(routeId = "different").reason)
        assertEquals(TrafficFailure.UNVERIFIED, check(routeId = null).reason)
    }

    @Test fun quotaAndNetworkErrorsAreNotTreatedAsNoTraffic() {
        assertEquals(TrafficFailure.QUOTA_EXCEEDED,
            TrafficEvidencePolicy.failure(429, false))
        assertEquals(TrafficFailure.NETWORK_UNAVAILABLE,
            TrafficEvidencePolicy.failure(null, true))
        assertEquals(TrafficFailure.HTTP_FAILURE,
            TrafficEvidencePolicy.failure(503, false))
    }
}
