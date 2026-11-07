package com.evcockpit.domain.model

/** A driver must explicitly choose an already-paired, personally owned adapter. */
data class PairedObdDevice(val name: String, val address: String)

enum class ObdPhase { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class ObdConnectionState(
    val phase: ObdPhase = ObdPhase.DISCONNECTED,
    val devices: List<PairedObdDevice> = emptyList(),
    val selectedDevice: String? = null,
    val error: String? = null,
    val lastSpeedAtMs: Long? = null,
    /** Last direct SAE PID 010D speed, never inferred from GPS or a default. */
    val lastSpeedKmh: Double? = null,
    val lastPollSucceeded: Boolean = false,
    val diagnosticBusy: Boolean = false,
    val diagnosticResult: MebDiagnosticReport? = null
)
