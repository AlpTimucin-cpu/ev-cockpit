package com.evcockpit.car

import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.GpsFix
import org.junit.Assert.*
import org.junit.Test

class CarNavigationContinuityTest {
    private val now = 1_800_000_000_000L
    private fun fix(t: Long = now - 500L, accuracy: Float = 5f) =
        GpsFix(GeoCoordinate(38.0, 34.0), accuracy, t)

    @Test fun serviceCannotCreateANavigationSessionWithoutExplicitHostStart() {
        val c = CarNavigationContinuity()
        assertFalse(c.serviceStarted("route", Any()))
        assertFalse(c.canResume("route", fix(), now))
        assertEquals(CarContinuityPhase.OFF, c.state.value.phase)
    }

    @Test fun serviceKeepsOnlySameRealRouteAndFreshAccurateFixForResume() {
        val c = CarNavigationContinuity()
        val token = Any()
        assertTrue(c.hostStarted("osrm-1"))
        assertEquals(CarContinuityPhase.STARTING, c.state.value.phase)
        assertTrue(c.serviceStarted("osrm-1", token))
        assertFalse(c.canResume("osrm-1", null, now))
        c.serviceGps("osrm-1", token, fix(), now)
        assertEquals(CarContinuityPhase.ACTIVE, c.state.value.phase)
        assertTrue(c.canResume("osrm-1", fix(), now))
        assertFalse(c.canResume("different-route", fix(), now))
    }

    @Test fun staleImpreciseFutureOrMissingGpsCannotEnableHostResume() {
        val c = CarNavigationContinuity()
        val token = Any()
        c.hostStarted("route")
        c.serviceStarted("route", token)
        assertFalse(c.canResume("route", fix(now - 20_000L), now))
        assertFalse(c.canResume("route", fix(now, 40f), now))
        assertFalse(c.canResume("route", fix(now + 2000L), now))
        assertFalse(c.canResume("route", null, now))
        c.serviceGps("route", token, fix(now - 20_000L), now)
        assertEquals(CarContinuityPhase.GPS_UNAVAILABLE, c.state.value.phase)
    }

    @Test fun temporaryGpsLossDoesNotDestroyUserTrip() {
        val c = CarNavigationContinuity()
        val token = Any()
        c.hostStarted("route")
        c.serviceStarted("route", token)
        c.serviceGps("route", token, fix(), now)
        c.serviceGps("route", token, null, now + 12_000L)
        assertEquals(CarContinuityPhase.GPS_UNAVAILABLE, c.state.value.phase)
        assertFalse(c.canResume("route", null, now + 12_000L))
        c.serviceGps("route", token, fix(now + 13_000L), now + 14_000L)
        assertEquals(CarContinuityPhase.ACTIVE, c.state.value.phase)
        assertTrue(c.canResume("route", fix(now + 13_000L), now + 14_000L))
    }

    @Test fun delayedOldServiceCannotEndReplacementSession() {
        val c = CarNavigationContinuity()
        val old = Any()
        val newer = Any()
        c.hostStarted("old-route")
        c.serviceStarted("old-route", old)
        c.hostStarted("new-route")
        c.serviceStarted("new-route", newer)
        c.serviceEnded(old)
        c.serviceGps("old-route", old, fix(), now)
        assertEquals("new-route", c.state.value.routeId)
        assertEquals(CarContinuityPhase.GPS_UNAVAILABLE, c.state.value.phase)
        c.serviceEnded(newer)
        assertEquals(CarContinuityPhase.OFF, c.state.value.phase)
    }

    @Test fun deniedHostLaunchNeedsVisiblePhoneUserAction() {
        val c = CarNavigationContinuity()
        assertFalse(c.retryFromPhone("route"))
        c.hostStarted("route")
        c.launchRestricted("route")
        assertEquals(CarContinuityPhase.PHONE_ACTION_REQUIRED, c.state.value.phase)
        assertFalse(c.canResume("route", fix(), now))
        assertFalse(c.retryFromPhone("other-route"))
        assertTrue(c.retryFromPhone("route"))
        assertEquals(CarContinuityPhase.STARTING, c.state.value.phase)
        val token = Any()
        c.serviceStarted("route", token)
        c.serviceGps("route", token, fix(), now)
        assertTrue(c.canResume("route", fix(), now))
    }

    @Test fun explicitStopAndNewProcessCannotResurrectOldRoute() {
        val c = CarNavigationContinuity()
        val token = Any()
        c.hostStarted("route")
        c.serviceStarted("route", token)
        c.endRoute("wrong")
        assertEquals("route", c.state.value.routeId)
        c.endRoute("route")
        assertEquals(CarContinuityPhase.OFF, c.state.value.phase)
        c.serviceGps("route", token, fix(), now)
        assertFalse(c.canResume("route", fix(), now))
        assertEquals(CarContinuityPhase.OFF, CarNavigationContinuity().state.value.phase)
    }

    @Test fun hostStartRejectsBlankRouteId() {
        val c = CarNavigationContinuity()
        assertFalse(c.hostStarted(""))
        assertFalse(c.hostStarted("  ".trim()))
        assertEquals(CarContinuityPhase.OFF, c.state.value.phase)
    }
}
