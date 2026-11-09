package com.evcockpit.mediahub

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ChargingStationDiscoveryTest {
    private val official = listOf(
        EpdkPublicStations.Entry(
            "100","Merkez DC","Deneme Ağı","L-11",
            "Niğde Merkez",37.97,34.67,
            listOf(EpdkPublicStations.Socket("1","CCS","DC","120"),
                   EpdkPublicStations.Socket("2","Type 2","AC","22"))),
        EpdkPublicStations.Entry(
            "200","Diğer","Örnek","L-22",
            "Antalya Merkez",36.9,30.7,
            listOf(EpdkPublicStations.Socket("1","CHAdeMO","DC","50")))
    )
    @Test fun connectorFilterFollowsOfficialSocketMetadata() {
        val result=ChargingStationDiscovery.filter(official,
            ChargingStationDiscovery.Filter(connector=ChargingStationDiscovery.Connector.CCS))
        assertEquals(listOf("100"),result.map{it.stationId})
        val type2=ChargingStationDiscovery.filter(official,
            ChargingStationDiscovery.Filter(connector=ChargingStationDiscovery.Connector.TYPE2))
        assertEquals(1,type2.size)
    }
    @Test fun cityAndOperatorAreFilterable() {
        val result=ChargingStationDiscovery.filter(official,
            ChargingStationDiscovery.Filter(operator="Deneme",text="Niğde"))
        assertEquals("100",result.single().stationId)
        assertEquals(0,ChargingStationDiscovery.filter(official,
            ChargingStationDiscovery.Filter(text="İstanbul")).size)
    }
    @Test fun staleAvailabilityMustNotStayGreen() {
        val now=Instant.parse("2026-10-18T00:05:00Z")
        val old=ChargingStationDiscovery.SocketAvailability(
            "100","1",ChargingStationDiscovery.Availability.AVAILABLE,
            Instant.parse("2026-10-18T00:02:00Z"),"EPDK-licensed-source")
        assertEquals(ChargingStationDiscovery.Availability.UNKNOWN,old.current(now))
    }
    @Test fun timeBandIsNotAnEvergreenTariff() {
        val time=Instant.parse("2026-10-18T09:00:00Z")
        val valid=ChargingStationDiscovery.TimedTariff("1",11.25,
            Instant.parse("2026-10-18T08:00:00Z"),
            Instant.parse("2026-10-18T10:00:00Z"),
            Instant.parse("2026-10-18T08:59:00Z"),"licensed-provider")
        assertTrue(valid.currentlyApplicable(time))
        assertFalse(valid.currentlyApplicable(
            Instant.parse("2026-10-18T10:01:00Z")))
    }
    @Test fun noProprietaryLiveValuesInvented() {
        val details=ChargingStationDiscovery.socketDetails(official.first())
        assertTrue(details.contains("Bilinmiyor"))
        assertTrue(details.contains("Doğrulanmadı"))
    }
}
