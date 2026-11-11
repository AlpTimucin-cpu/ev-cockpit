package com.evcockpit.diagnostics

import com.evcockpit.data.obd.MebUdsParser
import com.evcockpit.data.obd.ObdWireCommandPolicy
import com.evcockpit.domain.model.ChargingCatalog
import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.model.MebDiagnosticReport
import com.evcockpit.domain.model.MebResponseStatus
import com.evcockpit.domain.model.ObdConnectionState
import com.evcockpit.domain.model.ObdPhase
import com.evcockpit.car.CarWeatherRefreshGate
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsHubTest {
    private val now = 1_800_000_000_000L

    @Test fun ringKeepsOnlyLastFiveHundredAndRemainsVolatile() {
        val hub = DiagnosticsHub()
        repeat(512) { hub.event(DiagnosticEvent.OPENED, now + it) }
        assertEquals(500, hub.snapshot.value.entries.size)
        assertFalse(hub.snapshot.value.entries.first().contains(".000Z"))
        assertTrue(hub.snapshot.value.entries.all { it.contains("Tanılama açıldı") })
        assertEquals(0, DiagnosticsHub().snapshot.value.entries.size)
    }

    @Test fun exportOnlyContainsFixedSafeLabelsNoCoordinatesOrSecrets() {
        val hub = DiagnosticsHub()
        hub.event(DiagnosticEvent.GPS_RECEIVED, now)
        val text = hub.exportText()
        assertTrue(text.contains("Koordinatlar"))
        assertFalse(text.contains("38.812345"))
        assertFalse(text.contains("Authorization:"))
        assertFalse(text.contains("Bearer "))
        assertFalse(text.contains("https://"))
        assertEquals(1, hub.snapshot.value.entries.size)
    }

    @Test fun realGpsTimeAndAccuracyVisibleButCoordinatesNeverLogged() {
        val hub = DiagnosticsHub()
        val fix = GpsFix(GeoCoordinate(38.812345, 34.63579), 8f, now - 250)
        hub.gps(fix, now)
        assertEquals(now - 250, hub.snapshot.value.gpsAtMillis)
        assertEquals(8f, hub.snapshot.value.gpsAccuracyMeters!!, .001f)
        assertFalse(hub.exportText().contains("38.812345"))
        hub.gps(GpsFix(GeoCoordinate(38.99, 34.35), 5f, now - 20_000L), now)
        assertEquals(now - 250, hub.snapshot.value.gpsAtMillis)
    }

    @Test fun mapGeometryAndHostLifecycleNeverStoreRouteOrDeviceId() {
        val hub = DiagnosticsHub()
        hub.carScreen(true)
        hub.surface(1920, 1080, 160, 16, 24, 10, 12)
        hub.carReattached()
        assertTrue(hub.snapshot.value.carScreenActive == true)
        assertEquals(1920, hub.snapshot.value.mapWidthPx)
        assertEquals("16,24,10,12", hub.snapshot.value.mapInsetsPx)
        assertEquals(1, hub.snapshot.value.carReattachments)
        hub.surfaceGone()
        assertNull(hub.snapshot.value.mapWidthPx)
        hub.carScreen(false)
        assertTrue(hub.snapshot.value.carScreenActive == false)
    }

    @Test fun serviceWeatherAndEpdkOutcomesAreEvidenceBased() {
        val hub = DiagnosticsHub()
        hub.foregroundService(location = true, active = true)
        assertTrue(hub.snapshot.value.locationFgsActive)
        hub.foregroundService(location = false, active = true)
        assertTrue(hub.snapshot.value.obdFgsActive)
        hub.weatherAttempt(now)
        hub.weatherFinished(success = false, atMillis = now + 1000)
        assertEquals("Başarısız", hub.snapshot.value.weatherOutcome)
        assertNull(hub.snapshot.value.weatherReceivedMillis)
        hub.weatherFinished(success = true, atMillis = now + 2000)
        assertEquals(now + 2000, hub.snapshot.value.weatherReceivedMillis)
        hub.charging(ChargingCatalog(
            loading = false, verifiedAtUtc = "2026-11-08T20:30:00Z"
        ))
        assertEquals("2026-11-08T20:30:00Z",
            hub.snapshot.value.chargeSnapshotUtc)
        hub.charging(ChargingCatalog(
            loading = false, verifiedAtUtc = "raw token: abc"
        ))
        assertNull(hub.snapshot.value.chargeSnapshotUtc)
        hub.foregroundService(location = true, active = false)
        assertFalse(hub.snapshot.value.locationFgsActive)
    }

    @Test fun weatherDiagnosticEvidenceMasksAllExceptUtcAndBoundedCount() {
        val hub = DiagnosticsHub()
        hub.weatherEvidence("2026-11-11T06:00:00Z", 8)
        assertEquals("2026-11-11T06:00:00Z", hub.snapshot.value.weatherCurrentModelUtc)
        assertEquals(8, hub.snapshot.value.weatherRoutePointCount)
        hub.weatherEvidence("Bearer secret 38.123456", 9)
        assertEquals("2026-11-11T06:00:00Z", hub.snapshot.value.weatherCurrentModelUtc)
        assertEquals(8, hub.snapshot.value.weatherRoutePointCount)
        hub.weatherEvidence("https://untrusted.example/coords", 2)
        assertNull(hub.snapshot.value.weatherCurrentModelUtc)
        assertEquals(2, hub.snapshot.value.weatherRoutePointCount)
        assertFalse(hub.exportText().contains("38.123456"))
    }

    @Test fun rawObdIsAllowedReadOnlyHeaderAndTruncatedNeverFullPayload() {
        val hub = DiagnosticsHub()
        val report = MebDiagnosticReport(
            request = "22028C", receivedAtMillis = now,
            status = MebResponseStatus.VALID_RAW,
            payloadHex = "62028C1234567890ABCDEF112233445566778899"
        )
        hub.obd(ObdConnectionState(
            phase = ObdPhase.CONNECTED,
            diagnosticResult = report
        ))
        val safe = hub.snapshot.value.obdRawMasked
        assertTrue(safe.contains("22028C"))
        assertTrue(safe.contains("[maskeli]"))
        assertFalse(safe.contains("ABCDEF112233445566778899"))
        assertFalse(hub.exportText().contains("62028C1234"))
        assertEquals("Kullanılamıyor", hub.maskReadOnlyObd(report.copy(
            request = "2EF190"
        )))
        assertEquals("Kullanılamıyor", hub.maskReadOnlyObd(report.copy(
            payloadHex = "Bearer secret access_token"
        )))
    }

    @Test fun mode01RawReadIsRecordedOnlyAsSafeThreeByteResponse() {
        val hub = DiagnosticsHub()
        hub.obdSpeedResponse("SEARCHING...\\r410D4A\\r>")
        assertEquals("010D / 410D4A", hub.snapshot.value.obdRawMasked)
        hub.obd(ObdConnectionState(phase = ObdPhase.CONNECTED))
        assertEquals("010D / 410D4A", hub.snapshot.value.obdRawMasked)
        hub.obdSpeedResponse("Authorization: Bearer secret")
        assertEquals("010D / 410D4A", hub.snapshot.value.obdRawMasked)
        assertFalse(hub.exportText().contains("Bearer"))
        hub.obd(ObdConnectionState(phase = ObdPhase.DISCONNECTED))
        assertEquals("Kullanılamıyor", hub.snapshot.value.obdRawMasked)
    }

    @Test fun allEcuWritesAndUnapprovedRequestsStillRejectedAtWireBoundary() {
        listOf("2EF190", "31010000", "1101", "2701", "14FFFFFF",
            "04", "0902", "22F190", "3E00", "ATSHDEADBEEF").forEach {
            assertFalse("Unexpectedly allowed: " + it, ObdWireCommandPolicy.allowed(it))
        }
        assertTrue(ObdWireCommandPolicy.allowed("010D"))
        assertTrue(ObdWireCommandPolicy.allowed("22028C"))
        assertFalse(MebUdsParser.ALLOWED_REQUESTS.contains("22F190"))
    }

    @Test fun diagnosticWeatherIntervalMatchesActualCarRefreshPolicy() {
        assertEquals(1_800_000L, CarWeatherRefreshGate.AUTOMATIC_REFRESH_MS)
        val gate = CarWeatherRefreshGate()
        assertTrue(gate.shouldRefresh("real-route", true, now))
        gate.markAttempt("real-route", now)
        assertFalse(gate.shouldRefresh("real-route", true, now + 29 * 60_000L))
        assertTrue(gate.shouldRefresh("real-route", true, now + 30 * 60_000L))
        assertFalse(gate.shouldRefresh("real-route", false, now + 31 * 60_000L))
    }
}
