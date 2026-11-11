package com.evcockpit.data.weather

import com.evcockpit.domain.model.GeoCoordinate
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CurrentWeatherParserTest {
    private val nowSeconds = 1_800_000_000L
    private val gps = GeoCoordinate(38.0, 34.0)

    private fun response(epoch: Long = nowSeconds, temp: String = "11.0",
        code: String = "63") = JsonParser.parseString(
        """{"current":{"time":$epoch,"temperature_2m":$temp,
            "wind_speed_10m":27.0,"weather_code":$code}}"""
    ).asJsonObject

    @Test fun currentModelHasUtcValidTimeAndNoFakeEta() {
        val point = CurrentWeatherParser.match(response(), gps, nowSeconds * 1000L)
        assertNotNull(point)
        assertEquals(Instant.ofEpochSecond(nowSeconds).toString(), point?.observedAt)
        assertEquals(11.0, point!!.temperatureC, .001)
        assertEquals(63, point.weatherCode)
        assertEquals(gps, point.coordinate)
        assertNull(point.forecastEtaEpochMs)
    }

    @Test fun rejectsOldModelAndMalformedWeather() {
        assertNull(CurrentWeatherParser.match(
            response(epoch = nowSeconds - 4 * 3600L), gps, nowSeconds * 1000L))
        assertNull(CurrentWeatherParser.match(
            response(temp = "null"), gps, nowSeconds * 1000L))
        assertNull(CurrentWeatherParser.match(
            response(code = "250"), gps, nowSeconds * 1000L))
        assertNull(CurrentWeatherParser.match(
            response(code = "63.5"), gps, nowSeconds * 1000L))
    }

    @Test fun rejectsLocalIsoWhenUnixWasRequested() {
        val root = JsonParser.parseString(
            """{"current":{"time":"2026-11-11T08:00",
                "temperature_2m":11,"wind_speed_10m":27,"weather_code":63}}"""
        ).asJsonObject
        assertNull(CurrentWeatherParser.match(root, gps, nowSeconds * 1000L))
    }
}
