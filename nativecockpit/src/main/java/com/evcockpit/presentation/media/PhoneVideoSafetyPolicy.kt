package com.evcockpit.presentation.media

/**
 * Fail closed for phone-only embedded video. A parked checkbox is not a vehicle
 * gear/Park measurement. Even with confirmed stationary speed, this only gates
 * playback; the driver remains responsible for avoiding video while driving.
 *
 * Android Auto Car App Service never exposes video/WebView templates.
 */
object PhoneVideoSafetyPolicy {
    fun allow(
        phoneNotProjected: Boolean,
        userConfirmedParked: Boolean,
        obdConnected: Boolean,
        freshVehicleSpeedKmh: Double?
    ): Boolean = phoneNotProjected && userConfirmedParked && obdConnected &&
        freshVehicleSpeedKmh != null &&
        freshVehicleSpeedKmh.isFinite() &&
        freshVehicleSpeedKmh in 0.0..0.5
}
