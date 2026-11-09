package com.evcockpit.domain.usecase

import org.junit.Assert.*
import org.junit.Test
import com.evcockpit.domain.model.*

/** Test-only synthetic geometry. Never shipped as GPS or an active route. */
class RouteProgressTrackerTest {
    private val now=1_000_000L
    private val line=listOf(GeoCoordinate(38.0,35.0),GeoCoordinate(38.0,35.01),GeoCoordinate(38.0,35.02))
    private val maneuver=NavigationManeuver("test-route-1:step-1","Turn right",line[1])
    private val route=RouteSnapshot(routeId="test-route-1",routePoints=line,
        totalKm=1.8,osrmDurationSeconds=300.0,maneuvers=listOf(maneuver))
    @Test fun noGpsNoProgress() {
        val result=RouteProgressTracker().enrich(route,null,now)!!
        assertNull(result.remainingKm)
        assertNull(result.gpsProgressFraction)
        assertNull(result.nextManeuver)
    }
    @Test fun halfProgressIsMeasured() {
        val fix=GpsFix(line[1],5f,now)
        val result=RouteProgressTracker().enrich(route,fix,now)!!
        assertEquals(.5f,result.gpsProgressFraction!!,.015f)
        assertEquals(.9,result.remainingKm!!,.04)
        assertEquals(maneuver.id,result.nextManeuver!!.id)
        assertTrue(result.nextTurnMeters!! < 1.0)
        assertNotNull(result.estimatedArrivalEpochMs)
    }
    @Test fun staleFixIsNotANavigationPoint() {
        val fix=GpsFix(line[0],5f,now-11_000)
        val result=RouteProgressTracker().enrich(route,fix,now)!!
        assertNull(result.remainingKm)
        assertNull(result.nextTurnInstruction)
    }
    @Test fun distantFixIsNotSnapped() {
        val fix=GpsFix(GeoCoordinate(38.04,35.01),5f,now)
        val result=RouteProgressTracker().enrich(route,fix,now)!!
        assertNull(result.gpsProgressFraction)
        assertNull(result.nextManeuver)
    }
    @Test fun windowedProjectionFallsBackToFullRouteAfterLargeGpsJump() {
        val points = (0..250).map { step ->
            GeoCoordinate(38.0, 35.0 + step * 0.00010)
        }
        val longRoute = RouteSnapshot(
            routeId = "long-test-geometry",
            routePoints = points,
            totalKm = 2.2,
            osrmDurationSeconds = 500.0
        )
        val tracker = RouteProgressTracker()
        val beginning = tracker.enrich(longRoute, GpsFix(points[2], 3f, now), now)
        assertNotNull(beginning?.gpsProgressFraction)
        val jumped = tracker.enrich(
            longRoute, GpsFix(points[230], 3f, now + 1_000L), now + 1_000L
        )
        assertNotNull(jumped?.gpsProgressFraction)
        assertTrue(jumped!!.gpsProgressFraction!! > 0.85f)
    }

    @Test fun deviationOffsetHasNoSyntheticZeroWhenGpsMissing() {
        val tracker = RouteProgressTracker()
        assertNull(tracker.offRouteMeters(route, null, now))
        assertTrue(tracker.offRouteMeters(
            route, GpsFix(GeoCoordinate(38.003, 35.01), 3f, now), now
        )!! > 150.0)
    }

    @Test fun switchingRouteDoesNotRetainOldManeuver() {
        val tracker=RouteProgressTracker()
        tracker.enrich(route,GpsFix(line[0],5f,now),now)
        val alternate=route.copy(routeId="test-route-2",maneuvers=emptyList())
        assertNull(tracker.enrich(alternate,GpsFix(line[0],5f,now+1000),now+1000)!!.nextManeuver)
    }
}
