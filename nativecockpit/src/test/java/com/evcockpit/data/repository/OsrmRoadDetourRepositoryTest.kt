package com.evcockpit.data.repository

import com.evcockpit.domain.model.*
import com.evcockpit.domain.usecase.DCCorridorCandidate
import org.junit.Assert.*
import org.junit.Test

class OsrmRoadDetourRepositoryTest {
    private val route = RouteSnapshot(
        routeId = "real-osrm",
        routePoints = listOf(GeoCoordinate(38.0, 33.0), GeoCoordinate(39.0, 35.0)),
        totalKm = 320.0
    )
    private fun candidate(id: Int, distance: Double, ccs: Boolean = true) =
        DCCorridorCandidate(
            station = ChargingStation(
                id = "id-$id", name = "Station $id", operator = "EPDK",
                address = "", coordinate = GeoCoordinate(38.0, 34.0),
                sockets = listOf(ChargingSocket(
                    "s-$id", if (ccs) "CCS2" else "CHAdeMO", "DC", "100 kW"
                )), sourceVerifiedAtUtc = "2026-11-01T00:00:00Z"
            ),
            distanceFromStartKm = distance
        )

    @Test fun noCrossServerDetourEvidenceAndNoDemoFallback() {
        val verified = route.copy(osrmBaseUrl = "https://owner-osrm.example/")
        assertTrue(OsrmRoadDetourRepository.sourceMatches(
            verified, OsrmEndpointSelection("https://owner-osrm.example/", false)))
        assertFalse(OsrmRoadDetourRepository.sourceMatches(
            route, OsrmEndpointSelection("https://owner-osrm.example/", false)))
        assertFalse(OsrmRoadDetourRepository.sourceMatches(
            verified, OsrmEndpointSelection("https://other-osrm.example/", false)))
        assertFalse(OsrmRoadDetourRepository.sourceMatches(
            verified, OsrmEndpointSelection("https://owner-osrm.example/", true)))
        assertFalse(OsrmRoadDetourRepository.sourceMatches(verified, null))
    }

    @Test fun selectsAtMostSixDistributedRealCcsCandidates() {
        val many = (1..30).map { candidate(it, it * 10.0) }
        val result = OsrmRoadDetourRepository.selectCandidates(route, many)
        assertEquals(6, result.size)
        assertEquals(6, result.map { it.station.id }.distinct().size)
        assertTrue(result.zipWithNext().all { (a, b) ->
            a.distanceFromStartKm < b.distanceFromStartKm
        })
    }

    @Test fun excludesOtherConnectorsAndEndpointStations() {
        val result = OsrmRoadDetourRepository.selectCandidates(route, listOf(
            candidate(1, 5.0),
            candidate(2, 100.0, ccs = false),
            candidate(3, 200.0),
            candidate(4, 318.0)
        ))
        assertEquals(listOf("id-3"), result.map { it.station.id })
    }
}
