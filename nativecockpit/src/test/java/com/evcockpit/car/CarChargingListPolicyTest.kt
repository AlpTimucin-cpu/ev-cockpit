package com.evcockpit.car

import com.evcockpit.domain.model.*
import com.evcockpit.domain.usecase.AutomaticDcChargingPlanner
import com.evcockpit.domain.usecase.DCCorridorCandidate
import org.junit.Assert.*
import org.junit.Test

class CarChargingListPolicyTest {
    private val now = 1_800_000_000_000L
    private val a = GeoCoordinate(38.0, 34.0)
    private val b = GeoCoordinate(38.02, 34.0)
    private val route = RouteSnapshot(
        routeId = "verified-osrm-route",
        routePoints = listOf(a, b),
        totalKm = 2.3,
        osrmDurationSeconds = 260.0,
        destination = "Hedef"
    )
    private val s1 = ChargingStation(
        "epdk-1", "Test Station 1", "Verified operator", "Address", a,
        listOf(ChargingSocket("c1", "CCS2", "DC", "150 kW")),
        "2026-11-08T00:00:00Z"
    )
    private val s2 = ChargingStation(
        "epdk-2", "Test Station 2", "Verified operator", "Address",
        GeoCoordinate(38.01, 34.0),
        listOf(ChargingSocket("c2", "CCS2", "DC", "150")),
        "2026-11-08T00:00:00Z"
    )
    private val catalog = ChargingCatalog(
        stations = listOf(s1, s2),
        verifiedAtUtc = "2026-11-08T00:00:00Z",
        loading = false
    )
    private val candidates = listOf(
        DCCorridorCandidate(s1, 1.0),
        DCCorridorCandidate(s2, 1.4)
    )

    @Test fun explicitPowerUnitsOnly() {
        assertEquals(150.0, CarChargingListPolicy.explicitPowerKw("150 kW")!!, .001)
        assertEquals(22.5, CarChargingListPolicy.explicitPowerKw("22,5 KW")!!, .001)
        assertNull(CarChargingListPolicy.explicitPowerKw("150"))
        assertNull(CarChargingListPolicy.explicitPowerKw("150 W"))
        assertNull(CarChargingListPolicy.explicitPowerKw("999 kW"))
        assertNull(CarChargingListPolicy.explicitPowerKw("150kWh"))
    }

    @Test fun selectionIsBoundedByHostAndNeverInventsUnknownPowerOrPrice() {
        val result = CarChargingListPolicy.build(route, catalog, candidates, null,
            VehicleTelemetry(), VehicleProfileConfig(), null, now, 1)
        assertEquals(1, result.items.size)
        assertEquals(150.0, result.items[0].verifiedCcsPowerKw!!, .01)
        assertNull(result.items[0].modelArrivalSocPercent)
        assertTrue(CarChargingListPolicy.detail(result.items[0]).contains("kullanılamıyor"))
        val second = CarChargingListPolicy.build(route, catalog, candidates, null,
            VehicleTelemetry(), VehicleProfileConfig(), null, now, 2)
        assertNull(second.items[1].verifiedCcsPowerKw)
    }

    @Test fun withoutCatalogRouteOrHostSlotsShowsExplicitUnavailability() {
        val empty = CarChargingListPolicy.build(route, ChargingCatalog(), emptyList(), null,
            VehicleTelemetry(), VehicleProfileConfig(), null, now, 6)
        assertTrue(empty.items.isEmpty())
        assertTrue(empty.message!!.contains("yükleniyor"))
        val noRoute = CarChargingListPolicy.build(null, catalog, candidates, null,
            VehicleTelemetry(), VehicleProfileConfig(), null, now, 6)
        assertTrue(noRoute.message!!.contains("OSRM"))
        val zero = CarChargingListPolicy.build(route, catalog, candidates, null,
            VehicleTelemetry(), VehicleProfileConfig(), null, now, 0)
        assertTrue(zero.items.isEmpty())
    }

    @Test fun staleCatalogIsClearlyMarkedNotLiveAvailability() {
        val stale = catalog.copy(staleCache = true)
        val model = CarChargingListPolicy.build(route, stale, candidates, null,
            VehicleTelemetry(), VehicleProfileConfig(), null, now, 4)
        assertTrue(model.catalogLabel.contains("önbelleği"))
        assertEquals(2, model.items.size)
    }

    @Test fun actualGpsOffersDirectDistanceButNotRoadKilometres() {
        val fix = GpsFix(a, 6f, now - 1000)
        val model = CarChargingListPolicy.build(route, catalog, candidates, fix,
            VehicleTelemetry(), VehicleProfileConfig(), null, now, 4)
        assertNotNull(model.items[0].directDistanceMeters)
        assertEquals(0.0, model.items[0].directDistanceMeters!!, 1.0)
        assertNull(model.items[0].modelArrivalSocPercent)
    }

    @Test fun noVerifiedBmsOrNoValidatedRoadDetourMeansNoEstimatedArrivalSoc() {
        val fix = GpsFix(a, 5f, now - 500)
        val userConfirmed = VehicleProfileConfig(
            usableBatteryKwh = 52.0,
            consumptionKwhPer100Km = 18.0,
            arrivalReservePercent = 20.0,
            userConfirmed = true
        )
        val detour = RoadDetour(
            routeId = route.routeId!!,
            stationId = s1.id,
            additionalKm = 0.5,
            roadDistanceToStationKm = 1.5,
            totalViaDistanceKm = 3.0,
            additionalMinutes = 3.0,
            verifiedAtMillis = now - 1000
        )
        val roads = RoadDetourCatalog(routeId = route.routeId,
            checked = listOf(detour))
        val result = CarChargingListPolicy.build(route, catalog, candidates, fix,
            VehicleTelemetry(socPercent = TimedValue(80.0, now - 500)),
            userConfirmed, roads, now, 2)
        assertNull(result.items[0].modelArrivalSocPercent)
        val noRoad = CarChargingListPolicy.build(route, catalog, candidates, fix,
            verifiedBms(), userConfirmed, null, now, 2)
        assertNull(noRoad.items[0].modelArrivalSocPercent)
    }

    @Test fun verifiedBmsAndCurrentPrivateRoadEvidenceAllowOnlyLabeledModel() {
        val config = VehicleProfileConfig(
            usableBatteryKwh = 52.0,
            consumptionKwhPer100Km = 18.0,
            arrivalReservePercent = 20.0,
            userConfirmed = true
        )
        val fix = GpsFix(a, 4f, now - 200)
        val road = RoadDetour(
            routeId = route.routeId!!, stationId = s1.id,
            additionalKm = 0.4, roadDistanceToStationKm = 1.5,
            totalViaDistanceKm = 3.1, additionalMinutes = 2.0,
            verifiedAtMillis = now - 60_000
        )
        val result = CarChargingListPolicy.build(route, catalog, candidates, fix,
            verifiedBms(), config,
            RoadDetourCatalog(routeId = route.routeId, checked = listOf(road)),
            now, 2)
        assertNotNull(result.items[0].modelArrivalSocPercent)
        assertTrue(result.items[0].modelArrivalSocPercent!! < 80.0)
        assertTrue(CarChargingListPolicy.detail(result.items[0]).contains("model"))
        assertNull(result.items[1].modelArrivalSocPercent)
        val expired = CarChargingListPolicy.build(route, catalog, candidates, fix,
            verifiedBms(), config,
            RoadDetourCatalog(routeId = route.routeId,
                checked = listOf(road.copy(verifiedAtMillis = now - 31 * 60_000L))),
            now, 2)
        assertNull(expired.items[0].modelArrivalSocPercent)
    }

    private fun verifiedBms(): VehicleTelemetry = VehicleTelemetry(
        socPercent = TimedValue(80.0, now - 200),
        socEvidence = CoreEvidence.Available(
            80.0, now - 200, EvidenceSource.VERIFIED_OBD,
            EvidenceClass.MEASUREMENT
        )
    )
}
