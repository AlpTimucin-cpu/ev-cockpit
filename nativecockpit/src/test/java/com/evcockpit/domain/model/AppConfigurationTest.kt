package com.evcockpit.domain.model

import org.junit.Assert.*
import org.junit.Test

class AppConfigurationTest {
    @Test fun noMeasurementsOrOsrmServerAreFabricated() {
        val config = AppConfiguration()
        assertNull(config.vehicle.usableBatteryKwh)
        assertNull(config.vehicle.peakDcKw)
        assertNull(config.vehicle.consumptionKwhPer100Km)
        assertNull(config.endpoints.osrmBaseUrl)
        assertFalse(config.vehicle.readyForEstimate())
    }

    @Test fun explicitlyConfirmedScenarioDoesNotBecomeBmsTelemetry() {
        // Test values represent a manually entered scenario, not live vehicle proof.
        val profile = VehicleProfileConfig(
            usableBatteryKwh = 52.0,
            peakDcKw = null,
            consumptionKwhPer100Km = 10.5,
            arrivalReservePercent = 10.0,
            userConfirmed = true
        )
        assertTrue(profile.valid())
        assertTrue(profile.readyForEstimate())
        assertEquals(105.0, profile.referenceWhPerKm() ?: -1.0, 0.0001)
        assertFalse(profile.copy(userConfirmed = false).readyForEstimate())
    }

    @Test fun invalidOrIncompleteConfigurationsFailClosed() {
        assertFalse(VehicleProfileConfig(userConfirmed = true).readyForEstimate())
        assertFalse(VehicleProfileConfig(usableBatteryKwh = Double.NaN).valid())
        assertFalse(VehicleProfileConfig(peakDcKw = -3.0).valid())
        assertFalse(VehicleProfileConfig(consumptionKwhPer100Km = 0.0).valid())
        assertFalse(VehicleProfileConfig(arrivalReservePercent = 110.0).valid())
    }

    @Test fun endpointValidationRejectsDemoKeysAndNonHttpsUrls() {
        val check = ServiceEndpointConfig.Companion
        assertNull(check.checkedHttpsRoot("https://router.project-osrm.org/", osrm = true))
        assertNull(check.checkedHttpsRoot("http://routing.example/route/", osrm = true))
        assertNull(check.checkedHttpsRoot("https://user:secret@routing.example/", osrm = true))
        assertNull(check.checkedHttpsRoot("https://routing.example/?api_key=example", osrm = true))
        assertNull(check.checkedHttpsRoot("https://localhost/", osrm = true))
        assertEquals("https://routes.example.net/osrm/",
            check.checkedHttpsRoot("https://routes.example.net/osrm", osrm = true))
    }
}
