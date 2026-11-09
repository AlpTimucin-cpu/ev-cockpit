package com.evcockpit.data.routing

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import retrofit2.Retrofit
import com.evcockpit.domain.model.GeoCoordinate

class OsrmHttpIntegrationTest {
    @Test fun retrofitPreservesCoordinatesAndReportsHttp400() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(400)
                .setBody("""{"code":"InvalidQuery","message":"Invalid coordinates"}"""))
            val service = Retrofit.Builder().baseUrl(server.url("/"))
                .build().create(OsrmService::class.java)
            val relative = OsrmRequestFormat.url(
                GeoCoordinate(37.456, 34.123), GeoCoordinate(36.123, 30.789)
            )
            val localUrl = server.url("/" + relative)
            val response = service.route(localUrl.toString())
            assertFalse(response.isSuccessful)
            assertEquals(400, response.code())
            response.errorBody()?.close()
            val request = server.takeRequest()
            assertEquals("/route/v1/driving/34.123000,37.456000;30.789000,36.123000",
                request.requestUrl?.encodedPath)
            assertEquals("true", request.requestUrl?.queryParameter("alternatives"))
            assertEquals("true", request.requestUrl?.queryParameter("steps"))
            assertEquals("geojson", request.requestUrl?.queryParameter("geometries"))
            assertEquals("full", request.requestUrl?.queryParameter("overview"))
        } finally { server.shutdown() }
    }
}
