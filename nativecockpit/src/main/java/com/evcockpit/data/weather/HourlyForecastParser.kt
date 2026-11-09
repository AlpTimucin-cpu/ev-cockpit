package com.evcockpit.data.weather

import com.evcockpit.domain.model.WeatherWaypoint
import com.evcockpit.domain.usecase.ForecastStop
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

/** Parses UTC Unix-hourly model forecasts, never confusing them with observations. */
internal object HourlyForecastParser {
    fun match(site: ForecastStop, root: JsonObject): WeatherWaypoint? {
        val hourly = root.get("hourly")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return null
        fun series(name: String): JsonArray? =
            hourly.get(name)?.takeIf { it.isJsonArray }?.asJsonArray
        val times = series("time") ?: return null
        val temp = series("temperature_2m") ?: return null
        val wind = series("wind_speed_10m") ?: return null
        val codes = series("weather_code") ?: return null
        val precip = series("precipitation_probability") ?: return null
        val count = times.size()
        if (count == 0 || count > 500 || listOf(temp, wind, codes, precip)
                .any { it.size() != count }) return null

        val etaSeconds = site.etaEpochMillis / 1000L
        var bestIndex = -1
        var bestOffset = Long.MAX_VALUE
        for (i in 0 until count) {
            val time = times[i].readLong() ?: continue
            val offset = abs(time - etaSeconds)
            if (offset < bestOffset) {
                bestOffset = offset
                bestIndex = i
            }
        }
        // Hourly forecasts should normally be within 30 minutes of ETA.
        // Allow 45 minutes for sampling jitter but reject the wrong adjacent
        // hour instead of treating a distant forecast as an ETA prediction.
        if (bestIndex < 0 || bestOffset > 2_700L) return null
        val timestamp = times[bestIndex].readLong() ?: return null
        val temperature = temp[bestIndex].readDouble()
            ?.takeIf { it in -90.0..65.0 } ?: return null
        val windSpeed = wind[bestIndex].readDouble()
            ?.takeIf { it in 0.0..350.0 } ?: return null
        val code = codes[bestIndex].readDouble()
            ?.takeIf { it in 0.0..99.0 && it % 1.0 == 0.0 } ?: return null
        val probability = precip[bestIndex].readDouble()
            ?.takeIf { it in 0.0..100.0 }?.roundToInt()
        return WeatherWaypoint(
            coordinate = site.coordinate,
            distanceFromStartKm = site.distanceFromStartKm,
            temperatureC = temperature,
            windSpeedKmh = windSpeed,
            weatherCode = code.toInt(),
            observedAt = Instant.ofEpochSecond(timestamp).toString(),
            forecastEtaEpochMs = site.etaEpochMillis,
            precipitationProbabilityPercent = probability
        )
    }

    private fun com.google.gson.JsonElement.readDouble(): Double? = try {
        if (isJsonNull || !isJsonPrimitive) null else asDouble.takeIf { it.isFinite() }
    } catch (_: RuntimeException) { null }

    private fun com.google.gson.JsonElement.readLong(): Long? = try {
        if (isJsonNull || !isJsonPrimitive) null else asLong.takeIf {
            it in 946684800L..4102444800L
        }
    } catch (_: RuntimeException) { null }
}
