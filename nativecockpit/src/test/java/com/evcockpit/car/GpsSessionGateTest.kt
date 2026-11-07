package com.evcockpit.car

import org.junit.Assert.*
import org.junit.Test

/** No simulated driving: tests only lifecycle epoch bookkeeping. */
class GpsSessionGateTest {
    @Test fun staleGpsCallbackCannotUpdateReplacementSession() {
        val gate = GpsSessionGate()
        val old = gate.open()
        assertTrue(gate.isCurrent(old))
        gate.invalidate()
        assertFalse(gate.isCurrent(old))
        val fresh = gate.open()
        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(fresh))
        gate.invalidate()
        assertFalse(gate.isCurrent(fresh))
    }

    @Test fun newerRequestSupersedesPreviousFailureCallback() {
        val gate = GpsSessionGate()
        val first = gate.open()
        val second = gate.open()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }
}
