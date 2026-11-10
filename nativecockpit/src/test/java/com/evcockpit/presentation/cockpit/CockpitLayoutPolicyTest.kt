package com.evcockpit.presentation.cockpit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitLayoutPolicyTest {
    @Test fun narrowLandscapeUsesCompactTelemetry() {
        assertTrue(CockpitLayoutPolicy.isCompact(740f, 460f))
    }

    @Test fun lowLandscapeUsesCompactTelemetry() {
        assertTrue(CockpitLayoutPolicy.isCompact(900f, 360f))
    }

    @Test fun wideCarSizedLayoutKeepsFullTelemetry() {
        assertFalse(CockpitLayoutPolicy.isCompact(1024f, 520f))
    }

    @Test fun focusedMapTakesWholeDrivingRow() {
        assertEquals(1f, CockpitLayoutPolicy.mapWeight(compact = true, focused = true), 0f)
        assertEquals(1f, CockpitLayoutPolicy.mapWeight(compact = false, focused = true), 0f)
    }

    @Test fun ordinarySplitWeightsAreUnchanged() {
        assertEquals(.67f, CockpitLayoutPolicy.mapWeight(compact = true, focused = false), 0f)
        assertEquals(.64f, CockpitLayoutPolicy.mapWeight(compact = false, focused = false), 0f)
    }

    @Test fun boundaryIsStable() {
        assertFalse(CockpitLayoutPolicy.isCompact(760f, 400f))
        assertTrue(CockpitLayoutPolicy.isCompact(759f, 400f))
        assertTrue(CockpitLayoutPolicy.isCompact(760f, 399f))
    }
}
