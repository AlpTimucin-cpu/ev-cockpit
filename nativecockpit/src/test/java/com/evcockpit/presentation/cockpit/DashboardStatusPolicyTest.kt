package com.evcockpit.presentation.cockpit

import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.model.ObdPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardStatusPolicyTest {
    private val measuredGps = GpsFix(
        coordinate = GeoCoordinate(38.0, 34.0),
        accuracyMeters = 12f,
        recordedAtMillis = 30_000L
    )

    @Test fun gpsRequiresRecentAccurateFix() {
        assertEquals("GPS güncel", DashboardStatusPolicy.gpsLabel(measuredGps, 35_000L))
        assertEquals("GPS bekleniyor", DashboardStatusPolicy.gpsLabel(measuredGps, 45_001L))
        assertEquals("GPS bekleniyor", DashboardStatusPolicy.gpsLabel(null, 35_000L))
    }

    @Test fun gpsRejectsInaccurateMeasurement() {
        val badAccuracy = measuredGps.copy(accuracyMeters = 120f)
        assertEquals("GPS bekleniyor", DashboardStatusPolicy.gpsLabel(badAccuracy, 35_000L))
    }

    @Test fun obdConnectingAndErrorAreNotReportedAsConnected() {
        assertEquals("OBD bağlanıyor", DashboardStatusPolicy.obdLabel(ObdPhase.CONNECTING))
        assertEquals("OBD hatası", DashboardStatusPolicy.obdLabel(ObdPhase.ERROR))
        assertEquals("OBD bağlı değil", DashboardStatusPolicy.obdLabel(ObdPhase.DISCONNECTED))
        assertEquals("OBD bağlı", DashboardStatusPolicy.obdLabel(ObdPhase.CONNECTED))
    }

    @Test fun weatherFreshnessAndAvailabilityAreExplicit() {
        assertEquals("Hava yükleniyor", DashboardStatusPolicy.weatherLabel(true, false, null))
        assertEquals("Hava verisi yok", DashboardStatusPolicy.weatherLabel(false, false, 2L))
        assertEquals("Hava verisi yok", DashboardStatusPolicy.weatherLabel(false, true, null))
        assertEquals("Hava · 4 dk", DashboardStatusPolicy.weatherLabel(false, true, 4L))
        assertEquals("Hava eski · 31 dk", DashboardStatusPolicy.weatherLabel(false, true, 31L))
    }
}
