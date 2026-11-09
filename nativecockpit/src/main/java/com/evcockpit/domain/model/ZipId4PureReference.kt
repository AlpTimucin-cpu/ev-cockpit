package com.evcockpit.domain.model

/**
 * User-uploaded ID.4 Pure PROTOTYPE reference, not manufacturer specification,
 * live ECU telemetry, proof of battery capacity, or a default car profile.
 *
 * ZIP SHA-256: 9f66d0b1302789b7377ae82b94545daad198a02003c69625f7cf5ab4e413e62a
 * README + Core.kt, uploaded 8 November 2026; source describes itself as
 * "v0.2 (derlenmemiş)" / not built. Technical figures need owner verification.
 *
 * Do not import the ZIP's Hub.soc=80f, e-Golf BMS DID scale, synthetic speed,
 * passenger-mode video bypass, test GPS, or public OSRM demo endpoint.
 */
object ZipId4PureReference {
    const val SOURCE_SHA256 =
        "9f66d0b1302789b7377ae82b94545daad198a02003c69625f7cf5ab4e413e62a"
    const val VEHICLE_LABEL = "VW ID.4 Pure"
    const val DRIVE_POWER_KW = 125
    const val DRIVE_POWER_PS = 170
    const val CANDIDATE_NET_BATTERY_KWH = 52.0
    const val CANDIDATE_PEAK_DC_KW = 145.0
    const val CANDIDATE_AC_KW = 11.0
    const val CLAIMED_WLTP_MIN_KM = 354
    const val CLAIMED_WLTP_MAX_KM = 363

    /**
     * Stage ONLY ZIP assertions relevant to the existing vehicle configuration.
     * Do not persist or silently confirm. Carry forward consumption/reserve from
     * the owner's existing entry, if any, but always revoke confirmation because
     * the reference could overwrite previously confirmed vehicle parameters.
     */
    fun stagedDraft(current: VehicleProfileConfig): VehicleProfileConfig = current.copy(
        usableBatteryKwh = CANDIDATE_NET_BATTERY_KWH,
        peakDcKw = CANDIDATE_PEAK_DC_KW,
        userConfirmed = false
    )
}
