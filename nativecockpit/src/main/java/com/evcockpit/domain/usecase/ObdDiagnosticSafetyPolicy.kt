package com.evcockpit.domain.usecase

import com.evcockpit.domain.model.ObdConnectionState
import com.evcockpit.domain.model.ObdPhase

/**
 * Only a recently measured SAE PID 010D zero-speed value can enable a
 * user-initiated raw MEB diagnostic. This is an additional risk reduction,
 * NOT proof of transmission Park/ignition state and never a permission to
 * run diagnostics while driving. No GPS or default speed substitutes.
 */
object ObdDiagnosticSafetyPolicy {
    const val MAX_SPEED_AGE_MS = 3_000L
    const val MAX_STATIONARY_KMH = 0.5

    fun canRequest(state: ObdConnectionState, nowMs: Long): Boolean {
        if (state.phase != ObdPhase.CONNECTED || !state.lastPollSucceeded ||
            state.diagnosticBusy || nowMs <= 0L) return false
        val at = state.lastSpeedAtMs ?: return false
        val speed = state.lastSpeedKmh ?: return false
        return at > 0L && nowMs >= at && nowMs - at <= MAX_SPEED_AGE_MS &&
            speed.isFinite() && speed in 0.0..MAX_STATIONARY_KMH
    }

    /** Checks the same speed proof while a request is already queued. */
    fun canExecute(state: ObdConnectionState, nowMs: Long): Boolean =
        canRequest(state.copy(diagnosticBusy = false), nowMs)
}
