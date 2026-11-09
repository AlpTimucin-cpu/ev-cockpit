package com.evcockpit.presentation.media

import org.junit.Assert.*
import org.junit.Test

class PhoneVideoSafetyPolicyTest {
    @Test fun cannotOpenPhoneVideoWhileCarProjectedOrInMotion() {
        assertFalse(PhoneVideoSafetyPolicy.allow(false, true, true, 0.0))
        assertFalse(PhoneVideoSafetyPolicy.allow(true, true, true, 20.0))
        assertFalse(PhoneVideoSafetyPolicy.allow(true, true, true, 1.0))
    }

    @Test fun noObdOrNoFreshSpeedIsUnknownNotParked() {
        assertFalse(PhoneVideoSafetyPolicy.allow(true, true, false, 0.0))
        assertFalse(PhoneVideoSafetyPolicy.allow(true, true, true, null))
        assertFalse(PhoneVideoSafetyPolicy.allow(true, true, true, Double.NaN))
        assertFalse(PhoneVideoSafetyPolicy.allow(true, true, true, -1.0))
    }

    @Test fun verifiedStationarySpeedAloneIsInsufficientWithoutAcknowledgement() {
        assertFalse(PhoneVideoSafetyPolicy.allow(true, false, true, 0.0))
        assertTrue(PhoneVideoSafetyPolicy.allow(true, true, true, 0.0))
    }
}
