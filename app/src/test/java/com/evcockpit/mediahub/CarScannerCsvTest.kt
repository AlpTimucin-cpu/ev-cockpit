package com.evcockpit.mediahub

import org.junit.Test
import org.junit.Assert.*

class CarScannerCsvTest {
    @Test fun basicSemicolonCsv() {
        val text="Time;Battery State Of Charge (%);Odometer;Battery voltage;Battery temperature\n" +
            "0;60;30001;395.7;28.5\n1;59.5;30002;396.2;28.6"
        val reading=CarScannerCsv.parse(text)
        assertEquals(59.5,reading.socPercent!!,0.001)
        assertEquals(30002.0,reading.odometerKm!!,0.001)
        assertEquals(396.2,reading.batteryVoltageV!!,0.001)
        assertEquals(28.6,reading.batteryTemperatureC!!,0.001)
        assertEquals(2,reading.inspectedRows)
    }
    @Test fun quotedCellsAndWrongHeaders() {
        val text="Time,Name,Location\n1,\"Bank, near mall\",Ankara"
        val r=CarScannerCsv.parse(text)
        assertFalse(r.hasMeasurements())
        assertEquals(3,CarScannerCsv.splitRow(text.lineSequence().drop(1).first(),',').size)
    }
    @Test fun invalidSocNotSaved() {
        val r=CarScannerCsv.parse("soc;odometer\n130;21550")
        assertNull(r.socPercent)
        assertEquals(21550.0,r.odometerKm!!,0.001)
    }
}
