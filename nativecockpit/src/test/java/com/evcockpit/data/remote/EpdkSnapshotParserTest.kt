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
    @Test fun rejectInventedAvailabilitySchema() {
        val doc="""{"schema_version":1,"prices_included":false,
            "live_status_included":true,"verified_at_utc":"2026-10-17T21:58:05Z",
            "stations":[]}"""
        assertThrows(IllegalArgumentException::class.java) { EpdkSnapshotParser.parseJson(doc) }
    }
}
