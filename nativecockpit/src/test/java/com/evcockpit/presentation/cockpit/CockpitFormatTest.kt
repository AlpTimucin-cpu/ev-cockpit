package com.evcockpit.presentation.cockpit

import org.junit.Assert.assertEquals
import org.junit.Test

class CockpitFormatTest {
    @Test
    fun unavailableTelemetryIsNotShownAsZero() {
        assertEquals("— %", CockpitFormat.percent(null))
        assertEquals("— km/h", CockpitFormat.speed(null))
        assertEquals("— kW", CockpitFormat.power(null))
        assertEquals("— km", CockpitFormat.distance(null))
        assertEquals("— %", CockpitFormat.percent(Double.NaN))
        assertEquals("— km/h", CockpitFormat.speed(-2.0))
    }

    @Test
    fun validReadingsHaveUnitsAndTurkishDecimalPunctuation() {
        assertEquals("84%", CockpitFormat.percent(84.0))
        assertEquals("92 km/h", CockpitFormat.speed(92.0))
        assertEquals("12,5 kW", CockpitFormat.power(12.5))
        assertEquals("24,3 °C", CockpitFormat.temperature(24.3))
    }
}
