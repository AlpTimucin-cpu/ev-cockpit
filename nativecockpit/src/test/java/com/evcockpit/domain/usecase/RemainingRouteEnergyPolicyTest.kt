package com.evcockpit.domain.usecase

import com.evcockpit.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class RemainingRouteEnergyPolicyTest {
    private val now = 1_800_000_000_000L
    private val route = RouteSnapshot(
        routeId = "actual-osrm-1",
        routePoints = listOf(GeoCoordinate(38.0, 34.0), GeoCoordinate(39.0, 34.0)),
        totalKm = 200.0,
        remainingKm = 100.0,
        gpsProgressFraction = .5f
    )
    private val profile = VehicleProfileConfig(
        usableBatteryKwh = 50.0,
        consumptionKwhPer100Km = 15.0,
        arrivalReservePercent = 10.0,
        userConfirmed = true
    )
    private fun bms(soc: Double = 80.0) = VehicleTelemetry(
        socPercent = TimedValue(soc, now - 500L),
        socEvidence = CoreEvidence.Available(
            soc, now - 500L, EvidenceSource.VERIFIED_OBD, EvidenceClass.MEASUREMENT
        )
    )

    @Test fun projectsRemaining100KmNotOriginal200Km() {
        val result = RemainingRouteEnergyPolicy.estimate(route, bms(), profile, now)
            as BaselineEnergyResult.Estimate
        assertEquals(100.0, result.routeKm, 0.00001)
        assertEquals(15.0, result.driveEnergyKwh, 0.00001)
        assertEquals(50.0, result.arrivalSocPercent, 0.00001)
        assertTrue(result.usedLiveObd)
        assertEquals(ConsumptionBasis.REFERENCE_MODEL, result.consumptionBasis)
    }

    @Test fun missingGpsBmsAndUserConfirmationFailClosed() {
        assertTrue(RemainingRouteEnergyPolicy.estimate(
            route.copy(gpsProgressFraction = null), bms(), profile, now
        ) is BaselineEnergyResult.Unavailable)
        assertTrue(RemainingRouteEnergyPolicy.estimate(
            route, VehicleTelemetry(socPercent = TimedValue(80.0, now)), profile, now
        ) is BaselineEnergyResult.Unavailable)
        assertTrue(RemainingRouteEnergyPolicy.estimate(
            route, bms(), profile.copy(userConfirmed = false), now
        ) is BaselineEnergyResult.Unavailable)
    }

    @Test fun inconsistentDistanceAndOutdatedSocAreNotDisplayed() {
        assertTrue(RemainingRouteEnergyPolicy.estimate(
            route.copy(remainingKm = 190.0), bms(), profile, now
        ) is BaselineEnergyResult.Unavailable)
        assertTrue(RemainingRouteEnergyPolicy.estimate(
            route, bms(), profile, now + 20_000L
        ) is BaselineEnergyResult.Unavailable)
    }

    @Test fun reserveOverrideMustRemainAnExplicitValidPercent() {
        assertTrue(RemainingRouteEnergyPolicy.estimate(
            route, bms(), profile, now, 75.0
        ) is BaselineEnergyResult.Unavailable)
        val result = RemainingRouteEnergyPolicy.estimate(
            route, bms(), profile, now, 20.0
        ) as BaselineEnergyResult.Estimate
        assertEquals(50.0, result.arrivalSocPercent, 0.0001)
        assertFalse(result.chargingStopNeeded)
    }
}
