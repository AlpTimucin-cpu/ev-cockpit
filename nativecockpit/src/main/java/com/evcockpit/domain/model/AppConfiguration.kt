package com.evcockpit.domain.model

import java.net.URI

/** Vehicle-specific assumptions are empty until deliberately supplied by the driver. */
data class VehicleProfileConfig(
    val usableBatteryKwh: Double? = null,
    val peakDcKw: Double? = null,
    val consumptionKwhPer100Km: Double? = null,
    val arrivalReservePercent: Double? = null,
    val userConfirmed: Boolean = false
) {
    fun valid(): Boolean =
        (usableBatteryKwh == null || usableBatteryKwh.isFinite() && usableBatteryKwh in 1.0..120.0) &&
        (peakDcKw == null || peakDcKw.isFinite() && peakDcKw in 1.0..400.0) &&
        (consumptionKwhPer100Km == null ||
            consumptionKwhPer100Km.isFinite() && consumptionKwhPer100Km in 2.0..60.0) &&
        (arrivalReservePercent == null ||
            arrivalReservePercent.isFinite() && arrivalReservePercent in 0.0..50.0)

    fun readyForEstimate(): Boolean = userConfirmed && valid() &&
        usableBatteryKwh != null && consumptionKwhPer100Km != null &&
        arrivalReservePercent != null

    fun referenceWhPerKm(): Double? = consumptionKwhPer100Km?.times(10.0)
}

/** HTTPS service roots, not credentials. Public OSRM demo is deliberately disallowed. */
data class ServiceEndpointConfig(
    val osrmBaseUrl: String? = null,
    val geocodingBaseUrl: String = "https://nominatim.openstreetmap.org/",
    val weatherBaseUrl: String = "https://api.open-meteo.com/",
    /** Explicit test-only switch. Release builds MUST ignore this value. */
    val debugDemoRoutingEnabled: Boolean = false
) {
    fun validated(): Boolean =
        (osrmBaseUrl == null || checkedHttpsRoot(osrmBaseUrl, osrm = true) != null) &&
        checkedHttpsRoot(geocodingBaseUrl) != null &&
        checkedHttpsRoot(weatherBaseUrl) != null

    companion object {
        fun checkedHttpsRoot(value: String?, osrm: Boolean = false): String? {
            val input = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val uri = try { URI(input) } catch (_: IllegalArgumentException) { return null }
            val host = uri.host?.lowercase() ?: return null
            if (uri.scheme?.lowercase() != "https" ||
                uri.rawUserInfo != null || uri.rawQuery != null ||
                uri.rawFragment != null || uri.port !in -1..65535 ||
                host == "localhost" || host == "127.0.0.1" ||
                (osrm && (host == "router.project-osrm.org" ||
                    host.endsWith(".project-osrm.org")))
            ) return null
            val path = uri.rawPath.orEmpty()
            if (path.contains("//") || path.contains("..") || path.contains("%")) return null
            return if (input.endsWith("/")) input else "$input/"
        }
    }
}

data class AppConfiguration(
    val vehicle: VehicleProfileConfig = VehicleProfileConfig(),
    val endpoints: ServiceEndpointConfig = ServiceEndpointConfig()
)
