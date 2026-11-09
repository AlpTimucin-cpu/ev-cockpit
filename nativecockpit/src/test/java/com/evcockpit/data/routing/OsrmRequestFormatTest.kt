package com.evcockpit.data.routing

import org.junit.Assert.*
import org.junit.Test
import com.evcockpit.domain.model.GeoCoordinate

class OsrmRequestFormatTest {
    @Test fun longitudeLatitudeNeverSwapped() {
        val a = GeoCoordinate(37.456, 34.123)
        val b = GeoCoordinate(36.123, 30.789)
        assertEquals("34.123000,37.456000;30.789000,36.123000",
            OsrmRequestFormat.coordinates(a, b))
        assertTrue(OsrmRequestFormat.url(a, b).contains(
            "/driving/34.123000,37.456000;30.789000,36.123000?"))
    }
    @Test(expected=IllegalArgumentException::class)
    fun identicalPointsAreRejected() {
        val a = GeoCoordinate(37.456, 34.123)
        OsrmRequestFormat.coordinates(a, a)
    }
}
