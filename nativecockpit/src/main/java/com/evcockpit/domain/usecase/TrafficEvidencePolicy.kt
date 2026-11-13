package com.evcockpit.domain.usecase

/**
 * Phase 2 security boundary. No provider account, key or API endpoint is
 * configured: no real-time traffic may be displayed until owner approval.
 * This model cannot generate road shapes or speed-limit values.
 */
internal enum class TrafficFailure {
    PROVIDER_NOT_APPROVED, KEY_MISSING, QUOTA_EXCEEDED,
    NETWORK_UNAVAILABLE, HTTP_FAILURE, UNVERIFIED, STALE, WRONG_ROUTE,
    AUTH_FAILED, BUDGET_EXHAUSTED
}
internal enum class TrafficProvider { TOMTOM, HERE, MAPBOX }

internal data class TrafficEvidence(
    val provider: TrafficProvider,
    val routeId: String,
    /** Milliseconds when provider says the traffic data was updated. */
    val sourceTimeMs: Long,
    val fetchedAtMs: Long,
    /** ONLY from a verified traffic-aware routing result, not OSRM. */
    val trafficAwareEtaSeconds: Double? = null,
    val trafficRoutingVerified: Boolean = false,
    /** Validated provider data; no default 0 km/h or speed limit. */
    val segmentsVerified: Int = 0,
    val incidentsVerified: Int = 0
)

internal data class TrafficDecision(
    val label: String = "Trafik: kullanılamıyor",
    val reason: TrafficFailure? = TrafficFailure.PROVIDER_NOT_APPROVED,
    val sourceTimeMs: Long? = null,
    val trafficAwareEtaSeconds: Double? = null,
    val incidentsVerified: Int = 0,
    val segmentsVerified: Int = 0
)

/** Fail-closed policy for live traffic provenance, TTL, route binding and errors. */
internal object TrafficEvidencePolicy {
    private const val MAX_AGE_MS = 5L * 60_000L
    private const val MAX_CLOCK_LEAD_MS = 30_000L
    val disabled: TrafficDecision get() = TrafficDecision()

    fun failure(httpCode: Int?, networkFailure: Boolean): TrafficFailure = when {
        httpCode == 429 -> TrafficFailure.QUOTA_EXCEEDED
        networkFailure -> TrafficFailure.NETWORK_UNAVAILABLE
        httpCode != null -> TrafficFailure.HTTP_FAILURE
        else -> TrafficFailure.UNVERIFIED
    }

    fun validate(
        evidence: TrafficEvidence?,
        routeId: String?,
        nowMs: Long,
        ownerApproved: Boolean,
        keyAvailable: Boolean
    ): TrafficDecision {
        if (!ownerApproved) return TrafficDecision(
            reason = TrafficFailure.PROVIDER_NOT_APPROVED
        )
        if (!keyAvailable) return TrafficDecision(reason = TrafficFailure.KEY_MISSING)
        if (routeId.isNullOrBlank() || evidence == null || nowMs <= 0L)
            return TrafficDecision(reason = TrafficFailure.UNVERIFIED)
        if (evidence.routeId != routeId)
            return TrafficDecision(reason = TrafficFailure.WRONG_ROUTE)
        if (evidence.sourceTimeMs <= 0L || evidence.fetchedAtMs <= 0L ||
            evidence.sourceTimeMs > evidence.fetchedAtMs + MAX_CLOCK_LEAD_MS ||
            evidence.fetchedAtMs > nowMs + MAX_CLOCK_LEAD_MS ||
            evidence.segmentsVerified < 0 || evidence.incidentsVerified < 0
        ) return TrafficDecision(reason = TrafficFailure.UNVERIFIED)
        if (nowMs < evidence.sourceTimeMs ||
            nowMs - evidence.sourceTimeMs > MAX_AGE_MS ||
            nowMs < evidence.fetchedAtMs ||
            nowMs - evidence.fetchedAtMs > MAX_AGE_MS
        ) return TrafficDecision(reason = TrafficFailure.STALE)
        val eta = evidence.trafficAwareEtaSeconds?.takeIf {
            evidence.trafficRoutingVerified && it.isFinite() && it in 1.0..604_800.0
        }
        return TrafficDecision(
            label = "Trafik: sağlayıcı verisi güncel (kaynak doğrulandı)",
            reason = null,
            sourceTimeMs = evidence.sourceTimeMs,
            trafficAwareEtaSeconds = eta,
            incidentsVerified = evidence.incidentsVerified,
            segmentsVerified = evidence.segmentsVerified
        )
    }
}
