package com.evcockpit.presentation.map

import com.evcockpit.data.traffic.TomTomFlowTile
import org.junit.Assert.*
import org.junit.Test

class TomTomTileQuadTest {
    @Test fun tileCornersAreOrderedFromUpperLeftClockwise() {
        val tile = TomTomFlowTile(8, 151, 101, byteArrayOf(), 1_800_000_000_000L)
        val bounds = TomTomTileQuad.from(tile)
        assertTrue(bounds.topLeft.latitude > bounds.bottomLeft.latitude)
        assertTrue(bounds.topRight.longitude > bounds.topLeft.longitude)
        assertEquals(bounds.topLeft.latitude, bounds.topRight.latitude, 0.000001)
        assertEquals(bounds.bottomLeft.latitude, bounds.bottomRight.latitude, 0.000001)
    }
}
