package com.evcockpit.data.remote

import org.junit.Assert.*
import org.junit.Test

/** Small invalid samples only. No sample is shipped or rendered as real stations. */
class EpdkSnapshotParserTest {
    @Test fun rejectAbsentRealEpdkStations() {
        val doc="""{"schema_version":1,"prices_included":false,
            "live_status_included":false,"verified_at_utc":"2026-10-17T21:58:05Z",
            "stations":[]}"""
        assertThrows(IllegalArgumentException::class.java) { EpdkSnapshotParser.parseJson(doc) }
    }
    @Test fun validatesActualExportCountsAndRejectsSingleBadStation() {
        val now = java.time.Instant.now().toString()
        val stations = (1..5_000).joinToString(",") { i ->
            """{"official_station_id":"EPDK-$i","name":"Deneme",
              "operator":"Kaynak operatörü","address":"Test",
              "source":"EPDK","public":true,"service_kind":"HALKA_ACIK",
              "lat":38.0,"lon":34.0,"sockets":[],"socket_count":0}"""
        }
        fun document(items: String, count: Int = 5_000) =
            """{"schema_version":1,"source_url":"https://apigateway.epdk.gov.tr/sarjIstasyonlari",
              "verified_at_utc":"$now","country":"TR","complete":true,
              "all_rows_count":6000,"public_rows_count":5000,
              "geocoded_public_rows_count":$count,
              "prices_included":false,"live_status_included":false,
              "stations":[$items]}"""
        val parsed = EpdkSnapshotParser.parseJson(document(stations))
        assertEquals(5_000, parsed.stations.size)
        assertEquals(6_000, parsed.allRowCount)
        assertEquals(5_000, parsed.geocodedRowCount)
        assertEquals(now, parsed.verifiedAtUtc)
        assertThrows(IllegalArgumentException::class.java) {
            EpdkSnapshotParser.parseJson(document(stations, 4_999))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EpdkSnapshotParser.parseJson(document(
                stations.replaceFirst("\"lat\":38.0", "\"lat\":0.0")))
        }
    }

    @Test fun rejectInventedAvailabilitySchema() {
        val doc="""{"schema_version":1,"prices_included":false,
            "live_status_included":true,"verified_at_utc":"2026-10-17T21:58:05Z",
            "stations":[]}"""
        assertThrows(IllegalArgumentException::class.java) { EpdkSnapshotParser.parseJson(doc) }
    }
}
