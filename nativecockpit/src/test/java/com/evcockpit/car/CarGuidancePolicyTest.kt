package com.evcockpit.car

import org.junit.Assert.*
import org.junit.Test
import com.evcockpit.domain.model.*

class CarGuidancePolicyTest {
    private val t = 1_700_000_000_000L
    private val a = GeoCoordinate(38.0, 34.0)
    private val b = GeoCoordinate(38.002, 34.0)
    private val c = GeoCoordinate(38.004, 34.0)
    private fun route() = RouteSnapshot(
        destination = "Antalya", routeId = "osrm-real-route",
        totalKm = .46, osrmDurationSeconds = 60.0,
        routePoints = listOf(a,b,c),
        maneuvers = listOf(
            NavigationManeuver("next","Turn right",b,"turn","right")
        )
    )
    private fun fix(time: Long = t) = GpsFix(a, 5f, time)

    @Test fun noGpsNeverPublishesCarTrip() {
        assertNull(CarGuidancePolicy().snapshot(route(), null, t))
        assertNull(CarGuidancePolicy().snapshot(route(), fix(t - 11_000), t))
    }
    @Test fun realGpsAndRouteProduceNextStep() {
        val g = CarGuidancePolicy().snapshot(route(), fix(), t)!!
        assertEquals(CarTurn.RIGHT, g.nextTurn)
        assertTrue(g.remainingMeters > 0)
        assertTrue(g.nextMeters!! > 0)
        assertEquals("osrm-real-route", g.routeId)
    }
    @Test fun noFakeProgressForOffRouteFix() {
        assertNull(CarGuidancePolicy().snapshot(route(),GpsFix(GeoCoordinate(39.0,34.0),5f,t),t))
    }
    @Test fun hostUpdateGateThrottlesOneSecondTicks() {
        val g = CarGuidancePolicy().snapshot(route(),fix(),t)!!
        val gate = CarTripUpdateGate()
        assertTrue(gate.shouldPublish(g,t))
        gate.onPublished(g,t)
        assertFalse(gate.shouldPublish(g,t+1000))
        assertTrue(gate.shouldPublish(g,t+10_001))
    }
    @Test fun rejectedTripIpcNeverBecomesPublishedAndRetriesAfterBackoff() {
        val g = CarGuidancePolicy().snapshot(route(),fix(),t)!!
        val gate = CarTripUpdateGate()
        assertTrue(gate.shouldPublish(g,t))
        gate.onPublishFailed(t)
        assertFalse(gate.shouldPublish(g,t+1_000L))
        assertTrue(gate.shouldPublish(g,t+2_100L))
        gate.onPublished(g,t+2_100L)
        assertFalse(gate.shouldPublish(g,t+2_200L))
        gate.clear()
        assertTrue(gate.shouldPublish(g,t+2_201L))
    }

    @Test fun aDifferentManeuverIsPublishedImmediatelyAfterAcknowledgement() {
        val g = CarGuidancePolicy().snapshot(route(),fix(),t)!!
        val gate = CarTripUpdateGate()
        gate.onPublished(g,t)
        assertTrue(gate.shouldPublish(g.copy(nextManeuverId = "other"),t+100))
    }

    @Test fun unknownOsrmTurnNeverGetsInventedIcon() {
        assertNull(CarTurnMapping.fromOsrm("unknown","right"))
        assertEquals(CarTurn.RIGHT, CarTurnMapping.fromOsrm("turn","right"))
        assertEquals(CarTurn.ROUNDABOUT, CarTurnMapping.fromOsrm("roundabout",null))
    }
}
