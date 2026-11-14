package com.evcockpit.presentation.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PassengerMediaSafetyPolicyTest {
    private val now = 100_000L
    private val parkedObd = PassengerMediaSafetyPolicy.Observation(0.0, now, null, true)
    private val parkedGps = PassengerMediaSafetyPolicy.Observation(0.0, now, 8f, false)

    private fun check(
        androidAuto: Boolean = false,
        consent: Boolean = true,
        foreground: Boolean = true,
        obd: PassengerMediaSafetyPolicy.Observation? = parkedObd,
        gps: PassengerMediaSafetyPolicy.Observation? = parkedGps
    ) = PassengerMediaSafetyPolicy.evaluate(androidAuto, consent, foreground, now, obd, gps)

    @Test fun androidAutoNeverAllowsVideo() =
        assertEquals(PassengerMediaSafetyPolicy.Reason.ANDROID_AUTO, check(androidAuto = true))

    @Test fun consentIsRequired() =
        assertEquals(PassengerMediaSafetyPolicy.Reason.NO_PASSENGER_CONFIRMATION, check(consent = false))

    @Test fun backgroundDisallowsVideo() =
        assertEquals(PassengerMediaSafetyPolicy.Reason.APP_NOT_FOREGROUND, check(foreground = false))

    @Test fun noSensorEvidenceFailsClosed() =
        assertEquals(PassengerMediaSafetyPolicy.Reason.NO_RELIABLE_SPEED, check(obd = null, gps = null))

    @Test fun unverifiedObdIsNotEvidence() =
        assertEquals(PassengerMediaSafetyPolicy.Reason.NO_RELIABLE_SPEED,
            check(obd = parkedObd.copy(fromVerifiedObd = false), gps = null))

    @Test fun staleSensorsFailClosed() =
        assertEquals(PassengerMediaSafetyPolicy.Reason.NO_RELIABLE_SPEED,
            check(obd = parkedObd.copy(measuredAtMs = now - 5_000), gps = null))

    @Test fun inaccurateGpsFailsClosed() =
        assertEquals(PassengerMediaSafetyPolicy.Reason.NO_RELIABLE_SPEED,
            check(obd = null, gps = parkedGps.copy(accuracyMeters = 60f)))

    @Test fun gpsFallbackWorksWhenFreshAndAccurate() =
        assertEquals(PassengerMediaSafetyPolicy.Reason.ALLOWED, check(obd = null))

    @Test fun movingGpsOverridesStationaryObd() =
        assertEquals(PassengerMediaSafetyPolicy.Reason.VEHICLE_MOVING,
            check(gps = parkedGps.copy(speedKmh = 12.0)))

    @Test fun movingObdOverridesStationaryGps() =
        assertEquals(PassengerMediaSafetyPolicy.Reason.VEHICLE_MOVING,
            check(obd = parkedObd.copy(speedKmh = 4.0)))

    @Test fun verifiedStationaryObdCanAllowWithoutGps() =
        assertEquals(PassengerMediaSafetyPolicy.Reason.ALLOWED, check(gps = null))
}
