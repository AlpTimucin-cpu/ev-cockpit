package com.evcockpit.domain.usecase

import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.model.RouteSnapshot
import com.evcockpit.domain.model.validRouteGeometry
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Pure Kotlin, read-only route-distance policy. NavigationViewModel feeds this
 * from LocationRepository.gpsFix (FusedLocationProviderClient), never mock GPS.
 * The output is enqueued in NativeVoiceGuide and acknowledged on TTS onStart.
 */
class ManeuverGuidancePolicy {
    private data class Projection(val metersAlong: Double, val offRoute: Double)

    private var activeRouteId: String? = null
    private var routeAndStep: String? = null
    private val spokenStagesByStep = LinkedHashMap<String, Int>()
    private var lastLevel = -1
    private var proposedLevel: Int? = null
    private var proposedAtMs = 0L
    private var proposalSequence = 0L
    var pendingCueToken: Long? = null
        private set
    private var lastFixTime = 0L
    private var lastSpeechTime = 0L

    fun reset() {
        activeRouteId = null
        routeAndStep = null
        spokenStagesByStep.clear()
        lastLevel = -1
        clearPending()
        lastFixTime = 0L
        lastSpeechTime = 0L
    }

    /**
     * Returns exactly one announcement per route-step and distance stage.
     * One stage at <=650 m, another at <=90 m, based on distance ALONG OSRM.
     * Missing/stale/imprecise GPS or unsupported maneuver => silent, never guess.
     */
    fun nextAnnouncement(route: RouteSnapshot?, fix: GpsFix?, nowMs: Long): String? {
        if (route == null || fix == null) return null
        val routeId = route.routeId?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 128 } ?: return null
        val turn = route.nextManeuver ?: return null
        val stepId = turn.id.trim()
            .takeIf { it.isNotEmpty() && it.length <= 128 } ?: return null
        val instruction = turn.instructionTr.trim().takeIf { text ->
            text.length in 4..140 && text.none { it.isISOControl() || it == '<' || it == '>' }
        } ?: return null
        if (!validRouteGeometry(route.routePoints) || !turn.location.valid()) return null
        if (!fix.usable(nowMs) || fix.accuracyMeters > 35f) return null

        val key = "$routeId|$stepId"
        if (routeId != activeRouteId) {
            activeRouteId = routeId
            spokenStagesByStep.clear()
            lastSpeechTime = 0L
        }
        if (key != routeAndStep) {
            routeAndStep = key
            lastLevel = spokenStagesByStep[key] ?: -1
            clearPending()
            lastFixTime = 0L
        }
        // Fresh LOCATION measurement, not one-second UI clock tick.
        if (fix.recordedAtMillis <= lastFixTime) return null
        lastFixTime = fix.recordedAtMillis

        val vehicle = nearest(route.routePoints, fix.coordinate) ?: return null
        val maneuver = nearest(route.routePoints, turn.location) ?: return null
        if (vehicle.offRoute > 35.0 || maneuver.offRoute > 25.0) return null
        val ahead = maneuver.metersAlong - vehicle.metersAlong
        if (ahead < -10.0 || ahead > 1_500.0) return null

        val level = when {
            ahead <= 90.0 -> 1
            ahead <= 650.0 -> 0
            else -> return null
        }
        // Do not queue duplicate prompts while Android TTS is preparing.
        if (proposedLevel != null && nowMs - proposedAtMs in 0L..4_999L) return null
        if (level <= lastLevel ||
            (lastSpeechTime > 0L && nowMs - lastSpeechTime < 4_000L)
        ) {
            clearPending()
            return null
        }

        proposedLevel = level
        proposedAtMs = nowMs
        pendingCueToken = ++proposalSequence
        return (if (level == 0) "Yaklaşık altı yüz elli metre sonra, " else "Yaklaşık doksan metre sonra, ") +
            instruction
    }

    /** Called only after the TextToSpeech onStart callback for this exact cue. */
    fun confirmSpoken(nowMs: Long, token: Long? = pendingCueToken) {
        if (token == null || token != pendingCueToken) return
        proposedLevel?.let { level ->
            lastLevel = maxOf(lastLevel, level)
            routeAndStep?.let { key ->
                spokenStagesByStep[key] = maxOf(spokenStagesByStep[key] ?: -1, level)
                if (spokenStagesByStep.size > 150) {
                    spokenStagesByStep.remove(spokenStagesByStep.keys.first())
                }
            }
            lastSpeechTime = nowMs
        }
        clearPending()
    }

    /** Failed TTS can be retried only on a later actual GPS measurement. */
    fun cancelPending(token: Long? = pendingCueToken) {
        if (token != null && token == pendingCueToken) clearPending()
    }

    private fun clearPending() {
        proposedLevel = null
        proposedAtMs = 0L
        pendingCueToken = null
    }

    /** Projection onto real OSRM route geometry; not a full road map-matcher. */
    private fun nearest(path: List<GeoCoordinate>, p: GeoCoordinate): Projection? {
        val metersPerLon = 111_320.0 * cos(Math.toRadians(p.latitude))
        val metersPerLat = 111_195.0
        var bestOffset = Double.POSITIVE_INFINITY
        var bestAlong = 0.0
        var cumulative = 0.0
        for (i in 0 until path.lastIndex) {
            val a = path[i]
            val b = path[i + 1]
            val ax = (a.longitude - p.longitude) * metersPerLon
            val ay = (a.latitude - p.latitude) * metersPerLat
            val dx = (b.longitude - a.longitude) * metersPerLon
            val dy = (b.latitude - a.latitude) * metersPerLat
            val planarLength = hypot(dx, dy)
            val segmentMeters = VehicleMotionMath.distanceMeters(a, b)
            if (planarLength < 0.1 || !segmentMeters.isFinite() || segmentMeters < 0.1) continue
            val t = (-(ax * dx + ay * dy) / (planarLength * planarLength)).coerceIn(0.0, 1.0)
            val offset = hypot(ax + t * dx, ay + t * dy)
            if (offset < bestOffset) {
                bestOffset = offset
                bestAlong = cumulative + t * segmentMeters
            }
            cumulative += segmentMeters
        }
        return if (bestOffset.isFinite()) Projection(bestAlong, bestOffset) else null
    }
}