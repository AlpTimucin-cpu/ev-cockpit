package com.evcockpit.domain.usecase

import com.evcockpit.domain.model.ObdConnectionState
import com.evcockpit.domain.model.ObdPhase
import org.junit.Assert.*
import org.junit.Test

class ObdDiagnosticSafetyPolicyTest {
    private val now = 100_000L
    private fun state(speed: Double? = 0.0, at: Long? = now) =
        ObdConnectionState(
            phase = ObdPhase.CONNECTED,
            lastSpeedAtMs = at,
            lastSpeedKmh = speed,
            lastPollSucceeded = true
        )

    @Test fun userInitiatedRawDiagnosticNeedsFreshRealStationarySpeed() {
        assertTrue(ObdDiagnosticSafetyPolicy.canRequest(state(), now))
        assertTrue(ObdDiagnosticSafetyPolicy.canRequest(state(0.5), now))
        assertFalse(ObdDiagnosticSafetyPolicy.canRequest(state(1.0), now))
        assertFalse(ObdDiagnosticSafetyPolicy.canRequest(state(25.0), now))
        assertFalse(ObdDiagnosticSafetyPolicy.canRequest(state(Double.NaN), now))
        assertFalse(ObdDiagnosticSafetyPolicy.canRequest(state(null), now))
    }

    @Test fun oldUnknownOrUnhealthyReadNeverOpensDiagnostic() {
        assertFalse(ObdDiagnosticSafetyPolicy.canRequest(state(at = now - 3001L), now))
        assertFalse(ObdDiagnosticSafetyPolicy.canRequest(state(at = now + 1L), now))
        assertFalse(ObdDiagnosticSafetyPolicy.canRequest(state(at = null), now))
        assertFalse(ObdDiagnosticSafetyPolicy.canRequest(state().copy(lastPollSucceeded = false), now))
        assertFalse(ObdDiagnosticSafetyPolicy.canRequest(state().copy(phase = ObdPhase.ERROR), now))
        assertFalse(ObdDiagnosticSafetyPolicy.canRequest(state().copy(diagnosticBusy = true), now))
        assertTrue(ObdDiagnosticSafetyPolicy.canExecute(state().copy(diagnosticBusy = true), now))
        assertFalse(ObdDiagnosticSafetyPolicy.canExecute(
            state(5.0).copy(diagnosticBusy = true), now
        ))
    }
}
