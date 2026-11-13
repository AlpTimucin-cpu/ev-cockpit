package com.evcockpit.data.traffic

import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.model.RouteSnapshot
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

class TomTomTrafficHttpTest {
    private val key = "test-only-0123456789abcdef0123456789"
    private val bbox = TomTomBounds(34.0, 37.0, 34.2, 37.2)

    @Test fun authenticatedIncidentCallNeverPlacesKeyInUrl() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setHeader("Date", "Fri, 13 Nov 2026 11:00:00 GMT")
                .setBody("""{"incidents":[]}"""))
            server.start()
            val http = TomTomTrafficHttp(OkHttpClient(), server.url("/").toString())
            val result = http.incidents(key, bbox)
            assertEquals("{\"incidents\":[]}", String(result.bytes))
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals(key, request.getHeader("TomTom-Api-Key"))
            assertNull(request.requestUrl?.queryParameter("key"))
            assertTrue(request.path!!.startsWith(
                "/maps/orbis/traffic/incidents/details?"))
            assertEquals(Instant.parse("2026-11-13T11:00:00Z").toEpochMilli(),
                result.responseDateMs)
        }
    }

    @Test fun authenticatedRasterFlowTilesNeverLeakKeyInRequestPath() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png")
                .setBody(okio.Buffer().write(byteArrayOf(0x89.toByte(), 80, 78, 71))))
            server.start()
            val http = TomTomTrafficHttp(OkHttpClient(), server.url("/").toString())
            val result = http.flowTile(key, TomTomTileId(8, 152, 101))
            assertEquals(4, result.bytes.size)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals(key, request.getHeader("TomTom-Api-Key"))
            assertNull(request.requestUrl?.queryParameter("key"))
            assertTrue(request.path!!.contains("flow/raster/tile/8/152/101"))
        }
    }

    @Test fun quotaErrorDoesNotGetParsedAsNoIncidents() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429))
            server.start()
            val http = TomTomTrafficHttp(OkHttpClient(), server.url("/").toString())
            try {
                http.incidents(key, bbox)
                fail("429 should throw")
            } catch (expected: TomTomHttpException) {
                assertEquals(429, expected.status)
            }
        }
    }

    @Test fun parserAcceptsVerifiedOnRouteIncidentAndRejectsOffRouteData() {
        val root = """{"incidents":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[34.0,38.0]},
           "properties":{"iconCategory":"accident","timeValidity":"present",
            "events":[{"description":"Crash","iconCategory":"accident"}]}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[36.0,39.0]},
           "properties":{"iconCategory":"jam","timeValidity":"present",
            "events":[{"description":"Jam"}]}}
        ]}"""
        val incidents = TomTomIncidentParser.parse(root.toByteArray(),
            listOf(GeoCoordinate(38.0, 34.0)))
        assertEquals(1, incidents.size)
        assertEquals("accident", incidents.single().category)
        assertEquals("Crash", incidents.single().description)
    }

    @Test fun corridorQueriesAreBoundedAndRequireRealProgress() {
        val missing = RouteSnapshot(routeId = "r",
            routePoints = listOf(GeoCoordinate(38.0, 34.0), GeoCoordinate(38.5, 34.0)))
        assertNull(missing.validProgress())
        val b = TomTomBounds(34.0, 37.0, 34.1, 37.1)
        assertTrue(b.valid())
        assertFalse(TomTomBounds(34.0, 37.0, 36.0, 40.0).valid())
        assertEquals(8, TomTomCorridorPolicy.tileId(GeoCoordinate(38.0,34.0)).z)
    }
}
