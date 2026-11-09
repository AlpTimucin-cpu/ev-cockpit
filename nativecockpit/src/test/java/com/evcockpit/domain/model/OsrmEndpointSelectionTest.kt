package com.evcockpit.domain.model

import org.junit.Assert.*
import org.junit.Test

class OsrmEndpointSelectionTest {
    @Test fun noDemoWithoutExplicitConsentOrInRelease() {
        assertNull(OsrmEndpointSelector.choose(ServiceEndpointConfig(), true))
        assertNull(OsrmEndpointSelector.choose(
            ServiceEndpointConfig(debugDemoRoutingEnabled = true), false
        ))
        val chosen = OsrmEndpointSelector.choose(
            ServiceEndpointConfig(debugDemoRoutingEnabled = true), true
        )
        assertEquals("https://router.project-osrm.org/", chosen?.baseUrl)
        assertTrue(chosen?.demo == true)
    }

    @Test fun ownServerIsPreferredAndNotConfusedWithDemo() {
        val chosen = OsrmEndpointSelector.choose(
            ServiceEndpointConfig(
                osrmBaseUrl = "https://private-routing.example.net/",
                debugDemoRoutingEnabled = true
            ), true
        )
        assertEquals("https://private-routing.example.net/", chosen?.baseUrl)
        assertFalse(chosen?.demo ?: true)
    }

    @Test fun userProvidedDemoUrlStillRejectedAsProductionEndpoint() {
        assertNull(ServiceEndpointConfig.checkedHttpsRoot(
            "https://router.project-osrm.org/", osrm = true
        ))
        assertNull(OsrmEndpointSelector.choose(ServiceEndpointConfig(
            osrmBaseUrl = "http://insecure.example", debugDemoRoutingEnabled = false
        ), false))
    }

    @Test fun debugDemoRequestsAreRateLimitedWithoutWallClockFallback() {
        val gate = DebugDemoRequestGate()
        assertFalse(gate.accept(0L))
        assertTrue(gate.accept(10_000L))
        assertFalse(gate.accept(10_001L))
        assertFalse(gate.accept(20_000L))
        assertFalse(gate.accept(9_000L))
        assertTrue(gate.accept(25_000L))
    }
}
