package com.evcockpit.data.obd

import org.junit.Assert.*
import org.junit.Test

class ElmSpeedParserTest {
    @Test fun standardHexAndWhitespace() {
        assertEquals(40.0, ElmSpeedParser.decode("010D\r41 0D 28\r>"))
        assertEquals(100.0, ElmSpeedParser.decode("7E8 03 41 0D 64\r>"))
        assertEquals(255.0, ElmSpeedParser.decode("410DFF>"))
        assertEquals(0.0, ElmSpeedParser.decode("41 0D 00>"))
    }
    @Test fun rejectsErrorOrAmbiguousResponses() {
        assertNull(ElmSpeedParser.decode("NO DATA>"))
        assertNull(ElmSpeedParser.decode("CAN ERROR>"))
        assertNull(ElmSpeedParser.decode("41 0D 20\r41 0D 40>"))
        assertNull(ElmSpeedParser.decode("41 5B 80>")) // Battery-life PID != speed
        assertNull(ElmSpeedParser.decode("41 0D XY>"))
        assertNull(ElmSpeedParser.decode("Z".repeat(4097)))
    }
    @Test fun duplicateSameEcuFramesAreSafe() {
        assertEquals(40.0, ElmSpeedParser.decode("7E8 03 41 0D 28\r7E8 03 41 0D 28>"))
    }
}
