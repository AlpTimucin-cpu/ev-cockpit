package com.evcockpit.domain.usecase

/**
 * Offline, phone-only research reproduction of the UNSAFE assumptions from
 * the user's uploaded uncompiled ZIP. None of these functions access a
 * Bluetooth socket, GPS, WebView, media player, DataStore, or vehicle state.
 *
 * Source ZIP SHA-256:
 * 9f66d0b1302789b7377ae82b94545daad198a02003c69625f7cf5ab4e413e62a
 */
object ZipPrototypeLabPolicy {
    // The ZIP starts at 80%; in this isolated lab it is a MANUAL DEMO only.
    const val ZIP_INITIAL_DEMO_SOC_PERCENT = 80.0
    const val ZIP_INITIAL_DEMO_CONSUMPTION = 18.5

    /** Approximate old ZIP range formula; never live range or verified SoC. */
    fun demoRangeKm(socPercent: Double, consumptionKwhPer100Km: Double,
                    netCapacityKwh: Double): Double? {
        if (!socPercent.isFinite() || socPercent !in 0.0..100.0 ||
            !consumptionKwhPer100Km.isFinite() ||
            consumptionKwhPer100Km !in 2.0..60.0 ||
            !netCapacityKwh.isFinite() || netCapacityKwh !in 1.0..120.0
        ) return null
        return socPercent / 100.0 * netCapacityKwh /
            consumptionKwhPer100Km * 100.0
    }

    /**
     * Reproduces the ZIP's *candidate* value(b, off=1, len=1, div=2.5).
     * Input is an UDS application payload '62 02 8C aa bb'
     * (without CAN/ISO-TP headers). The offline lab takes typed samples;
     * the user-initiated raw OBD diagnostics screen may also display its
     * output only after the independent header-aware MebUdsParser accepted
     * a read. The scale itself remains UNVERIFIED; this math authenticates
     * neither an ECU nor a BMS signal and NEVER writes vehicle telemetry.
     */
    fun unverified028cFromTypedPayload(input: String): Double? {
        val compact = input.trim().uppercase().replace(Regex("[ \\t]"), "")
        if (!compact.matches(Regex("62028C(?:[0-9A-F]{2}){2,24}"))) return null
        val value = compact.substring(8, 10).toIntOrNull(16)?.div(2.5)
        return value?.takeIf { it.isFinite() && it in 0.0..100.0 }
    }

    /**
     * Illustrates why the old ZIP accepted untrusted ELM text: only the
     * first two characters were checked. OFFLINE comparison, NEVER a command.
     */
    fun prototypeWouldAcceptUnsafeCommand(input: String): Boolean {
        val prototypeNormalized = input.replace(" ", "").uppercase()
        return prototypeNormalized.startsWith("01") ||
            prototypeNormalized.startsWith("22")
    }

    /**
     * OFFLINE example only: the old ZIP allowed video below 3 km/h or when
     * the passenger toggle was set; GPS absence also defaulted to zero.
     * This boolean MUST NOT be connected to video unlock/Android Auto.
     */
    fun prototypeWouldPermitVideo(testSpeedKmh: Double, passengerFlag: Boolean): Boolean =
        testSpeedKmh.isFinite() && testSpeedKmh >= 0.0 &&
            (testSpeedKmh < 3.0 || passengerFlag)
}
