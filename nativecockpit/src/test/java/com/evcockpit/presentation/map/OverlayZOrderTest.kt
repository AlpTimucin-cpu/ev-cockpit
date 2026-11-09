package com.evcockpit.presentation.map

import org.junit.Assert.*
import org.junit.Test

class OverlayZOrderTest {
    private val route = "eya-route-line"
    private val weather = "eya-weather-markers"
    private val dc = "eya-station-dc-icons"
    private val speed = "eya-osrm-speed-normal"

    @Test fun markersMustRemainAboveRealRouteLayer() {
        assertTrue(OverlayZOrder.isCorrect(
            listOf("tile-base", route, speed, dc, weather),
            route, listOf(speed, dc, weather)
        ))
        assertFalse(OverlayZOrder.isCorrect(
            listOf("tile-base", dc, route, speed, weather),
            route, listOf(speed, dc, weather)
        ))
        assertFalse(OverlayZOrder.isCorrect(
            listOf("tile-base", weather, route, speed, dc),
            route, listOf(speed, dc, weather)
        ))
    }

    @Test fun orderDoesNotRequireWeatherOrChargingToHaveArrived() {
        assertTrue(OverlayZOrder.isCorrect(
            listOf("tile-base", route, speed),
            route, listOf(speed, dc, weather)
        ))
        assertFalse(OverlayZOrder.isCorrect(
            listOf("tile-base", dc, weather),
            route, listOf(speed, dc, weather)
        ))
    }

    @Test fun laterOverlaysRetainWeatherOnTop() {
        assertFalse(OverlayZOrder.isCorrect(
            listOf("tile-base", route, weather, dc),
            route, listOf(speed, dc, weather)
        ))
        assertTrue(OverlayZOrder.isCorrect(
            listOf("tile-base", route, dc, "other-provider", weather),
            route, listOf(speed, dc, weather)
        ))
    }
}
