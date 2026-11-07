package com.evcockpit.data.routing

import com.evcockpit.domain.model.ServiceEndpointConfig

/**
 * A road ROUTE does not require confirmed battery capacity, SoC or DC power.
 *
 * Production uses only a configured HTTPS OSRM service. Debug APK may use the
 * FOSSGIS OSRM demonstration server after explicit consent to share route
 * coordinates with that third party. Demo use has no uptime, traffic,
 * privacy or navigation-quality guarantees and is not for live reliance.
 */
object RoutingEndpointPolicy {
    const val DEBUG_DEMO_URL = "https://router.project-osrm.org/"

    data class Choice(val root: String, val publicDemo: Boolean)

    fun choose(config: ServiceEndpointConfig, isDebugBuild: Boolean): Choice? {
        // A configured provider always takes precedence and uses production
        // strict URL validation (public demo is never a custom provider).
        config.osrmBaseUrl?.let { entered ->
            val validated = ServiceEndpointConfig.checkedHttpsRoot(
                entered, osrm = true
            ) ?: return null
            return Choice(validated, publicDemo = false)
        }
        if (isDebugBuild && config.debugDemoRoutingApproved) {
            return Choice(DEBUG_DEMO_URL, publicDemo = true)
        }
        return null
    }

    fun unavailableMessage(debugBuild: Boolean): String =
        if (debugBuild)
            "Rota servisi ayarlanmadı. Ayarlar bölümünde ücretsiz, sınırlı OSRM " +
                "denemesine açıkça izin verin veya kendi HTTPS OSRM adresinizi girin. " +
                "Araç bataryası onayı rota çizimi için gerekli değildir."
        else
            "Rota servisi ayarlanmadı. Ayarlar bölümüne güvenilir HTTPS OSRM " +
                "servis adresinizi girin. Araç bataryası onayı rota çizimi için " +
                "gerekli değildir."
}
