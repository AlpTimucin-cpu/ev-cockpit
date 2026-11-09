package com.evcockpit.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.evcockpit.domain.model.ChargingSocket
import com.evcockpit.domain.model.ChargingStation
import com.evcockpit.domain.model.GeoCoordinate

/** Indexed checks must give the same results as the original exact geometry. */
class StationCorridorIndexTest {
    private val route = listOf(
        GeoCoordinate(37.000, 34.000),
        GeoCoordinate(37.020, 34.020),
        GeoCoordinate(37.030, 34.150),
        GeoCoordinate(37.260, 34.380)
    )

    private fun station(point: GeoCoordinate, ccs: Boolean = true): ChargingStation =
        ChargingStation(
            id = "test-" + point.latitude + "," + point.longitude,
            name = "Test",
            operator = "Test",
            address = "",
            coordinate = point,
            sockets = listOf(
                ChargingSocket("1", if (ccs) "CCS2" else "Type2",
                    if (ccs) "DC" else "AC", "100")
            ),
            sourceVerifiedAtUtc = "2026-10-28"
        )

    @Test fun indexedAndDirectResultsMatch() {
        val index = StationCorridor.index(route)
        val samples = listOf(
            GeoCoordinate(37.000, 34.000),
            GeoCoordinate(37.015, 34.015),
            GeoCoordinate(37.022, 34.050),
            GeoCoordinate(37.040, 34.151),
            GeoCoordinate(37.250, 34.365),
            GeoCoordinate(37.900, 36.900),
            GeoCoordinate(36.450, 33.990),
            GeoCoordinate(37.051, 34.049),
            GeoCoordinate(37.100, 34.200)
        )
        samples.forEach { point ->
            val candidate = station(point)
            assertEquals(
                "Spatial index differed at " + point,
                StationCorridor.isAlongRoute(candidate, route),
                index.isAlongRoute(candidate)
            )
        }
    }

    @Test fun requiresRealCcsAndValidRoute() {
        val index = StationCorridor.index(route)
        assertTrue(index.isAlongRoute(station(route.first())))
        assertFalse(index.isAlongRoute(station(route.first(), ccs = false)))
        assertFalse(StationCorridor.index(emptyList()).isAlongRoute(
            station(route.first())
        ))
    }

    @Test fun batchOfThirteenThousandLocationsKeepsExactResult() {
        val index = StationCorridor.index(route)
        val points = List(13_000) { i ->
            GeoCoordinate(36.5 + (i % 100) * 0.01, 33.5 + (i / 100) * 0.01)
        }
        // Verify exact equivalence across many cells, not a timing assertion.
        points.forEachIndexed { i, point ->
            if (i % 17 == 0) {
                val candidate = station(point)
                assertEquals(
                    StationCorridor.isAlongRoute(candidate, route),
                    index.isAlongRoute(candidate)
                )
            }
        }
    }
}
