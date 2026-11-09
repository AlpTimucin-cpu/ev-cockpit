package com.evcockpit.domain.model

/**
 * Driver-reported seasonal road consumption, not an SAE/OBD observation.
 * Summer 10–11 and winter 17–19 kWh/100 km were supplied separately
 * by the owner. Midpoints are editable trip MODEL scenarios only.
 */
object UserDrivingScenarios {
    data class Season(
        val labelTr: String,
        val minKwhPer100Km: Double,
        val maxKwhPer100Km: Double
    ) {
        val midpointKwhPer100Km: Double
            get() = (minKwhPer100Km + maxKwhPer100Km) / 2.0
    }

    val summer = Season("Yaz", 10.0, 11.0)
    val winter = Season("Kış", 17.0, 19.0)
}
