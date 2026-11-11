package com.evcockpit.data.weather

import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.WeatherWaypoint
import com.google.gson.JsonObject
import java.time.Instant
import kotlin.math.abs

/** Open-Meteo current MODEL conditions with UTC Unix valid time, not a station reading. */
internal object CurrentWeatherParser {
    fun match(root: JsonObject, coordinate: GeoCoordinate, nowMs: Long): WeatherWaypoint? {
        if (!coordinate.valid() || nowMs <= 0L) return null
        val current = root.get("current")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return null
        val validSeconds = try {
            current.get("time")?.takeIf { it.isJsonPrimitive }?.asLong
        } catch (_: RuntimeException) {
            null
        } ?: return null
        if (validSeconds !in 946684800L..4102444800L) return null
        val validMs = validSeconds * 1000L
        // A very old/mislabelled model cannot be passed off as "now".
        if (abs(nowMs - validMs) > 2L * 60L * 60_000L) return null
        fun number(key: String): Double? = try {
            current.get(key)?.takeIf { it.isJsonPrimitive }?.asDouble
                ?.takeIf { it.isFinite() }
        } catch (_: RuntimeException) { null }
        val temperature = number("temperature_2m")?.takeIf { it in -90.0..65.0 }
            ?: return null
        val wind = number("wind_speed_10m")?.takeIf { it in 0.0..350.0 }
            ?: return null
        val weather = number("weather_code")?.takeIf {
            it in 0.0..99.0 && it % 1.0 == 0.0
        }?.toInt() ?: return null
        return WeatherWaypoint(
            coordinate = coordinate,
            distanceFromStartKm = 0.0,
            temperatureC = temperature,
            windSpeedKmh = wind,
            weatherCode = weather,
            observedAt = Instant.ofEpochSecond(validSeconds).toString()
        )
    }
}
