package com.evcockpit.domain.model

/** Opt-in ECU read; diagnostic candidates are NEVER vehicle telemetry. */
enum class MebResponseStatus {
    VALID_RAW, NO_DATA, NEGATIVE, WRONG_ECU, WRONG_IDENTIFIER,
    INCOMPLETE, MALFORMED, ADAPTER_ERROR
}

data class MebDiagnosticReport(
    val request: String,
    val receivedAtMillis: Long,
    val status: MebResponseStatus,
    val responder: String? = null,
    val payloadHex: String? = null,
    /** Community-reported 22028C BMS SoC. Not confirmed on this specific vehicle. */
    val candidateBmsSocPercent: Double? = null,
    /** Community-reported 221E0E max BMS temperature, not battery SoH. */
    val candidateMaxBatteryTempC: Double? = null
)
