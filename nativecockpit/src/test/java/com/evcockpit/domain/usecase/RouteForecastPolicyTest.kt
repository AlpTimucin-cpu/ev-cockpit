package com.evcockpit.domain.usecase

import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.RouteSnapshot
import org.junit.Assert.*
import org.junit.Test

class RouteForecastPolicyTest {
    private fun route(progress: Float? = 0f) = RouteSnapshot(
        routeId = "real-osrm-1",
        routePoints = listOf(GeoCoordinate(37.0, 34.0), GeoCoordinate(41.5, 34.0)),
        osrmDurationSeconds = 6 * 3600.0,
        gpsProgressFraction = progress
    )

    @Test fun longRouteCoversRemainingPathAndDestinationInAtMostEightPoints() {
        val points = RouteForecastPolicy.sample(route(), 1_800_000_000_000L)
        assertEquals(8, points.size)
        assertEquals(37.0, points.first().coordinate.latitude, 0.000001)
        assertEquals(41.5, points.last().coordinate.latitude, 0.000001)
        assertEquals(1_800_000_000_000L, points.first().etaEpochMillis)
        assertEquals(1_800_000_000_000L + 6L * 3_600_000L,
            points.last().etaEpochMillis)
        assertTrue(points.zipWithNext().all { (a, b) ->
            b.distanceFromStartKm > a.distanceFromStartKm &&
                b.etaEpochMillis > a.etaEpochMillis
        })
    }

    @Test fun halfDrivenRouteStartsAtActualProgressNotOrigin() {
        val points = RouteForecastPolicy.sample(route(.5f), 1_800_000_000_000L)
        assertTrue(points.size in 2..8)
        assertEquals(39.25, points.first().coordinate.latitude, 0.01)
        assertEquals(41.5, points.last().coordinate.latitude, 0.000001)
        assertEquals(1_800_000_000_000L + 3L * 3_600_000L,
            points.last().etaEpochMillis)
    }

    @Test fun shortRouteMaintainsApproximatelyFortyKilometerSpacing() {
        val small = RouteSnapshot(
            routeId = "test",
            routePoints = listOf(GeoCoordinate(38.0, 34.0), GeoCoordinate(38.8, 34.0)),
            gpsProgressFraction = 0f, osrmDurationSeconds = 5400.0
        )
        val points = RouteForecastPolicy.sample(small, 1_800_000_000_000L)
        assertEquals(4, points.size)
        assertTrue(points[1].distanceFromStartKm in 39.9..40.1)
        assertEquals(38.8, points.last().coordinate.latitude, 0.000001)
    }

    @Test fun missingProgressOrDurationDoesNotFabricateEta() {
        assertTrue(RouteForecastPolicy.sample(route(null), 1_800_000_000_000L)
            .isEmpty())
        assertTrue(RouteForecastPolicy.sample(
            route().copy(osrmDurationSeconds = null), 1_800_000_000_000L
        ).isEmpty())
        assertTrue(RouteForecastPolicy.sample(route(1f), 1_800_000_000_000L)
            .isEmpty())
    }
}
