package com.evcockpit.car

import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.RouteWeatherState
import com.evcockpit.domain.model.WeatherWaypoint
import org.junit.Assert.*
import org.junit.Test

class CarWeatherPresentationPolicyTest {
    private val now = 1_800_000_000_000L
    private fun point(
        km: Double = 40.0,
        code: Int = 75,
        eta: Long? = now + 60 * 60_000L
    ) = WeatherWaypoint(
        coordinate = GeoCoordinate(38.0, 34.0),
        distanceFromStartKm = km,
        temperatureC = -3.0,
        windSpeedKmh = 42.0,
        weatherCode = code,
        observedAt = "2027-01-15T16:00",
        forecastEtaEpochMs = eta,
        precipitationProbabilityPercent = 80
    )
    private fun state() = RouteWeatherState(
        routeId = "osrm-123",
        routeLengthKm = 80.0,
        points = listOf(point(0.0), point(40.0), point(80.0)),
        fetchedAtMillis = now - 5 * 60_000L
    )

    @Test fun onlyCurrentRouteForecastIsShown() {
        val missing = CarWeatherPresentationPolicy.display(state(), "other", now, 3)
        assertTrue(missing.rows.isEmpty())
        assertTrue(missing.message!!.contains("OSRM"))
    }

    @Test fun routeSitesUseEstimatedArrivalAndExplicitForecastLabels() {
        val result = CarWeatherPresentationPolicy.display(state(), "osrm-123", now, 3)
        assertEquals(3, result.rows.size)
        assertTrue(result.rows[2].title.contains("Varış"))
        assertTrue(result.rows[0].title.contains("tahmini geçiş"))
        assertTrue(result.rows[1].description.contains("kar yağışlı"))
        assertTrue(result.rows[1].description.contains("%80"))
        assertTrue(result.provenance.contains("TAHMİN"))
        assertTrue(result.provenance.contains("5 dk önce"))
    }

    @Test fun offlineOrFailedRefreshPreservesAgeLabel() {
        val cached = state().copy(
            fetchedAtMillis = now - 67 * 60_000L,
            error = "Open-Meteo bağlantısı kurulamadı"
        )
        val result = CarWeatherPresentationPolicy.display(cached, "osrm-123", now, 2)
        assertEquals(2, result.rows.size)
        assertTrue(result.provenance.contains("67 dk önce"))
        assertTrue(result.provenance.contains("yenileme başarısız"))
    }

    @Test fun staleWithoutErrorIsNotMislabelledAsFresh() {
        val result = CarWeatherPresentationPolicy.display(
            state().copy(fetchedAtMillis = now - 37 * 60_000L),
            "osrm-123", now, 3
        )
        assertTrue(result.provenance.contains("37 dk önce"))
        assertTrue(result.provenance.contains("güncelleme bekleniyor"))
    }

    @Test fun invalidForecastDataIsFilteredAndHostLimitsRespected() {
        val invalid = point(code = 200)
        val rows = state().copy(points = listOf(invalid, point(40.0), point(80.0)))
        val result = CarWeatherPresentationPolicy.display(rows, "osrm-123", now, 1)
        assertEquals(1, result.rows.size)
        val noHost = CarWeatherPresentationPolicy.display(state(), "osrm-123", now, 0)
        assertTrue(noHost.rows.isEmpty())
    }

    @Test fun noForecastNeverCreatesZeroTemperatureOrFakeWeather() {
        val missing = CarWeatherPresentationPolicy.display(
            RouteWeatherState(routeId = "osrm-123", loading = true),
            "osrm-123", now, 3
        )
        assertTrue(missing.rows.isEmpty())
        assertTrue(missing.message!!.contains("yükleniyor"))
    }

    @Test fun weatherAlertTextIsClearlyPredictedNotObserved() {
        val spoken = CarWeatherPresentationPolicy.warningText(point())
        assertTrue(spoken.contains("tahmini"))
        assertTrue(spoken.contains("kar yağışlı"))
    }

    @Test fun automaticRefreshIsThirtyMinutesAndRequiresGpsProof() {
        val gate = CarWeatherRefreshGate()
        assertFalse(gate.shouldRefresh("route", false, now))
        assertTrue(gate.shouldRefresh("route", true, now))
        gate.markAttempt("route", now)
        assertFalse(gate.shouldRefresh("route", true, now + 29 * 60_000L))
        assertTrue(gate.shouldRefresh("route", true, now + 30 * 60_000L))
        assertFalse(gate.shouldRefresh("route", true, now + 20_000L, manual = true))
        assertTrue(gate.shouldRefresh("route", true, now + 60_000L, manual = true))
    }

    @Test fun newRouteResetsRefreshCadenceButNotMissingGpsGate() {
        val gate = CarWeatherRefreshGate()
        assertTrue(gate.shouldRefresh("first", true, now))
        gate.markAttempt("first", now)
        assertFalse(gate.shouldRefresh("first", true, now + 1000))
        assertFalse(gate.shouldRefresh("second", false, now + 2000))
        assertTrue(gate.shouldRefresh("second", true, now + 3000))
        gate.reset()
        assertTrue(gate.shouldRefresh("first", true, now + 4000))
    }
}
