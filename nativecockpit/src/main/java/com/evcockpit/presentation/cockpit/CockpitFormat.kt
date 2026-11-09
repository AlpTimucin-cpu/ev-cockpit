package com.evcockpit.presentation.cockpit

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** UI-only formatting: unknown input never becomes 0 or an invented value. */
object CockpitFormat {
    private val turkish = Locale.forLanguageTag("tr-TR")

    fun percent(v: Double?): String =
        v?.takeIf { it.isFinite() && it in 0.0..100.0 }
            ?.let { "%.0f%%".format(turkish, it) } ?: "— %"

    fun distance(km: Double?): String =
        km?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { "%.0f km".format(turkish, it) } ?: "— km"

    fun speed(kmh: Double?): String =
        kmh?.takeIf { it.isFinite() && it in 0.0..260.0 }
            ?.let { "%.0f km/h".format(turkish, it) } ?: "— km/h"

    fun voltage(v: Double?): String =
        v?.takeIf { it.isFinite() && it in 0.0..1000.0 }
            ?.let { "%.0f V".format(turkish, it) } ?: "— V"

    fun temperature(c: Double?): String =
        c?.takeIf { it.isFinite() && it in -50.0..110.0 }
            ?.let { "%.1f °C".format(turkish, it) } ?: "— °C"

    fun power(kw: Double?): String =
        kw?.takeIf { it.isFinite() && it in -600.0..600.0 }
            ?.let { "%.1f kW".format(turkish, it) } ?: "— kW"

    fun minutes(min: Long?): String =
        min?.takeIf { it in 0L..100_000L }?.let { "$it min" } ?: "—"

    fun eta(epochMs: Long?): String = try {
        epochMs?.takeIf { it > 0L }?.let {
            val time = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
            time.format(DateTimeFormatter.ofPattern("HH:mm", turkish))
        } ?: "—"
    } catch (_: Exception) { "—" }
}
