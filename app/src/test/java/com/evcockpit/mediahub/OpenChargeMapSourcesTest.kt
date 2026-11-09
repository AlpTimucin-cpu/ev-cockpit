package com.evcockpit.mediahub

import org.junit.Test
import org.junit.Assert.*

class OpenChargeMapSourcesTest {
    @Test fun parseAndDeduplicate() {
        val sample="""[{"ID":123,"AddressInfo":{"Title":"Hızlı Şarj","Latitude":37.963,
           "Longitude":34.67},"OperatorInfo":{"Title":"Deneme"},
           "Connections":[{"ConnectionType":{"Title":"CCS (Type 2)"},"PowerKW":120}]}]"""
        val stations=OpenChargeMapSources.parse(sample)
        assertEquals(1,stations.size)
        assertEquals("Open Charge Map",stations[0].source)
        assertEquals("120 kW",stations[0].rawPower)
        val copy=stations[0].copy(osmId="node:22",source="OpenStreetMap")
        assertEquals(1,OpenChargeMapSources.merge(listOf(copy),stations).size)
    }
}
