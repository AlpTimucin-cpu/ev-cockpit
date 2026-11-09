package com.evcockpit.domain.model

import org.junit.Assert.*
import org.junit.Test

class EngineeringMathTest {
    @Test fun realObdSampleComputesSignedPowerAndCellSpread() {
        val t = 1_000_000L
        var s = EngineeringSnapshot()
        s = EngineeringMath.update(s, "22028C", "62028C4C", t)
        s = EngineeringMath.update(s, "221E3B", "621E3B05D6", t + 1000)
        s = EngineeringMath.update(s, "221E3D", "621E3D0002E263", t + 1500)
        s = EngineeringMath.update(s, "221E33", "621E3338450001", t + 2000)
        s = EngineeringMath.update(s, "221E34", "621E3438250002", t + 2500)
        val m = EngineeringMath.calculate(s, t + 3000)
        assertEquals(30.4, s.socPercent.value!!, 0.001)
        assertEquals(373.5, s.packVoltageV.value!!, 0.001)
        assertEquals(390.27, s.packCurrentA.value!!, 0.001)
        assertEquals(145.765845, m.batteryPowerKw!!, 0.001)
        assertEquals(7.8125, m.batteryCellSpreadMv!!, 0.001)
    }

    @Test fun capacityAndAccumulatedEnergyDoNotBecomeSoH() {
        val t = 2_000_000L
        var s = EngineeringMath.update(
            EngineeringSnapshot(), "222AB2", "622AB2041C0400", t
        )
        s = EngineeringMath.update(
            s, "221E32", "621E3200811662FF7F230602F3A698FD3759E9", t + 100
        )
        assertEquals(52.5988, s.capacityParameterKwh.value!!, 0.001)
        assertEquals(5769.77, s.accumulatedChargedKwh.value!!, 0.01)
        assertEquals(5441.43, s.accumulatedDischargedKwh.value!!, 0.01)
        assertEquals(104.64, EngineeringMath.calculate(s, t + 300).dischargeEquivalentFullCycles!!, 0.02)
    }

    @Test fun rejectsStaleAndMismatchedSensorTimes() {
        val t = 3_000_000L
        val s = EngineeringSnapshot(
            packVoltageV = TimedValue(380.0, t),
            packCurrentA = TimedValue(200.0, t + 20_000),
            cellMaxV = TimedValue(3.2, t),
            cellMinV = TimedValue(3.8, t)
        )
        assertNull(EngineeringMath.calculate(s, t + 21_000).batteryPowerKw)
        assertNull(EngineeringMath.calculate(s, t + 21_000).batteryCellSpreadMv)
        assertNull(EngineeringMath.calculate(s, t + 200_000).batteryPowerKw)
    }

    @Test fun invalidDidAndBadRangesNeverProduceLiveMeasurements() {
        val t = 4_000_000L
        val base = EngineeringSnapshot()
        assertNull(EngineeringMath.update(base, "221E3B", "621E3B0001", t).packVoltageV.value)
        assertNull(EngineeringMath.update(base, "221E3B", "622AB2041C0400", t).packVoltageV.value)
        assertNull(EngineeringMath.update(base, "221E3D", "621E3D", t).packCurrentA.value)
        val reversed = EngineeringSnapshot(
            temperatureMaxC = TimedValue(20.0, t),
            temperatureMinC = TimedValue(40.0, t)
        )
        assertNull(EngineeringMath.calculate(reversed, t).batteryTemperatureSpreadC)
    }
}
