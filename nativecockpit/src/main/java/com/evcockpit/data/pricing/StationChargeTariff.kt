package com.evcockpit.data.pricing

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Provider-verified station/socket tariff. Null means UNKNOWN, never zero TL.
 * An EPDK public station record alone is not a daily tariff or campaign feed.
 */
data class StationChargeTariff(
    val stationId: String,
    val priceTlKwh: Double? = null,
    val minPriceTlKwh: Double? = null,
    val maxPriceTlKwh: Double? = null,
    val campaignDescription: String? = null,
    val verifiedAtUtc: String? = null,
    val sourceOperator: String? = null
) {
    val isUsable: Boolean get() = priceTlKwh?.let {
        it.isFinite() && it > 0.0 && it <= 500.0
    } == true && !verifiedAtUtc.isNullOrBlank() && !sourceOperator.isNullOrBlank()

    companion object {
        fun unavailable(stationId: String) = StationChargeTariff(stationId)
    }
}

/**
 * Plug in an explicitly approved operator endpoint later. Retrofit's @Url
 * expects a fully qualified URL from trusted configuration, NOT the station
 * name or an untrusted field. No fictional public price endpoint is hardcoded.
 */
interface StationTariffApi {
    @GET
    suspend fun fetchVerifiedQuote(@Url trustedProviderUrl: String): Response<StationChargeTariff>
}

/** Fail closed on stale/unverified/mismatched prices and optional campaigns. */
object StationTariffValidation {
    fun accept(stationId: String, response: StationChargeTariff?): StationChargeTariff {
        if (response == null || response.stationId != stationId || !response.isUsable) {
            return StationChargeTariff.unavailable(stationId)
        }
        return response
    }
}
