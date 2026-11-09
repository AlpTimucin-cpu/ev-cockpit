package com.evcockpit.domain.usecase

import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.RouteWeatherState
import com.evcockpit.domain.model.WeatherWaypoint
import org.junit.Assert.*
import org.junit.Test

class WeatherChangeAlertGateTest {
    private val now = 200_000L
    private fun forecast(
        code: Int,
        routeId: String = "route-1",
        age: Long = 0L,
        distance: Double = 40.0,
        eta: Long = now,
        wind: Double = 10.0
    ) = RouteWeatherState(
        routeId = routeId,
        routeLengthKm = 100.0,
        fetchedAtMillis = now - age,
        points = listOf(
            WeatherWaypoint(
                coordinate = GeoCoordinate(38.0, 34.0),
                distanceFromStartKm = distance,
                temperatureC = 10.0,
                windSpeedKmh = wind,
                weatherCode = code,
                observedAt = "model UTC",
                forecastEtaEpochMs = eta
            )
        )
    )

    @Test fun warnsOnSeverityIncreaseButNotOnRepeatedHourlyRefresh() {
        val gate = WeatherChangeAlertGate()
        assertNull(gate.increasedWarning(forecast(0), now, 0f))
        assertNotNull(gate.increasedWarning(forecast(63), now, 0f))
        assertNull(gate.increasedWarning(forecast(63), now, 0f))
        assertNull(gate.increasedWarning(forecast(0), now, 0f))
        assertNotNull(gate.increasedWarning(forecast(95), now, 0f))
    }

    @Test fun routeSwitchResetsGateButStaleModelsAreSilent() {
        val gate = WeatherChangeAlertGate()
        assertNotNull(gate.increasedWarning(forecast(95), now, .1f))
        assertNull(gate.increasedWarning(forecast(95), now, .1f))
        assertNotNull(gate.increasedWarning(forecast(95, "route-2"), now, .1f))
        assertNull(gate.increasedWarning(
            forecast(95, "route-3", age = 46 * 60_000L), now, .1f
        ))
    }

    @Test fun refusesWeatherWithoutMeasuredRouteProgress() {
        val gate = WeatherChangeAlertGate()
        assertNull(gate.increasedWarning(forecast(95), now, null))
        assertNull(gate.increasedWarning(forecast(95), now, Float.NaN))
        assertNull(gate.increasedWarning(forecast(95), now, -1f))
        assertNull(gate.increasedWarning(forecast(95), now, 1.1f))
        assertNull(gate.increasedWarning(
            forecast(95).copy(routeLengthKm = null), now, 0f
        ))
    }

    @Test fun neverWarnForForecastAtPassedRoutePoint() {
        val gate = WeatherChangeAlertGate()
        // A dangerous storm forecast near km 40 is already behind a vehicle at km 60.
        assertNull(gate.increasedWarning(forecast(95, distance = 40.0), now, .6f))
        // Later valid upcoming weather still needs a fresh, separate increase.
        assertNotNull(gate.increasedWarning(
            forecast(63, distance = 80.0), now, .6f
        ))
    }

    @Test fun noExpiredOrFarAwayForecastIsSpoken() {
        val gate = WeatherChangeAlertGate()
        assertNull(gate.increasedWarning(
            forecast(95, eta = now - 2 * 60_000L), now, 0f
        ))
        assertNull(gate.increasedWarning(
            forecast(95, eta = now + 4 * 60 * 60_000L), now, 0f
        ))
        assertNotNull(gate.increasedWarning(
            forecast(95, eta = now + 2 * 60 * 60_000L), now, 0f
        ))
    }

    @Test fun passedPreviousWarningDoesNotSuppressUpcomingNewRisk() {
        val gate = WeatherChangeAlertGate()
        assertNotNull(gate.increasedWarning(
            forecast(63, distance = 40.0), now, 0f
        ))
        assertNull(gate.increasedWarning(
            forecast(63, distance = 40.0), now, .7f
        ))
        assertNotNull(gate.increasedWarning(
            forecast(63, distance = 80.0), now, .7f
        ))
    }
}
