package com.evcockpit.car

import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.model.RouteSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarRouteMatchPolicyTest {
    private val t = 1_700_000_000_000L
    private val start = GeoCoordinate(38.0, 34.0)
    private val middle = GeoCoordinate(38.003, 34.0)
    private val end = GeoCoordinate(38.006, 34.0)
    private fun route(id: String = "real-osrm-geometry") =
        RouteSnapshot(routeId = id, routePoints = listOf(start, middle, end))
    private fun fix(where: GeoCoordinate, at: Long = t, accuracy: Float = 5f) =
        GpsFix(where, accuracy, at)
    private val off = GeoCoordinate(38.003, 34.005)

    @Test fun missingOrInaccurateGpsNeverMeansVehicleOnRoute() {
        val policy = CarRouteMatchPolicy()
        assertEquals(CarRouteMatch.NO_VALID_FIX, policy.observe(route(), null, t))
        assertEquals(
            CarRouteMatch.NO_VALID_FIX,
            policy.observe(route(), fix(off, t, 38f), t)
        )
        assertEquals(CarRouteMatch.NO_VALID_FIX,
            policy.observe(route(), fix(off, t - 11_000L), t))
    }

    @Test fun fiveDistinctAccurateOffRouteFixesAreRequired() {
        val policy = CarRouteMatchPolicy()
        assertEquals(CarRouteMatch.ON_ROUTE, policy.observe(route(), fix(start), t))
        for (i in 1..4) {
            assertEquals(
                CarRouteMatch.VERIFYING,
                policy.observe(route(), fix(off, t + 1_000L * i), t + 1_000L * i)
            )
        }
        assertEquals(CarRouteMatch.CONFIRMED_OFF_ROUTE,
            policy.observe(route(), fix(off, t + 5_000L), t + 5_000L))
        // A UI clock tick with no new GPS measurement must not reset the alert.
        assertEquals(CarRouteMatch.CONFIRMED_OFF_ROUTE,
            policy.observe(route(), fix(off, t + 5_000L), t + 6_000L))
    }

    @Test fun isolatedGpsJumpNeverTriggersOffRouteWarning() {
        val policy = CarRouteMatchPolicy()
        assertEquals(CarRouteMatch.VERIFYING, policy.observe(route(), fix(off), t))
        assertEquals(CarRouteMatch.ON_ROUTE,
            policy.observe(route(), fix(middle, t + 1_000L), t + 1_000L))
        assertEquals(CarRouteMatch.VERIFYING,
            policy.observe(route(), fix(off, t + 2_000L), t + 2_000L))
    }

    @Test fun returningToRouteRearmsDetectionWithoutFabricatedReroute() {
        val policy = CarRouteMatchPolicy()
        repeat(5) { i ->
            policy.observe(route(), fix(off, t + i * 1_000L), t + i * 1_000L)
        }
        assertEquals(CarRouteMatch.CONFIRMED_OFF_ROUTE, policy.state)
        assertEquals(CarRouteMatch.ON_ROUTE,
            policy.observe(route(), fix(middle, t + 7_000L), t + 7_000L))
        for (i in 8..11) {
            assertEquals(CarRouteMatch.VERIFYING,
                policy.observe(route(), fix(off, t + i * 1_000L), t + i * 1_000L))
        }
        assertEquals(CarRouteMatch.CONFIRMED_OFF_ROUTE,
            policy.observe(route(), fix(off, t + 12_000L), t + 12_000L))
    }

    @Test fun routeChangeNeedsFreshEvidence() {
        val policy = CarRouteMatchPolicy()
        repeat(5) { i ->
            policy.observe(route("old"), fix(off, t + i * 1_000L), t + i * 1_000L)
        }
        assertEquals(CarRouteMatch.CONFIRMED_OFF_ROUTE, policy.state)
        assertEquals(CarRouteMatch.VERIFYING,
            policy.observe(route("new"), fix(off, t + 6_000L), t + 6_000L))
    }

    @Test fun failedLoadingTripRetriesWithBoundedBackoff() {
        val gate = CarHostLoadingGate()
        assertTrue(gate.shouldPublish(t))
        gate.onFailed(t)
        assertFalse(gate.shouldPublish(t + 1_000L))
        assertTrue(gate.shouldPublish(t + 2_050L))
        gate.onPublished()
        assertTrue(gate.wasPublished())
        assertFalse(gate.shouldPublish(t + 15_000L))
        gate.clear()
        assertFalse(gate.wasPublished())
        assertTrue(gate.shouldPublish(t + 15_001L))
    }
}
