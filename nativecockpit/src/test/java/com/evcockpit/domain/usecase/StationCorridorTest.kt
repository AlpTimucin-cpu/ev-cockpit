package com.evcockpit.domain.usecase

import org.junit.Assert.*
import org.junit.Test
import com.evcockpit.domain.model.*

class StationCorridorTest {
    private val route = listOf(GeoCoordinate(38.0,35.0),GeoCoordinate(38.2,35.0))
    @Test fun exactRouteIsZeroMeters() {
        assertEquals(0.0,StationCorridor.distanceMeters(GeoCoordinate(38.1,35.0),route)!!,1.0)
    }
    @Test fun corridorTwoKilometersIsNotDrivingDistance() {
        val near = ChargingStation("verified-source-id","Test","Test","",GeoCoordinate(38.1,35.015),
            listOf(ChargingSocket("1","CCS2","DC","120")),"2026-10-17T00:00:00Z")
        val midway = near.copy(coordinate = GeoCoordinate(38.1, 35.04))
        val far=near.copy(coordinate=GeoCoordinate(38.1,35.075))
        assertTrue(StationCorridor.isAlongRoute(near,route))
        // 3.5km: excluded by old 2km threshold, included by new 5km policy.
        assertTrue(StationCorridor.isDcAlongRoute(midway, route))
        assertTrue(StationCorridor.index(route).isDcAlongRoute(midway))
        assertFalse(StationCorridor.isAlongRoute(far,route))
        assertFalse(StationCorridor.index(route).isDcAlongRoute(far))
    }
    @Test fun acAndNonCcsCannotBecomeDcCcsStop() {
        val base=ChargingStation("verified-source-id","Test","Test","",GeoCoordinate(38.1,35.0),
            listOf(ChargingSocket("1","Type 2","AC","22")),"2026-10-17T00:00:00Z")
        assertFalse(StationCorridor.isAlongRoute(base,route))
        assertFalse(StationCorridor.isAlongRoute(base.copy(sockets=listOf(
            ChargingSocket("1","CHAdeMO","DC","50"))),route))
    }
    @Test fun absentRouteNeverProducesSuggestedStation() {
        assertNull(StationCorridor.distanceMeters(GeoCoordinate(38.1,35.0),emptyList()))
    }
}
