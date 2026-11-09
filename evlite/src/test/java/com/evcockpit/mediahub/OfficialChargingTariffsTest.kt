package com.evcockpit.mediahub

import org.junit.Test
import org.junit.Assert.*
import java.time.Instant

class OfficialChargingTariffsTest {
    @Test fun onlyOfficialFreshTariffsShowAsCurrent() {
        val now=Instant.parse("2026-10-18T04:00:00Z")
        val raw="""{"schema_version":1,"operators":[
           {"operator_id":"trugo","operator":"Trugo","tariffs":[
            {"connector_band":"AC","price_try_per_kwh":9.95,
             "price_scope":"operator_published_baseline",
             "verified_at_utc":"2026-10-17T08:00:00Z",
             "source_url":"https://www.trugo.com.tr/price"},
            {"connector_band":"DC","price_try_per_kwh":2,
             "price_scope":"operator_published_baseline",
             "verified_at_utc":"2026-10-17T08:00:00Z",
             "source_url":"https://attacker.invalid/"}
           ]}]}"""
        val list=OfficialChargingTariffs.parse(raw,now)
        assertEquals(1,list.size)
        assertTrue(list[0].fresh)
    }
    @Test fun staleTariffIsNotCurrent() {
        val raw="""{"schema_version":1,"operators":[
        {"operator_id":"zes","operator":"ZES","tariffs":[
          {"connector_band":"DC_1","price_try_per_kwh":12.99,
           "price_scope":"operator_published_baseline",
           "verified_at_utc":"2026-10-01T08:00:00Z",
           "source_url":"https://zes.net/tr/fiyatlandirma"}]}]}"""
        val list=OfficialChargingTariffs.parse(raw,Instant.parse("2026-10-18T04:00:00Z"))
        assertEquals(1,list.size)
        assertFalse(list[0].fresh)
    }
    @Test fun missingPriceReturnsEmpty() {
        assertTrue(OfficialChargingTariffs.parse("""{"schema_version":1,"operators":[]}""").isEmpty())
    }
}
