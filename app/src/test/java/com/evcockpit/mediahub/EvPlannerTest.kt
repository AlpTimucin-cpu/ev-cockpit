package com.evcockpit.mediahub

import org.junit.Assert.*
import org.junit.Test

class EvPlannerTest {
    @Test fun pureModelChargeFromTwentyToEighty() {
        val p = EvPlanner.Profile(usableKwh=52.0, socPercent=20.0,
            targetPercent=80.0, averageChargingKw=11.0,
            gridEfficiency=0.9, priceTlPerKwh=10.0)
        val c=EvPlanner.charge(p)
        assertEquals(31.2, c.batteryKwh, 0.00001)
        assertEquals(34.6666667, c.gridKwh, 0.00001)
        assertEquals(346.666667, c.costTl, 0.0001)
        assertEquals(31.2 / 11.0, c.idealHoursAtChosenAverageKw, 0.0001)
    }
    @Test fun tripArrivesWithReserve() {
        val p=EvPlanner.Profile(usableKwh=52.0, socPercent=80.0,
            reservePercent=10.0, consumptionKwhPer100Km=20.0,
            distanceKm=100.0)
        val t=EvPlanner.trip(p)
        assertEquals(41.5384615, t.arrivalPercentWithoutCharge, 0.0001)
        assertEquals(0.0, t.batteryKwhRequiredToArriveWithReserve, 0.00001)
        assertTrue(t.canArriveWithReserve)
    }
    @Test fun tripDetectsMissingCharge() {
        val p=EvPlanner.Profile(usableKwh=52.0,socPercent=20.0,
            reservePercent=10.0,consumptionKwhPer100Km=20.0,distanceKm=100.0)
        val t=EvPlanner.trip(p)
        assertEquals(14.8, t.batteryKwhRequiredToArriveWithReserve, 0.00001)
        assertFalse(t.canArriveWithReserve)
    }
    @Test fun invalidNumbersAreRejected() {
        assertFalse(EvPlanner.valid(EvPlanner.Profile(usableKwh=0.0)))
        assertFalse(EvPlanner.valid(EvPlanner.Profile(socPercent=101.0)))
        assertFalse(EvPlanner.valid(EvPlanner.Profile(priceTlPerKwh=Double.NaN)))
    }
    @Test fun ratioNeedsMeasurement() {
        assertNull(EvPlanner.indicativeCapacityRatio(0.0,52.0))
        assertEquals(95.0,EvPlanner.indicativeCapacityRatio(49.4,52.0)!!,0.0001)
    }
}
