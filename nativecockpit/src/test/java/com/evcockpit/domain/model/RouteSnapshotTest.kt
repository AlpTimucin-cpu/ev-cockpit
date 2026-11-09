package com.evcockpit.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteSnapshotTest {
    @Test fun routeHasNoFakeProgress() {
        assertNull(RouteSnapshot().validProgress())
        assertNull(RouteSnapshot(gpsProgressFraction = -0.2f).validProgress())
        assertNull(RouteSnapshot(gpsProgressFraction = Float.NaN).validProgress())
        assertEquals(0.42f, RouteSnapshot(gpsProgressFraction = 0.42f).validProgress() ?: -1f, 0.00001f)
    }
}
