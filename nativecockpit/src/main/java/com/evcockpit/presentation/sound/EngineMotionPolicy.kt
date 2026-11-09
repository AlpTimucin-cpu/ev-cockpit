package com.evcockpit.presentation.sound

/** Pure, testable synthetic-audio control; input acceleration is km/h per s. */
internal object EngineMotionPolicy {
    enum class Mode { SILENT, IDLE, ACCELERATING }
    data class Decision(val mode: Mode, val pitch: Float)

    fun decide(
        speedKmh: Double?,
        previousKmh: Double?,
        deltaSeconds: Double
    ): Decision {
        if (speedKmh == null || !speedKmh.isFinite() || speedKmh < 0.0) {
            return Decision(Mode.SILENT, 1.0f)
        }
        if (speedKmh < 5.0) return Decision(Mode.IDLE, 0.95f)

        val acceleration = if (
            previousKmh != null && previousKmh.isFinite() &&
            deltaSeconds in 0.1..20.0
        ) (speedKmh - previousKmh) / deltaSeconds else 0.0

        // Mild pitch progression follows actual OBD km/h; no fake RPM or gear shifts.
        val pitch = (0.90 + speedKmh.coerceIn(0.0, 160.0) / 160.0 * 0.42).toFloat()
        val mode = if (acceleration > 0.4) Mode.ACCELERATING else Mode.IDLE
        return Decision(mode, pitch)
    }
}
