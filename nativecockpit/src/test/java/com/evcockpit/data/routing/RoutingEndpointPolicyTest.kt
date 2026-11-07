package com.evcockpit.data.routing

import com.evcockpit.domain.model.AppConfiguration
import com.evcockpit.domain.model.ServiceEndpointConfig
import org.junit.Assert.*
import org.junit.Test

/** Route service selection must never depend on fake SoC or vehicle confirmation. */
class RoutingEndpointPolicyTest {
    @Test fun freshInstallCannotSilentlySendCoordinatesToSharedPublicDemo() {
        assertNull(RoutingEndpointPolicy.choose(AppConfiguration().endpoints, true))
        assertNull(RoutingEndpointPolicy.choose(ServiceEndpointConfig(), false))
    }

    @Test fun debugOptInEnablesRouteWithoutConfirmedBatteryButReleaseNeverDoes() {
        val config = AppConfiguration(
            endpoints = ServiceEndpointConfig(debugDemoRoutingApproved = true)
        )
        assertNull(config.vehicle.usableBatteryKwh)
        assertFalse(config.vehicle.readyForEstimate())
        val trial = RoutingEndpointPolicy.choose(config.endpoints, true)
        assertNotNull(trial)
        assertTrue(trial!!.publicDemo)
        assertEquals("https://router.project-osrm.org/", trial.root)
        assertNull(RoutingEndpointPolicy.choose(config.endpoints, false))
    }

    @Test fun validatedCustomHttpsServerAlwaysWinsAndIsNotDemo() {
        val config = ServiceEndpointConfig(
            osrmBaseUrl = "https://routes.example.org/osrm",
            debugDemoRoutingApproved = true
        )
        for (debug in listOf(true, false)) {
            val result = RoutingEndpointPolicy.choose(config, debug)
            assertEquals("https://routes.example.org/osrm/", result?.root)
            assertFalse(result?.publicDemo ?: true)
        }
    }

    @Test fun wrongConfiguredServerNeverTriggersUndisclosedFallback() {
        val wrong = ServiceEndpointConfig(
            osrmBaseUrl = "http://127.0.0.1/test",
            debugDemoRoutingApproved = true
        )
        assertNull(RoutingEndpointPolicy.choose(wrong, true))
        val demoAsCustom = ServiceEndpointConfig(
            osrmBaseUrl = "https://router.project-osrm.org/",
            debugDemoRoutingApproved = true
        )
        assertNull(RoutingEndpointPolicy.choose(demoAsCustom, true))
        assertNull(ServiceEndpointConfig.checkedHttpsRoot(
            "https://router.project-osrm.org/", osrm = true
        ))
    }

    @Test fun errorsDescribeActualRoutingProblemNotMissingBatteryProof() {
        val message = RoutingEndpointPolicy.unavailableMessage(true)
        assertTrue(message.contains("Rota servisi ayarlanmadı"))
        assertTrue(message.contains("bataryası onayı rota çizimi için gerekli değildir"))
        assertTrue(RoutingEndpointPolicy.unavailableMessage(false)
            .contains("HTTPS OSRM"))
    }
}
