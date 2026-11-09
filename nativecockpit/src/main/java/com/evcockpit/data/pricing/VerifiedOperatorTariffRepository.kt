package com.evcockpit.data.pricing

import com.evcockpit.domain.model.ChargingStation
import com.google.gson.JsonParser
import java.time.Duration
import java.time.OffsetDateTime
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches the repository's date-stamped published operator BASELINES.
 * Never interprets a baseline as the actual station/socket checkout price.
 * Operator promotions, membership and real-time occupancy are NOT inferred.
 */
object VerifiedOperatorTariffRepository {
    private const val URL =
        "https://raw.githubusercontent.com/AlpTimucin-cpu/ev-cockpit/" +
            "feature/ev-cockpit/data/charging/operator_prices.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun quote(station: ChargingStation): StationChargeTariff =
        withContext(Dispatchers.IO) {
            val unknown = StationChargeTariff.unavailable(station.id)
            try {
                val search = station.operator.lowercase(Locale.ROOT)
                val knownOperator = when {
                    "trugo" in search -> "trugo"
                    "zes" in search -> "zes"
                    else -> return@withContext unknown
                }
                val request = Request.Builder().get().url(URL)
                    .header("User-Agent", "EV-Cockpit/1.0").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext unknown
                    val bytes = response.body?.bytes() ?: return@withContext unknown
                    if (bytes.isEmpty() || bytes.size > 250_000) return@withContext unknown
                    val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
                    val metadata = root.getAsJsonObject("metadata") ?: return@withContext unknown
                    if (metadata.get("last_refresh_had_errors")?.asBoolean != false)
                        return@withContext unknown
                    val maxAgeHours = (metadata.get("price_valid_hours")?.asLong ?: 0L)
                        .coerceIn(0L, 36L)
                    if (maxAgeHours == 0L) return@withContext unknown

                    val entry = root.getAsJsonArray("operators")?.firstOrNull {
                        it.asJsonObject.get("operator_id")?.asString == knownOperator
                    }?.asJsonObject ?: return@withContext unknown
                    val verifiedAt = entry.get("verified_at_utc")?.asString
                        ?: return@withContext unknown
                    val age = Duration.between(
                        OffsetDateTime.parse(verifiedAt).toInstant(), Instant.now()
                    )
                    if (age.isNegative || age > Duration.ofHours(maxAgeHours))
                        return@withContext unknown

                    // For stations with DC sockets, show published DC tariff BAND,
                    // not an invented price for a particular connector.
                    val kind = if (station.hasDc) "DC" else "AC"
                    val tariffs = entry.getAsJsonArray("tariffs")
                        ?: return@withContext unknown
                    val values = tariffs.mapNotNull { item ->
                        val tariff = item.asJsonObject
                        val band = tariff.get("connector_band")?.asString.orEmpty()
                        if (!band.startsWith(kind)) return@mapNotNull null
                        if (tariff.get("price_scope")?.asString != "operator_published_baseline")
                            return@mapNotNull null
                        val p = runCatching {
                            tariff.get("price_try_per_kwh").asDouble
                        }.getOrNull() ?: return@mapNotNull null
                        p.takeIf { it.isFinite() && it in 0.01..500.0 }
                    }
                    if (values.isEmpty()) return@withContext unknown
                    StationChargeTariff(
                        stationId = station.id,
                        priceTlKwh = values.singleOrNull(),
                        minPriceTlKwh = values.minOrNull(),
                        maxPriceTlKwh = values.maxOrNull(),
                        verifiedAtUtc = verifiedAt,
                        sourceOperator = entry.get("operator")?.asString
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                unknown // Network/format errors never become made-up prices.
            }
        }
}
