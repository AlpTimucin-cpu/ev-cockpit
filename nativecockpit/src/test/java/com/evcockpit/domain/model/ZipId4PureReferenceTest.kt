package com.evcockpit.domain.model

import org.junit.Assert.*
import org.junit.Test

class ZipId4PureReferenceTest {
    @Test fun zipNumbersRemainAttributedToExactUploadedRevision() {
        assertEquals(
            "9f66d0b1302789b7377ae82b94545daad198a02003c69625f7cf5ab4e413e62a",
            ZipId4PureReference.SOURCE_SHA256
        )
        assertEquals("VW ID.4 Pure", ZipId4PureReference.VEHICLE_LABEL)
        assertEquals(125, ZipId4PureReference.DRIVE_POWER_KW)
        assertEquals(170, ZipId4PureReference.DRIVE_POWER_PS)
        assertEquals(52.0, ZipId4PureReference.CANDIDATE_NET_BATTERY_KWH, 0.0001)
        assertEquals(145.0, ZipId4PureReference.CANDIDATE_PEAK_DC_KW, 0.0001)
        assertEquals(11.0, ZipId4PureReference.CANDIDATE_AC_KW, 0.0001)
        assertEquals(354, ZipId4PureReference.CLAIMED_WLTP_MIN_KM)
        assertEquals(363, ZipId4PureReference.CLAIMED_WLTP_MAX_KM)
    }

    @Test fun importedProfileIsNeverSilentlyConfirmedAndDoesNotInventConsumption() {
        val incoming = ZipId4PureReference.stagedDraft(VehicleProfileConfig())
        assertEquals(52.0, incoming.usableBatteryKwh!!, 0.0001)
        assertEquals(145.0, incoming.peakDcKw!!, 0.0001)
        assertFalse(incoming.userConfirmed)
        assertNull(incoming.consumptionKwhPer100Km)
        assertNull(incoming.arrivalReservePercent)
        assertFalse(incoming.readyForEstimate())
    }

    @Test fun importerPreservesOwnersConsumptionAndReserveButRevokesConfirmation() {
        val original = VehicleProfileConfig(
            usableBatteryKwh = 50.0,
            peakDcKw = 110.0,
            consumptionKwhPer100Km = 18.0,
            arrivalReservePercent = 15.0,
            userConfirmed = true
        )
        val draft = ZipId4PureReference.stagedDraft(original)
        assertEquals(52.0, draft.usableBatteryKwh!!, 0.0001)
        assertEquals(145.0, draft.peakDcKw!!, 0.0001)
        assertEquals(18.0, draft.consumptionKwhPer100Km!!, 0.0001)
        assertEquals(15.0, draft.arrivalReservePercent!!, 0.0001)
        assertFalse(draft.userConfirmed)
        assertTrue(original.userConfirmed) // immutable input unchanged
    }
}
