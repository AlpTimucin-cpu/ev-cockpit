package com.evcockpit.domain.model

data class WeatherWaypoint(
    val coordinate: GeoCoordinate,
    val distanceFromStartKm: Double,
    val temperatureC: Double,
    val windSpeedKmh: Double,
    val weatherCode: Int,
    /** UTC valid hour of a prediction; not an observed measurement. */
    val observedAt: String,
    val forecastEtaEpochMs: Long? = null,
    val precipitationProbabilityPercent: Int? = null
) {
    val iconKey: String get() = when (weatherCode) {
        0, 1 -> "sun"
        2, 3, 45, 48 -> "cloud"
        else -> "rain"
    }
    // WMO Open-Meteo weather codes for spoken Turkish announcements.
    val summaryTr: String get() = when (weatherCode) {
        0 -> "açık"
        1 -> "az bulutlu"
        2 -> "parçalı bulutlu"
        3 -> "kapalı"
        45, 48 -> "sisli"
        51, 53, 55, 56, 57 -> "çiseli"
        61, 63, 65, 66, 67 -> "yağmurlu"
        71, 73, 75, 77, 85, 86 -> "kar yağışlı"
        80, 81, 82 -> "sağanak yağışlı"
        95, 96, 99 -> "gök gürültülü"
        else -> "hava durumu değişken"
    }
}

data class RouteWeatherState(
    val routeId: String? = null,
    val routeLengthKm: Double? = null,
    val points: List<WeatherWaypoint> = emptyList(),
    val loading: Boolean = false,
    val fetchedAtMillis: Long? = null,
    val error: String? = null
) {
    fun ageMinutes(nowMs: Long): Long? =
        fetchedAtMillis?.takeIf { nowMs >= it }?.let { (nowMs - it) / 60_000L }

    val destination: WeatherWaypoint? get() = points.lastOrNull()?.takeIf {
        routeLengthKm != null && kotlin.math.abs(it.distanceFromStartKm - routeLengthKm) < 0.2
    }
}
