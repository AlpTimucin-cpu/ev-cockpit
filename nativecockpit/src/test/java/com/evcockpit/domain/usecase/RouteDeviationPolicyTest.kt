package com.evcockpit.domain.usecase

import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.model.RouteSnapshot
import org.junit.Assert.*
import org.junit.Test

/** Synthetic samples are confined to JVM tests, never exposed as vehicle GPS. */
class RouteDeviationPolicyTest {
    private val clock = 2_000_000L
    private val route = RouteSnapshot(
        routeId = "test-route-1",
        routePoints = listOf(
            GeoCoordinate(38.0, 35.0),
            GeoCoordinate(38.0, 35.02)
        ),
        totalKm = 1.7,
        osrmDurationSeconds = 300.0
    )
    private fun fix(index: Int, northDegrees: Double = 0.0020, accuracy: Float = 3f) =
        GpsFix(
            GeoCoordinate(38.0 + northDegrees, 35.009),
            accuracy,
            clock + index * 1000L
        )

    @Test fun fiveFreshDeviationsTriggerExactlyOnce() {
        val policy = RouteDeviationPolicy()
        for (i in 0 until 4) {
            assertFalse(policy.observe(route, fix(i), clock + i * 1000))
        }
        assertTrue(policy.observe(route, fix(4), clock + 4_000L))
        assertFalse(policy.observe(route, fix(5), clock + 5_000L))
        assertFalse(policy.observe(route, fix(6), clock + 6_000L))
    }

    @Test fun onRouteFixResetsConsecutiveCounter() {
        val policy = RouteDeviationPolicy()
        for (i in 0..3) policy.observe(route, fix(i), clock + i * 1000)
        assertFalse(policy.observe(route, fix(4, northDegrees = 0.0), clock + 4_000L))
        for (i in 5..8) {
            assertFalse(policy.observe(route, fix(i), clock + i * 1000L))
        }
        assertTrue(policy.observe(route, fix(9), clock + 9_000L))
    }

    @Test fun lowAccuracyDuplicateOrStaleFixesDoNotCount() {
        val policy = RouteDeviationPolicy()
        assertFalse(policy.observe(route, fix(0), clock))
        assertFalse(policy.observe(route, fix(0), clock + 1000L))
        assertFalse(policy.observe(route, fix(1, accuracy = 37f), clock + 1000L))
        assertFalse(policy.observe(route, fix(2), clock + 20_000L))
        for (i in 3..5) {
            assertFalse(policy.observe(route, fix(i), clock + i * 1000L))
        }
        assertTrue(policy.observe(route, fix(6), clock + 6_000L))
    }

    @Test fun switchingRoutesRearmsButNoGpsNeverTriggers() {
        val policy = RouteDeviationPolicy()
        for (i in 0..4) policy.observe(route, fix(i), clock + i * 1000L)
        assertFalse(policy.observe(route, null, clock + 5_000L))
        val newRoute = route.copy(routeId = "real-route-two")
        for (i in 5..8) assertFalse(policy.observe(newRoute, fix(i), clock + i * 1000L))
        assertTrue(policy.observe(newRoute, fix(9), clock + 9_000L))
        assertFalse(policy.observe(null, fix(10), clock + 10_000L))
    }
}
