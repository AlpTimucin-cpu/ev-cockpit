package com.evcockpit.data.routing

import com.evcockpit.domain.model.GeoCoordinate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import retrofit2.Retrofit

class OsrmDetourResponseParserTest {
    private fun fixture(snap: Double = 22.0, total: Double = 306_000.0) =
        """{"code":"Ok","waypoints":[{"distance":2},{"distance":$snap},
            {"distance":2}],"routes":[{"distance":$total,"duration":14400,
            "legs":[{"distance":152000},{"distance":154000}]}]}"""

    @Test fun acceptsTwoLegRealRoadRouteWithExplicitSnapping() {
        val verified = OsrmDetourResponseParser.parse(
            fixture(), "osrm-route-a", "station-one", 300.0, 14100.0, 1700000000000L
        )
        assertNotNull(verified)
        assertEquals(6.0, verified!!.additionalKm, 0.001)
        assertEquals(152.0, verified.roadDistanceToStationKm, 0.001)
        assertEquals(5.0, verified.additionalMinutes!!, 0.001)
        assertTrue(verified.validFor("osrm-route-a", "station-one"))
        assertFalse(verified.validFor("different-route", "station-one"))
    }

    @Test fun rejectsFarRoadSnapAndAlternativeShorterThanBaseline() {
        assertNull(OsrmDetourResponseParser.parse(
            fixture(snap = 2500.0), "r", "s", 300.0, null, 1700000000000L
        ))
        assertNull(OsrmDetourResponseParser.parse(
            fixture(), "r", "s", 320.0, null, 1700000000000L
        ))
        assertNull(OsrmDetourResponseParser.parse(
            fixture(total = 310_000.0), "r", "s", 300.0, null, 1700000000000L
        ))
    }

    @Test fun realRetrofitRequestContainsOriginStationDestinationWithNoGeometry() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(fixture()))
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .build().create(OsrmService::class.java)
            val requestUrl = OsrmRequestFormat.urlDetour(
                GeoCoordinate(38.0, 34.0),
                GeoCoordinate(38.5, 34.5),
                GeoCoordinate(39.0, 35.0)
            )
            val result = api.route(requestUrl)
            assertTrue(result.isSuccessful)
            val parsed = result.body()!!.use {
                OsrmDetourResponseParser.parse(
                    it.string(), "real", "dc", 300.0, 14100.0, 1700000000000L
                )
            }
            assertNotNull(parsed)
            val request = server.takeRequest()
            assertTrue(request.requestUrl!!.encodedPath.endsWith(
                "34.000000,38.000000;34.500000,38.500000;35.000000,39.000000"
            ))
            assertEquals("false", request.requestUrl!!.queryParameter("overview"))
            assertEquals("false", request.requestUrl!!.queryParameter("steps"))
            assertEquals("false", request.requestUrl!!.queryParameter("alternatives"))
        } finally {
            server.shutdown()
        }
    }
}
