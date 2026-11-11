package com.evcockpit.car

import com.evcockpit.domain.model.RouteWeatherState
import com.evcockpit.domain.model.WeatherWaypoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Open-Meteo hourly predictions, never observed or measured road conditions. */
internal data class CarWeatherRow(
    val title: String,
    val description: String
)

internal data class CarWeatherPresentation(
    val rows: List<CarWeatherRow> = emptyList(),
    val provenance: String = "Saatlik hava tahmini kullanılamıyor",
    val message: String? = null
)

/** Pure, host-bounded display policy. No network or location sources here. */
internal object CarWeatherPresentationPolicy {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("tr-TR"))

    fun display(
        state: RouteWeatherState,
        currentRouteId: String?,
        nowMs: Long,
        hostLimit: Int
    ): CarWeatherPresentation {
        if (currentRouteId.isNullOrBlank() || state.routeId != currentRouteId) {
            return CarWeatherPresentation(message = "Gerçek OSRM rotası için tahmin bekleniyor.")
        }
        val fetchedAt = state.fetchedAtMillis
        val age = state.ageMinutes(nowMs)
        val status = when {
            fetchedAt == null || age == null -> "Open-Meteo · alım zamanı bilinmiyor"
            state.error != null -> "Son alınan TAHMİN · ${age} dk önce · yenileme başarısız"
            age >= 30L -> "Open-Meteo TAHMİN · ${age} dk önce · güncelleme bekleniyor"
            else -> "Open-Meteo TAHMİN · ${age} dk önce"
        }
        if (hostLimit <= 0) return CarWeatherPresentation(
            provenance = status,
            message = "Araç ekranı hava durumu satırı gösteremiyor."
        )
        val rows = mutableListOf<CarWeatherRow>()
        val currentAge = state.currentAgeMinutes(nowMs)
        state.current?.takeIf { current ->
            currentAge != null && current.coordinate.valid() &&
                current.temperatureC.isFinite() &&
                current.windSpeedKmh.isFinite() &&
                current.weatherCode in 0..99
        }?.let { current ->
            rows += CarWeatherRow(
                title = "Şu anki GPS · MODEL · ${currentAge} dk önce",
                description = description(current)
            )
        }
        val total = state.routeLengthKm?.takeIf { it.isFinite() && it > 0.0 }
        val safe = state.points.asSequence()
            .filter { point ->
                point.coordinate.valid() && point.distanceFromStartKm.isFinite() &&
                    point.distanceFromStartKm >= 0.0 &&
                    (total == null || point.distanceFromStartKm <= total + 0.5) &&
                    point.forecastEtaEpochMs?.let { it > 0L } == true &&
                    point.observedAt.isNotBlank() &&
                    point.temperatureC.isFinite() && point.temperatureC in -90.0..65.0 &&
                    point.windSpeedKmh.isFinite() && point.windSpeedKmh in 0.0..350.0 &&
                    point.weatherCode in 0..99
            }
            .sortedBy { it.distanceFromStartKm }
            .take((hostLimit - rows.size).coerceAtLeast(0))
            .map { point ->
                val waypoint = if (total != null &&
                    kotlin.math.abs(point.distanceFromStartKm - total) < 0.2) {
                    "Varış"
                } else {
                    String.format(Locale.forLanguageTag("tr-TR"),
                        "Rota %.0f km", point.distanceFromStartKm)
                }
                val time = Instant.ofEpochMilli(requireNotNull(point.forecastEtaEpochMs))
                    .atZone(ZoneId.systemDefault()).format(timeFormat)
                CarWeatherRow(
                    title = "${waypoint} · tahmini geçiş ${time}",
                    description = description(point)
                )
            }.toList()
        rows += safe
        return CarWeatherPresentation(
            rows = rows,
            provenance = status,
            message = if (rows.isEmpty())
                state.error?.take(120) ?: if (state.loading)
                    "Hava modeli yükleniyor."
                else "Model verisi kullanılamıyor."
            else null
        )
    }

    fun warningText(point: WeatherWaypoint): String =
        "Rota üzerinde hava tahmini: ${point.summaryTr}. Yol koşullarına dikkat."

    private fun description(point: WeatherWaypoint): String {
        val temp = String.format(Locale.forLanguageTag("tr-TR"),
            "%.0f °C", point.temperatureC)
        val wind = String.format(Locale.forLanguageTag("tr-TR"),
            "%.0f km/sa", point.windSpeedKmh)
        val precip = point.precipitationProbabilityPercent?.takeIf { it in 0..100 }
            ?.let { " · yağış olasılığı %${it}" } ?: ""
        return "${point.summaryTr} · ${temp} · rüzgâr ${wind}${precip}"
    }
}

/** Foreground-only cadence. No Doze-exempt alarms or background location. */
internal class CarWeatherRefreshGate {
    companion object { const val AUTOMATIC_REFRESH_MS = 30L * 60_000L }
    private var activeRouteId: String? = null
    private var lastAttemptAtMs: Long? = null

    fun reset() {
        activeRouteId = null
        lastAttemptAtMs = null
    }

    fun shouldRefresh(
        routeId: String?,
        hasVerifiedGpsRouteProgress: Boolean,
        nowMs: Long,
        manual: Boolean = false
    ): Boolean {
        if (routeId.isNullOrBlank() || nowMs <= 0L) {
            reset()
            return false
        }
        if (activeRouteId != routeId) {
            activeRouteId = routeId
            lastAttemptAtMs = null
        }
        if (!hasVerifiedGpsRouteProgress) return false
        val previous = lastAttemptAtMs ?: return true
        val interval = if (manual) 60_000L else AUTOMATIC_REFRESH_MS
        return nowMs >= previous && nowMs - previous >= interval
    }

    fun markAttempt(routeId: String, nowMs: Long) {
        if (routeId != activeRouteId || nowMs <= 0L) return
        lastAttemptAtMs = nowMs
    }
}
