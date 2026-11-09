package com.evcockpit.domain.model

import org.junit.Assert.*
import org.junit.Test

class MapGeometryTest {
    @Test fun refusesBadCoordinates() {
        assertFalse(GeoCoordinate(95.0, 35.0).valid())
        assertFalse(GeoCoordinate(38.0, 185.0).valid())
        assertFalse(validRouteGeometry(emptyList()))
        assertFalse(validRouteGeometry(listOf(GeoCoordinate(39.0, 35.0))))
        assertFalse(validRouteGeometry(listOf(GeoCoordinate(39.0,35.0), GeoCoordinate(39.0,35.0))))
    }
    @Test fun acceptsRealPolylineShape() {
        // Test fixture is only geometry validation; NEVER exposed in the map.
        assertTrue(validRouteGeometry(listOf(
            GeoCoordinate(38.0, 34.0), GeoCoordinate(38.1, 34.2))))
    }
    @Test fun oldAndInaccurateFixesAreHidden() {
        val now = 1_700_000_020_000L
        val p = GeoCoordinate(38.0, 34.0)
        assertTrue(GpsFix(p, 10f, now-1_000L).usable(now))
        assertFalse(GpsFix(p, 45f, now-1_000L).usable(now))
        assertFalse(GpsFix(p, 10f, now-11_000L).usable(now))
        assertFalse(GpsFix(p, 10f, now+1_000L).usable(now))
    }
}
