package com.evcockpit.data.weather

import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.usecase.ForecastStop
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class HourlyForecastParserTest {
    private val epoch = 1_800_000_000L
    private val site = ForecastStop(
        GeoCoordinate(38.0, 34.0), 40.0, (epoch + 3200) * 1000
    )
    private fun payload(temps: String = "12,14", times: String =
        "$epoch,${epoch + 3600}") = JsonParser.parseString(
        """{"hourly":{"time":[$times],
            "temperature_2m":[$temps],"wind_speed_10m":[20,50],
            "weather_code":[0,65],"precipitation_probability":[10,87]}}"""
    ).asJsonObject

    @Test fun matchesNearestUtcHourAndKeepsEtaDistinctFromForecastValidTime() {
        val forecast = HourlyForecastParser.match(site, payload())
        assertNotNull(forecast)
        assertEquals(14.0, forecast!!.temperatureC, 0.001)
        assertEquals(65, forecast.weatherCode)
        assertEquals(87, forecast.precipitationProbabilityPercent)
        assertEquals(site.etaEpochMillis, forecast.forecastEtaEpochMs)
        assertTrue(forecast.observedAt.endsWith("Z"))
    }

    @Test fun rejectsOutOfHorizonInsteadOfReturningCurrentWeather() {
        assertNull(HourlyForecastParser.match(site.copy(
            etaEpochMillis = (epoch + 86_400L) * 1000L
        ), payload()))
    }

    @Test fun missingEtaHourMustNotFallBackToNearbyButWrongForecast() {
        val afterLastAvailableHour = site.copy(
            etaEpochMillis = (epoch + 3600L + 2800L) * 1000L
        )
        assertNull(HourlyForecastParser.match(afterLastAvailableHour, payload()))
    }

    @Test fun rejectsMissingOrInvalidNumericalData() {
        assertNull(HourlyForecastParser.match(site, payload(temps = "12,null")))
        assertNull(HourlyForecastParser.match(site, JsonParser.parseString(
            """{"hourly":{"time":[1800000000],"temperature_2m":[12]}}"""
        ).asJsonObject))
    }
}
