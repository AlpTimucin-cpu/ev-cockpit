package com.evcockpit.data.radio

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class RadioRepositoryTest {
    @Test fun mapsRealWireNamesAndRequestsPopularTurkishStations() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""[
                {"stationuuid":"a","name":"Radio A","url_resolved":"https://a.example/live","favicon":"https://a.example/icon.png","tags":"pop","votes":40},
                {"stationuuid":"b","name":"Radio B","url_resolved":"https://b.example/live","votes":90},
                {"stationuuid":"c","name":"HTTP","url_resolved":"http://c.example/live","votes":100},
                {"stationuuid":"d","name":"Duplicate","url_resolved":"https://a.example/live","votes":1},
                {"stationuuid":"e","name":null,"url_resolved":null}
            ]"""))
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create()).build()
                .create(RadioBrowserApi::class.java)
            val result = RadioRepository(api).stations().first().getOrThrow()
            assertEquals(listOf("b", "a"), result.map { it.id })
            assertEquals("https://a.example/icon.png", result[1].favicon)
            assertEquals("pop", result[1].tags)
            val url = server.takeRequest().requestUrl!!
            assertEquals("/json/stations/search", url.encodedPath)
            assertEquals("TR", url.queryParameter("countrycode"))
            assertEquals("clickcount", url.queryParameter("order"))
            assertEquals("true", url.queryParameter("reverse"))
            assertEquals("true", url.queryParameter("hidebroken"))
            assertEquals("50", url.queryParameter("limit"))
        } finally { server.shutdown() }
    }

    @Test fun rejectsInvalidStreamsAndKeepsHighestVotedDuplicate() {
        val stations = listOf(
            RadioStation("a", "A", "file:///etc/test", votes = 100),
            RadioStation("b", "B", "https://example.org/live", votes = 1),
            RadioStation("c", "C", "https://example.org/live", votes = 9),
            RadioStation("d", "D", "not-a-url"),
            RadioStation("c", "Another URL, same ID", "https://other.example/live", votes = 2)
        )
        assertEquals(listOf("c"), RadioRepository.playableStations(stations).map { it.id })
    }
    @Test fun serverFailuresAreReturnedAsResultInsteadOfCrashingCollector() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(503).setBody("Unavailable"))
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create()).build()
                .create(RadioBrowserApi::class.java)
            val emission = RadioRepository(api).stations().first()
            assertTrue(emission.isFailure)
            assertTrue(emission.exceptionOrNull() is retrofit2.HttpException)
        } finally { server.shutdown() }
    }

}
