package com.evcockpit.domain.model

import org.junit.Assert.*
import org.junit.Test

class UserDrivingScenariosTest {
    @Test fun summerAndWinterScenariosMatchOwnerReports() {
        assertEquals(10.0, UserDrivingScenarios.summer.minKwhPer100Km, 0.001)
        assertEquals(11.0, UserDrivingScenarios.summer.maxKwhPer100Km, 0.001)
        assertEquals(10.5, UserDrivingScenarios.summer.midpointKwhPer100Km, 0.001)
        assertEquals(17.0, UserDrivingScenarios.winter.minKwhPer100Km, 0.001)
        assertEquals(19.0, UserDrivingScenarios.winter.maxKwhPer100Km, 0.001)
        assertEquals(18.0, UserDrivingScenarios.winter.midpointKwhPer100Km, 0.001)
    }

    @Test fun scenarioNumbersAreNotSavedCarBmsEvidence() {
        assertFalse(VehicleProfileConfig(
            usableBatteryKwh = ZipId4PureReference.CANDIDATE_NET_BATTERY_KWH,
            consumptionKwhPer100Km = UserDrivingScenarios.summer.midpointKwhPer100Km,
            arrivalReservePercent = 10.0,
            userConfirmed = false
        ).readyForEstimate())
    }
}
