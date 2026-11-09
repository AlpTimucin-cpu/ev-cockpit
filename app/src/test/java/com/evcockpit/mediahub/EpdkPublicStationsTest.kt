package com.evcockpit.mediahub

import org.junit.Assert.*
import org.junit.Test

class EpdkPublicStationsTest {
    @Test fun incompleteOfficialDataFailsClosed() {
        val raw="""{"schema_version":1,
          "source_url":"https://apigateway.epdk.gov.tr/sarjIstasyonlari",
          "verified_at_utc":"2026-10-18T00:00:00Z",
          "all_rows_count":20000,"public_rows_count":10000,
          "geocoded_public_rows_count":1,
          "prices_included":false,"live_status_included":false,
          "stations":[]}"""
        try {
            EpdkPublicStations.parse(raw)
            fail("Impossible EPDK counts must be rejected")
        } catch (_: IllegalArgumentException) {}
    }
    @Test fun officialSocketIsNotMisrepresentedAsLiveOrPriced() {
        val e=EpdkPublicStations.Entry("42","Deneme","İşletmeci","L1",
            "Niğde",37.9,34.6,
            listOf(EpdkPublicStations.Socket("1","CCS","DC","120")))
        val point=e.asPoint()
        assertEquals("EPDK • resmî halka açık",point.source)
        assertTrue(point.rawPower.contains("birim doğrulanmadı"))
    }
}
