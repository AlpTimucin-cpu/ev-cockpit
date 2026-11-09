package com.evcockpit.domain.usecase

import org.junit.Assert.*
import org.junit.Test
import com.evcockpit.domain.model.*

class ManeuverGuidancePolicyTest {
    private val now = 1_800_000_000_000L
    private val a = GeoCoordinate(38.00, 35.00)
    private val b = GeoCoordinate(38.00, 35.03)
    private val destination = GeoCoordinate(38.00, 35.029)
    private val turn = NavigationManeuver("step-1", "sağa dön", destination)
    private val route = RouteSnapshot(
        routeId = "real-route-id",
        nextManeuver = turn,
        routePoints = listOf(a, b)
    )
    private fun fix(
        lon: Double,
        time: Long = now,
        lat: Double = 38.00,
        accuracy: Float = 8f
    ) = GpsFix(GeoCoordinate(lat, lon), accuracy, time)

    @Test fun noRealRouteOrGpsNoSpeech() {
        val p = ManeuverGuidancePolicy()
        assertNull(p.nextAnnouncement(null, fix(35.027), now))
        assertNull(p.nextAnnouncement(RouteSnapshot(), fix(35.027), now))
        assertNull(p.nextAnnouncement(route, null, now))
        assertNull(p.nextAnnouncement(route, fix(35.027, now - 12_000), now))
        assertNull(p.nextAnnouncement(route, fix(35.027, accuracy = 60f), now))
    }

    @Test fun speechAt650And90MeterStages() {
        val p = ManeuverGuidancePolicy()
        // Do not read a turn from a kilometre away.
        assertNull(p.nextAnnouncement(route, fix(35.010), now))

        val first = fix(35.027, now + 1_000)
        assertEquals("Yaklaşık altı yüz elli metre sonra, sağa dön",
            p.nextAnnouncement(route, first, now + 1_000))
        assertNull(p.nextAnnouncement(route, first, now + 1_500))

        // A queued request is not consumed, but do not enqueue it every GPS tick.
        assertNull(p.nextAnnouncement(route, fix(35.0271, now + 2_000), now + 2_000))
        // The Android engine reports onStart, so the cue can now be consumed.
        p.confirmSpoken(now + 2_000)
        assertNull(p.nextAnnouncement(route, fix(35.0272, now + 3_000), now + 3_000))

        val final = fix(35.0285, now + 7_000)
        assertEquals("Yaklaşık doksan metre sonra, sağa dön",
            p.nextAnnouncement(route, final, now + 7_000))
        p.confirmSpoken(now + 7_000)
        assertNull(p.nextAnnouncement(route, fix(35.0286, now + 8_000), now + 8_000))
    }

    @Test fun failedSynthesisCanRetryAtNextFix() {
        val p = ManeuverGuidancePolicy()
        assertNotNull(p.nextAnnouncement(route, fix(35.027, now), now))
        p.cancelPending() // TextToSpeech.onError / onStop / focus denied
        assertEquals(
            "Yaklaşık altı yüz elli metre sonra, sağa dön",
            p.nextAnnouncement(route, fix(35.0271, now + 1_000), now + 1_000)
        )
    }

    @Test fun silentEngineTimeoutReleasesQueuedCueForRetry() {
        val p = ManeuverGuidancePolicy()
        assertNotNull(p.nextAnnouncement(route, fix(35.027, now), now))
        assertNull(p.nextAnnouncement(route, fix(35.0271, now + 1_000), now + 1_000))
        assertEquals(
            "Yaklaşık altı yüz elli metre sonra, sağa dön",
            p.nextAnnouncement(route, fix(35.0272, now + 6_000), now + 6_000)
        )
    }

    @Test fun nearTurnAndOffRouteFailClosed() {
        val p = ManeuverGuidancePolicy()
        assertNull(p.nextAnnouncement(route, fix(35.028, lat = 38.001), now))
        val near = ManeuverGuidancePolicy()
        assertEquals("Yaklaşık doksan metre sonra, sağa dön",
            near.nextAnnouncement(route, fix(35.0289), now))
        near.confirmSpoken(now)
        assertNull(near.nextAnnouncement(route, fix(35.029, now + 5_000), now + 5_000))
        assertNull(near.nextAnnouncement(route, fix(35.030, now + 10_000), now + 10_000))
    }

    @Test fun neverSpeakUntrustedInstruction() {
        val p = ManeuverGuidancePolicy()
        val bad = route.copy(nextManeuver = turn.copy(
            instructionTr = "<script>sağa dön</script>"
        ))
        assertNull(p.nextAnnouncement(bad, fix(35.027), now))
        assertNull(p.nextAnnouncement(route.copy(routeId = null), fix(35.027), now))
    }

    @Test fun stepJitterNeverRepeatsAcknowledgedCue() {
        val policy = ManeuverGuidancePolicy()
        assertNotNull(policy.nextAnnouncement(route, fix(35.027, now), now))
        policy.confirmSpoken(now)
        val stepTwo = route.copy(
            nextManeuver = turn.copy(id = "step-2", instructionTr = "continue straight")
        )
        assertNotNull(policy.nextAnnouncement(
            stepTwo, fix(35.0271, now + 5_000), now + 5_000
        ))
        policy.confirmSpoken(now + 5_000)
        // Map-matching GPS jitter temporarily reselects the previous maneuver.
        assertNull(policy.nextAnnouncement(
            route, fix(35.0272, now + 10_000), now + 10_000
        ))
    }

    @Test fun routeChangeResetsAcknowledgedStages() {
        val p = ManeuverGuidancePolicy()
        assertNotNull(p.nextAnnouncement(route, fix(35.027), now))
        p.confirmSpoken(now)
        val second = route.copy(routeId = "new-route", nextManeuver = turn.copy(id = "new-step"))
        assertNotNull(p.nextAnnouncement(second, fix(35.027, now + 5_000), now + 5_000))
    }
}
