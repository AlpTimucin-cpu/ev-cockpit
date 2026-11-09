package com.evcockpit.domain.usecase

import org.junit.Assert.*
import org.junit.Test
import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.RouteSnapshot

class EvBaselineEnergyEstimatorTest {
    private val now = 1_800_000_000_000L
    private val route = RouteSnapshot(
        routeId = "osrm-validated",
        routePoints = listOf(
            GeoCoordinate(37.0, 34.0), GeoCoordinate(37.4, 34.4)
        ),
        totalKm = 100.0
    )
    private fun inputs(
        soc: DepartureSoc = DepartureSoc.ExplicitAssumption(80.0),
        route: RouteSnapshot = this.route
    ) = BaselineEnergyInputs(
        route = route,
        departureSoc = soc,
        usableBatteryKwh = 60.0,
        consumptionWhPerKm = 180.0,
        consumptionBasis = ConsumptionBasis.REFERENCE_MODEL,
        requiredArrivalSocPercent = 12.0,
        nowMs = now
    )

    @Test fun baselineIsLabelledEstimateNotLiveBattery() {
        val result = EvBaselineEnergyEstimator.estimate(inputs())
            as BaselineEnergyResult.Estimate
        assertEquals(18.0, result.driveEnergyKwh, 0.0001)
        assertEquals(50.0, result.arrivalSocPercent, 0.0001)
        assertFalse(result.usedLiveObd)
        assertFalse(result.chargingStopNeeded)
    }

    @Test fun freshObdCanBeUsedAndStaleObdMustBeRejected() {
        val fresh = EvBaselineEnergyEstimator.estimate(
            inputs(DepartureSoc.FreshObd(80.0, now - 1_000))
        ) as BaselineEnergyResult.Estimate
        assertTrue(fresh.usedLiveObd)
        assertTrue(EvBaselineEnergyEstimator.estimate(
            inputs(DepartureSoc.FreshObd(80.0, now - 11_000))
        ) is BaselineEnergyResult.Unavailable)
    }

    @Test fun distantRouteRequiresChargingWithoutInventingStop() {
        val long = route.copy(totalKm = 400.0)
        val result = EvBaselineEnergyEstimator.estimate(
            inputs(route = long)
        ) as BaselineEnergyResult.Estimate
        assertTrue(result.chargingStopNeeded)
        assertEquals(-40.0, result.arrivalSocPercent, 0.0001)
        assertTrue(result.requiredChargingEnergyKwh > 0.0)
    }

    @Test fun speedAboveReferenceRaisesConsumptionAndLeavesBaselineCompatible() {
        val original = EvBaselineEnergyEstimator.estimate(inputs())
            as BaselineEnergyResult.Estimate
        val fast = EvBaselineEnergyEstimator.estimate(
            inputs().copy(speedKmh = 140.0)
        ) as BaselineEnergyResult.Estimate
        assertEquals(1.0, original.speedMultiplier, 0.000001)
        assertTrue(fast.speedMultiplier > 1.0)
        assertTrue(fast.driveEnergyKwh > original.driveEnergyKwh)
        assertTrue(fast.arrivalSocPercent < original.arrivalSocPercent)
        assertTrue(fast.isScenarioProjection)
    }

    @Test fun coldClimateAddsExplicitScenarioCost() {
        val warm = EvBaselineEnergyEstimator.estimate(
            inputs().copy(outsideTemperatureC = 20.0, climateActive = true)
        ) as BaselineEnergyResult.Estimate
        val cold = EvBaselineEnergyEstimator.estimate(
            inputs().copy(outsideTemperatureC = -10.0, climateActive = true)
        ) as BaselineEnergyResult.Estimate
        assertEquals(1.0, warm.climateMultiplier, 0.00001)
        assertTrue(cold.climateMultiplier > 1.0)
        assertTrue(cold.driveEnergyKwh > warm.driveEnergyKwh)
    }

    @Test fun unknownTemperatureDoesNotInventClimatePenalty() {
        val estimate = EvBaselineEnergyEstimator.estimate(
            inputs().copy(climateActive = true)
        ) as BaselineEnergyResult.Estimate
        assertEquals(1.0, estimate.climateMultiplier, 0.00001)
    }

    // QA-01: Low-speed city driving + high ambient temperature.
    @Test fun lowSpeedAndHotClimateExpectedMultipliers() {
        val input = inputs().copy(
            speedKmh = 65.0, outsideTemperatureC = 40.0, climateActive = true
        )
        val result = EvBaselineEnergyEstimator.estimate(input)
            as BaselineEnergyResult.Estimate
        assertEquals(1.0, result.speedMultiplier, 0.000001)
        assertEquals(1.08, result.climateMultiplier, 0.000001)
        assertEquals(19.44, result.driveEnergyKwh, 0.000001)
        assertEquals(47.6, result.arrivalSocPercent, 0.000001)
        assertTrue(result.isScenarioProjection)
    }

    // QA-02: Highway speed, subzero outside temperature and climate on.
    @Test fun highSpeedAndColdClimateLogarithmicMultiplier() {
        val input = inputs().copy(
            speedKmh = 150.0, outsideTemperatureC = -5.0, climateActive = true
        )
        val result = EvBaselineEnergyEstimator.estimate(input)
            as BaselineEnergyResult.Estimate
        val speedExpected = 1.0 + 0.12 * kotlin.math.ln(3.0)
        val climateExpected = 1.24
        assertEquals(speedExpected, result.speedMultiplier, 0.000001)
        assertEquals(climateExpected, result.climateMultiplier, 0.000001)
        assertEquals(
            180.0 * speedExpected * climateExpected,
            result.effectiveWhPerKm, 0.000001
        )
        assertTrue(result.arrivalSocPercent < 50.0)
        assertTrue(result.isScenarioProjection)
    }

    // QA-03: Missing speed/weather must preserve the historical baseline;
    // invalid numbers are rejected instead of being treated as measurements.
    @Test fun neutralDefaultAndInvalidInputsFailSafely() {
        val original = EvBaselineEnergyEstimator.estimate(inputs())
            as BaselineEnergyResult.Estimate
        assertEquals(1.0, original.speedMultiplier, 0.0)
        assertEquals(1.0, original.climateMultiplier, 0.0)
        assertEquals(18.0, original.driveEnergyKwh, 0.000001)
        assertFalse(original.isScenarioProjection)

        assertTrue(EvBaselineEnergyEstimator.estimate(
            inputs().copy(speedKmh = Double.POSITIVE_INFINITY)
        ) is BaselineEnergyResult.Unavailable)
        assertTrue(EvBaselineEnergyEstimator.estimate(
            inputs().copy(outsideTemperatureC = Double.NaN, climateActive = true)
        ) is BaselineEnergyResult.Unavailable)
    }

    @Test fun invalidOrMissingRouteAndBatteryFailClosed() {
        assertTrue(EvBaselineEnergyEstimator.estimate(
            inputs(route = route.copy(routeId = null))
        ) is BaselineEnergyResult.Unavailable)
        assertTrue(EvBaselineEnergyEstimator.estimate(
            inputs().copy(usableBatteryKwh = 0.0)
        ) is BaselineEnergyResult.Unavailable)
        assertTrue(EvBaselineEnergyEstimator.estimate(
            inputs().copy(consumptionWhPerKm = Double.NaN)
        ) is BaselineEnergyResult.Unavailable)
    }
}
