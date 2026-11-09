package com.evcockpit.domain.usecase

import com.evcockpit.domain.model.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic positions exist ONLY inside JVM tests, not in the Android APK. */
class NavigationSpeechBridgeTest {
    private val time = 1_800_000_000_000L
    private val route = RouteSnapshot(
        routeId = "test-route",
        routePoints = listOf(GeoCoordinate(37.0, 34.0), GeoCoordinate(37.01, 34.0)),
        maneuvers = listOf(NavigationManeuver(
            "step-7", "sağa dön", GeoCoordinate(37.005, 34.0)
        )),
        totalKm = 1.11, osrmDurationSeconds = 90.0
    )

    private fun recordedFix(alongMeters: Double, now: Long) = GpsFix(
        coordinate = GeoCoordinate(37.0 + alongMeters / 111_195.0, 34.0),
        accuracyMeters = 3f,
        recordedAtMillis = now
    )

    @Test fun measuredFixShapeFeedsBridgeAt650And90MeterThresholds() {
        val bridge = NavigationSpeechBridge()
        val messages = mutableListOf<String>()
        val positions = listOf(300.0, 385.0, 400.0, 520.0, 530.0)
        val seconds = listOf(0, 1, 2, 6, 7)
        positions.forEachIndexed { i, along ->
            val now = time + 1_000L * seconds[i]
            val result = bridge.evaluate(route, recordedFix(along, now), now)
            assertNotNull(result.route?.gpsProgressFraction)
            result.cue?.let {
                messages += it.message
                bridge.confirmStarted(it.token, now)
            }
        }
        assertEquals(
            listOf(
                "Yaklaşık altı yüz elli metre sonra, sağa dön",
                "Yaklaşık doksan metre sonra, sağa dön"
            ), messages
        )
    }

    @Test fun lateCallbacksFromOldRouteCannotCancelNewCue() {
        val bridge = NavigationSpeechBridge()
        fun evaluate(r: RouteSnapshot, at: Long) =
            bridge.evaluate(r, recordedFix(385.0, at), at).cue!!
        val old = evaluate(route, time)
        val replacement = route.copy(routeId = "replacement-route")
        val current = evaluate(replacement, time + 1_000L)
        assertNotEquals(old.token, current.token)
        bridge.confirmStarted(old.token, time + 1_100L)
        bridge.cancel(old.token)
        bridge.cancel(current.token)
        assertEquals(current.message, evaluate(replacement, time + 2_000L).message)
    }

    @Test fun resetDoesNotReuseCallbackTokens() {
        val bridge = NavigationSpeechBridge()
        val first = bridge.evaluate(route, recordedFix(385.0, time), time).cue!!
        bridge.reset()
        val next = bridge.evaluate(
            route, recordedFix(385.0, time + 1_000L), time + 1_000L
        ).cue!!
        assertNotEquals(first.token, next.token)
        bridge.confirmStarted(first.token, time + 1_100L)
        bridge.cancel(next.token)
        assertNotNull(bridge.evaluate(
            route, recordedFix(385.0, time + 2_000L), time + 2_000L
        ).cue)
    }

    @Test fun noRouteStaleAndOffRouteFixesNeverProduceSpeech() {
        val bridge = NavigationSpeechBridge()
        val fix = recordedFix(385.0, time)
        assertNull(bridge.evaluate(null, fix, time).cue)
        assertNull(bridge.evaluate(route, fix, time + 20_000L).cue)
        assertNull(bridge.evaluate(
            route, fix.copy(coordinate = GeoCoordinate(38.0, 34.0)), time
        ).cue)
    }
}
