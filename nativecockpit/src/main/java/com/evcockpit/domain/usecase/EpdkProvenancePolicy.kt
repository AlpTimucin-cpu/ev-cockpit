package com.evcockpit.domain.usecase

import java.time.Instant

/**
 * Verified EPDK public publisher snapshot freshness, not live socket status.
 * "verified_at_utc" is the export time of EPDK's public records.
 */
object EpdkProvenancePolicy {
    const val OFFICIAL_SOURCE = "https://apigateway.epdk.gov.tr/sarjIstasyonlari"
    const val MAX_AGE_MS = 72L * 60L * 60_000L
    private const val FUTURE_SKEW_MS = 5L * 60_000L

    fun isFresh(sourceDate: String?, nowMillis: Long): Boolean {
        val parsed = runCatching { Instant.parse(sourceDate ?: return false) }
            .getOrNull() ?: return false
        val epoch = parsed.toEpochMilli()
        if (nowMillis <= 0L || epoch > nowMillis + FUTURE_SKEW_MS) return false
        return epoch <= nowMillis && nowMillis - epoch <= MAX_AGE_MS
    }

    fun sourceLabel(url: String?): String =
        if (url == OFFICIAL_SOURCE) "EPDK resmî istasyon REST servisi"
        else "EPDK kaynak bilgisi doğrulanamadı"

    /** Null unless both vehicle DC peak and this exact CCS socket power are known. */
    fun acceptedDcPowerKw(stationDcKw: Double?, vehiclePeakDcKw: Double?,
        confirmedVehicle: Boolean
    ): Double? {
        if (!confirmedVehicle) return null
        val station = stationDcKw?.takeIf { it.isFinite() && it in 1.0..700.0 }
            ?: return null
        val vehicle = vehiclePeakDcKw?.takeIf { it.isFinite() && it in 1.0..400.0 }
            ?: return null
        return minOf(station, vehicle) // NAMEPLATE model only, NOT real delivered power.
    }
}
