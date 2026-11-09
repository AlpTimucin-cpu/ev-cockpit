package com.evcockpit.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleTelemetryTest {
    @Test fun unknownAndStaleValuesAreNotShown() {
        assertNull(TimedValue<Double>().current(30_000L))
        val battery = TimedValue(75.0, measuredAtMillis = 1_000L)
        assertEquals(75.0, battery.current(10_999L) ?: -1.0, 0.0001)
        assertNull(battery.current(11_001L))
        assertNull(battery.current(900L)) // Future timestamps are invalid.
    }
}
