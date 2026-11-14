package com.evcockpit.domain.usecase

import java.time.Instant

/** 
 * EPDK static station snapshot *publication* age. A fresh snapshot NEVER
 * means a plug is available, a tariff is current or its status is live.
 *
 * Seven days is an app-level caution threshold, not an EPDK guarantee.
 */
object EpdkSourceAgePolicy {
    private const val DAY_MS = 86_400_000L
    const val MAX_AGE_MS = 7L * DAY_MS
    private const val MAX_CLOCK_SKEW_MS = 5L * 60_000L

    enum class Status { FRESH_SNAPSHOT, OUTDATED_SNAPSHOT, UNVERIFIED_DATE }

    data class Assessment(
        val status: Status,
        val ageDays: Long?,
        val sourceUtc: String?
    ) {
        val isFresh: Boolean get() = status == Status.FRESH_SNAPSHOT
        val needsCaution: Boolean get() = !isFresh
        val labelTr: String get() = when(status) {
            Status.FRESH_SNAPSHOT ->
                "EPDK istasyon listesi: ${ageDays ?: 0} gün önceki kayıt · CANLI DEĞİL"
            Status.OUTDATED_SNAPSHOT ->
                "EPDK istasyon listesi: ESKİ VERİ · ${ageDays ?: 0} gün önce · CANLI DEĞİL"
            Status.UNVERIFIED_DATE ->
                "EPDK istasyon listesi: kaynak tarihi doğrulanamadı · CANLI DEĞİL"
        }
    }

    fun assess(sourceDate: String?, nowMs: Long = System.currentTimeMillis()): Assessment {
        if (sourceDate.isNullOrBlank() || nowMs <= 0L) {
            return Assessment(Status.UNVERIFIED_DATE, null, null)
        }
        val published = runCatching { Instant.parse(sourceDate).toEpochMilli() }
            .getOrNull() ?: return Assessment(Status.UNVERIFIED_DATE, null, null)
        // Future publication (even within five minutes) is not evidence of freshness.
        if (published <= 0L || published > nowMs ||
            nowMs - published < 0L || published > nowMs + MAX_CLOCK_SKEW_MS)
            return Assessment(Status.UNVERIFIED_DATE, null, null)
        val age = nowMs - published
        return Assessment(
            if (age <= MAX_AGE_MS) Status.FRESH_SNAPSHOT else Status.OUTDATED_SNAPSHOT,
            age / DAY_MS,
            Instant.ofEpochMilli(published).toString()
        )
    }
}
