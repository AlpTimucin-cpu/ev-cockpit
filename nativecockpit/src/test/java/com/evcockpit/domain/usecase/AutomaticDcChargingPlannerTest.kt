package com.evcockpit.domain.usecase

import org.junit.Assert.*
import org.junit.Test
import com.evcockpit.domain.model.*

class AutomaticDcChargingPlannerTest {
    private val route = RouteSnapshot(
        routeId = "verified-osrm-fixture",
        totalKm = 350.0,
        routePoints = listOf(GeoCoordinate(38.0, 35.0), GeoCoordinate(38.0, 39.0))
    )
    private val dc = ChargingStation(
        id = "verified-dc", name = "Fixture DC", operator = "Fixture", address = "",
        coordinate = GeoCoordinate(38.0, 37.0),
        sockets = listOf(ChargingSocket("one", "CCS2", "DC", "unknown")),
        sourceVerifiedAtUtc = "2026-10-29T00:00:00Z"
    )
    private val baseline = EvBaselineEnergyEstimator.estimate(
        BaselineEnergyInputs(
            route = route,
            departureSoc = DepartureSoc.ExplicitAssumption(80.0),
            usableBatteryKwh = 60.0,
            consumptionWhPerKm = 180.0,
            consumptionBasis = ConsumptionBasis.REFERENCE_MODEL,
            requiredArrivalSocPercent = 15.0,
            nowMs = 1_800_000_000_000L
        )
    )

    private fun roadChecked(station: ChargingStation): List<DCCorridorCandidate> =
        AutomaticDcChargingPlanner.candidates(route, listOf(station)).map { item ->
            item.copy(roadDetour = RoadDetour(
                routeId = requireNotNull(route.routeId),
                stationId = item.station.id,
                additionalKm = 4.0,
                roadDistanceToStationKm = item.distanceFromStartKm + 2.0,
                totalViaDistanceKm = requireNotNull(route.totalKm) + 4.0,
                additionalMinutes = 8.0,
                verifiedAtMillis = 1_800_000_000_000L
            ))
        }

    @Test fun uncheckedAirCorridorIsNotAnAutomaticChargingStop() {
        val unchecked = AutomaticDcChargingPlanner.candidates(route, listOf(dc))
        val result = AutomaticDcChargingPlanner.plan(
            route, unchecked, baseline, 60.0, 80.0, 15.0
        )
        assertTrue(result is AutomaticChargePlan.Unavailable)
    }

    @Test fun oneVerifiedDcCandidateEnablesModelStopWithoutAssumingPower() {
        val candidates = roadChecked(dc)
        assertEquals(1, candidates.size)
        val plan = AutomaticDcChargingPlanner.plan(route, candidates, baseline, 60.0, 80.0, 15.0)
        assertTrue(plan is AutomaticChargePlan.Estimate)
        val estimate = plan as AutomaticChargePlan.Estimate
        assertEquals("verified-dc", estimate.stops.single().station.id)
        assertTrue(estimate.stops.single().suggestedEnergyKwh > 0)
        assertTrue(estimate.source.contains("MODEL/ESTIMATE"))
    }

    @Test fun powerRequiresExplicitKilowattUnit() {
        val specified = dc.copy(
            sockets = listOf(ChargingSocket("one", "CCS2", "DC", "150 kW"))
        )
        val verified = AutomaticDcChargingPlanner.plan(
            route, roadChecked(specified),
            baseline, 60.0, 80.0, 15.0
        ) as AutomaticChargePlan.Estimate
        assertEquals(150.0, verified.stops.single().verifiedPowerKw!!, 0.01)

        val unknown = AutomaticDcChargingPlanner.plan(
            route, roadChecked(dc.copy(
                sockets = listOf(ChargingSocket("one", "CCS2", "DC", "150"))
            )),
            baseline, 60.0, 80.0, 15.0
        ) as AutomaticChargePlan.Estimate
        assertNull(unknown.stops.single().verifiedPowerKw)
    }

    @Test fun noReachableDcIsUnavailableNotAFabricatedPlan() {
        val result = AutomaticDcChargingPlanner.plan(route, emptyList(), baseline, 60.0, 80.0, 15.0)
        assertTrue(result is AutomaticChargePlan.Unavailable)
    }

    @Test fun differentChargeTargetsAreExplicitModeledPreferences() {
        val corridor = roadChecked(dc)
        val optimal = AutomaticDcChargingPlanner.plan(
            route, corridor, baseline, 60.0, 80.0, 15.0, 80.0
        ) as AutomaticChargePlan.Estimate
        val fewer = AutomaticDcChargingPlanner.plan(
            route, corridor, baseline, 60.0, 80.0, 15.0, 90.0
        ) as AutomaticChargePlan.Estimate
        val minimum = AutomaticDcChargingPlanner.plan(
            route, corridor, baseline, 60.0, 80.0, 15.0, 100.0
        ) as AutomaticChargePlan.Estimate
        assertEquals(80.0, optimal.assumedChargeTargetPercent, 0.01)
        assertEquals(90.0, fewer.assumedChargeTargetPercent, 0.01)
        assertEquals(100.0, minimum.assumedChargeTargetPercent, 0.01)
        assertTrue(fewer.totalSuggestedChargeKwh > optimal.totalSuggestedChargeKwh)
        assertTrue(minimum.totalSuggestedChargeKwh > fewer.totalSuggestedChargeKwh)
    }

    @Test fun chargeTargetOutsideSafeModelRangeIsRejected() {
        val result = AutomaticDcChargingPlanner.plan(
            route, roadChecked(dc),
            baseline, 60.0, 80.0, 15.0, 120.0
        )
        assertTrue(result is AutomaticChargePlan.Unavailable)
    }

    @Test fun missingCalibrationFailsClosed() {
        val result = AutomaticDcChargingPlanner.plan(route, emptyList(), baseline, Double.NaN, 80.0, 15.0)
        assertTrue(result is AutomaticChargePlan.Unavailable)
    }
}
