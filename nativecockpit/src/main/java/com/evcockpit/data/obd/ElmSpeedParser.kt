package com.evcockpit.data.obd

/** SAE J1979 Mode 01 PID 0D: vehicle speed (km/h). NOT EV battery SoC. */
object ElmSpeedParser {
    private val speedFrame = Regex("41\\s*0D\\s*([0-9A-F]{2})(?![0-9A-F])")
    private val errors = listOf("NO DATA", "STOPPED", "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR")

    fun decode(response: String): Double? {
        if (response.length > 4_096) return null
        val raw = response.uppercase()
        if (errors.any(raw::contains)) return null
        val readings = speedFrame.findAll(raw)
            .map { it.groupValues[1].toInt(16) }.toList()
        // Multiple responding ECUs must not be treated as one reliable measurement.
        return readings.distinct().singleOrNull()?.toDouble()
    }
}
