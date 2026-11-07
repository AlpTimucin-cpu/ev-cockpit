package com.evcockpit.data.routing

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class OsrmDemoRateLimiterTest {
    @Test fun identificationHeaderOnlyInDemoMode() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setBody("{}"))
            server.start()
            val regular = OsrmDemoRateLimiter.client(OkHttpClient(), false)
            regular.newCall(Request.Builder().url(server.url("/normal")).build())
                .execute().close()
            val normalAgent = server.takeRequest().getHeader("User-Agent")
            val demo = OsrmDemoRateLimiter.client(OkHttpClient(), true)
            demo.newCall(Request.Builder().url(server.url("/demo")).build())
                .execute().close()
            val trialAgent = server.takeRequest().getHeader("User-Agent")
            assertNotNull(normalAgent)
            assertTrue(trialAgent?.startsWith("EV-Cockpit-Debug/") == true)
            assertNotEquals(normalAgent, trialAgent)
        }
    }

    @Test fun demoCallsAreSerializedAndNoMoreThanOneStartPerSecond() = runBlocking {
        val limiter = OsrmDemoRateLimiter()
        val seen = mutableListOf<Long>()
        limiter.execute(true) { seen += System.nanoTime() }
        limiter.execute(true) { seen += System.nanoTime() }
        assertEquals(2, seen.size)
        assertTrue(
            "Two public requests must not start within 1 second",
            (seen[1] - seen[0]) / 1_000_000L >= 1_000L
        )
    }

    @Test fun customServiceCallsHaveNoDemoDelay() = runBlocking {
        val limiter = OsrmDemoRateLimiter()
        var called = false
        limiter.execute(false) { called = true }
        assertTrue(called)
    }
}
