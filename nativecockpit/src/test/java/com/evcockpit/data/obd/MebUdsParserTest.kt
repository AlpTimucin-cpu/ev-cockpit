package com.evcockpit.data.obd

import org.junit.Assert.*
import org.junit.Test
import com.evcockpit.domain.model.MebResponseStatus

class MebUdsParserTest {
    @Test fun unverifiedBmsDidIsRawOnly() {
        val sample = MebUdsParser.parse("22028C","22028C\r17FE007B 04 62 02 8C 55 AA AA AA\r>",100)
        assertEquals(MebResponseStatus.VALID_RAW,sample.status)
        assertNull(sample.candidateBmsSocPercent)
        assertEquals("62028C55",sample.payloadHex)
    }
    @Test fun unverifiedTemperatureDidIsRawOnly() {
        val sample = MebUdsParser.parse("221E0E","17FE007B07621E0E04680004\r>",100)
        assertEquals(MebResponseStatus.VALID_RAW,sample.status)
        assertNull(sample.candidateMaxBatteryTempC)
        assertNull(sample.candidateBmsSocPercent)
    }
    @Test fun currentMultiframeIsCompleteButNotMappedToAmps() {
        val sample=MebUdsParser.parse("221E3D",
            "17FE007B1008621E3D000249\r17FE007B21963AAAAAAAAAAA\r>",100)
        assertEquals(MebResponseStatus.VALID_RAW,sample.status)
        assertEquals("621E3D000249963A",sample.payloadHex)
        assertNull(sample.candidateBmsSocPercent)
        assertNull(sample.candidateMaxBatteryTempC)
    }
    @Test fun negativeAndForeignFramesFailClosed() {
        assertEquals(MebResponseStatus.NEGATIVE,
            MebUdsParser.parse("22028C","17FE007B 03 7F 22 31 AA AA AA AA>",100).status)
        assertEquals(MebResponseStatus.WRONG_ECU,
            MebUdsParser.parse("22028C","17FE0076 04 62 02 8C 55 AA AA AA>",100).status)
        assertEquals(MebResponseStatus.WRONG_IDENTIFIER,
            MebUdsParser.parse("22028C","17FE007B 04 62 1E 0E 55 AA AA AA>",100).status)
    }
    @Test fun truncatedAndErrorRepliesFailClosed() {
        assertEquals(MebResponseStatus.INCOMPLETE,
            MebUdsParser.parse("221E3D","17FE007B1008621E3D000249>",100).status)
        assertEquals(MebResponseStatus.NO_DATA,
            MebUdsParser.parse("22028C","NO DATA>",100).status)
        assertEquals(MebResponseStatus.ADAPTER_ERROR,
            MebUdsParser.parse("22028C","CAN ERROR>",100).status)
        assertEquals(MebResponseStatus.VALID_RAW,
            MebUdsParser.parse("22028C","17FE007B 04 62 02 8C FF 00 00 00>",100).status)
    }
    @Test fun unverifiedSampleNeverProducesSoCOrTemperature() {
        val soc = MebUdsParser.parse(
            "22028C", "17FE007B 04 62 02 8C 55 AA AA AA>", 100
        )
        val temp = MebUdsParser.parse(
            "221E0E", "17FE007B07621E0E04680004>", 100
        )
        assertNull(soc.candidateBmsSocPercent)
        assertNull(soc.candidateMaxBatteryTempC)
        assertNull(temp.candidateBmsSocPercent)
        assertNull(temp.candidateMaxBatteryTempC)
    }

    @Test(expected=IllegalArgumentException::class)
    fun disallowArbitraryUdsAndSensitiveCommands() {
        MebUdsParser.parse("22F190","17FE007B 04 62 F1 90 00 00 00 00>",100)
    }
    @Test fun readsRealElevenBitCapacityHeader() {
        val sample = MebUdsParser.parse("222AB2", "77A07622AB2041C0400\r>", 1200)
        assertEquals(MebResponseStatus.VALID_RAW, sample.status)
        assertEquals("622AB2041C0400", sample.payloadHex)
        assertEquals("0000077A", sample.responder)
    }

    @Test fun readsRealMultiframeEnergyCounter() {
        val sample = MebUdsParser.parse("221E32", listOf(
            "17FE007B1013621E32008116",
            "17FE007B2162FF7F230602F3",
            "17FE007B22A698FD3759E9AA"
        ).joinToString("\r", postfix = "\r>"), 1200)
        assertEquals(MebResponseStatus.VALID_RAW, sample.status)
        assertEquals("621E3200811662FF7F230602F3A698FD3759E9", sample.payloadHex)
    }

    @Test fun capacityCannotBeForgedFromBatteryEcuid() {
        val sample = MebUdsParser.parse("222AB2", "17FE007B07622AB2041C0400>", 1200)
        assertEquals(MebResponseStatus.WRONG_ECU, sample.status)
    }

}
