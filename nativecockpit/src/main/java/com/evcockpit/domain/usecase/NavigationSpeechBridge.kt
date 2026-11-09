package com.evcockpit.domain.usecase

import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.model.RouteSnapshot

/** Main-thread-owned bridge shared by real GPS and explicitly labelled simulation. */
class NavigationSpeechBridge {
    data class Cue(val token: Long, val message: String)
    data class Evaluation(val route: RouteSnapshot?, val cue: Cue?)

    private val progress = RouteProgressTracker()
    private val policy = ManeuverGuidancePolicy()

    fun evaluate(route: RouteSnapshot?, fix: GpsFix?, nowMs: Long): Evaluation {
        val enriched = progress.enrich(route, fix, nowMs)
        val message = policy.nextAnnouncement(enriched, fix, nowMs)
        val token = policy.pendingCueToken
        val cue = if (message != null && token != null) Cue(token, message) else null
        return Evaluation(enriched, cue)
    }

    fun confirmStarted(token: Long, nowMs: Long) = policy.confirmSpoken(nowMs, token)
    fun cancel(token: Long) = policy.cancelPending(token)
    fun cancelPending() = policy.cancelPending()
    fun reset() = policy.reset()
}
