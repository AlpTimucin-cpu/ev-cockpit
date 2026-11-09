package com.evcockpit.data.routing

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NominatimGeocoderTest {
    private val payload = """[
      {"lat":"37.966","lon":"34.680","display_name":"Niğde, Türkiye"},
      {"lat":"999","lon":"34.680","display_name":"Geçersiz konum"},
      {"lat":"36.891","lon":"30.706","display_name":"Antalya, Türkiye"}
    ]"""

    @Test fun parserAcceptsOnlyActualValidCoordinates() {
        val places = NominatimResponseParser.parse(payload)
        assertEquals(2, places.size)
        assertEquals("Nominatim / OpenStreetMap", places[0].source)
        assertEquals(37.966, places[0].coordinate.latitude, 0.0001)
        assertEquals("Antalya, Türkiye", places[1].label)
    }

    @Test fun explicitUserRequestUsesIdentifiedAgentAndCache() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(payload))
            val geocoder = NominatimGeocoder(OkHttpClient())
            val root = server.url("/").toString()
            val one = geocoder.lookup(root, "Niğde")
            val two = geocoder.lookup(root, "Niğde")
            assertEquals(one, two)
            assertEquals(1, server.requestCount)
            val req = server.takeRequest()
            assertEquals("/search", req.requestUrl?.encodedPath)
            assertEquals("Niğde", req.requestUrl?.queryParameter("q"))
            assertEquals("jsonv2", req.requestUrl?.queryParameter("format"))
            assertTrue(req.getHeader("User-Agent")?.contains("EV-Cockpit") == true)
        } finally {
            server.shutdown()
        }
    }

    @Test(expected = RuntimeException::class)
    fun malformedJsonIsRejected() {
        NominatimResponseParser.parse("not json")
    }
}
