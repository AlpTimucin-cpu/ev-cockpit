package com.evcockpit.data.weather

import com.evcockpit.data.settings.AppConfigRepository
import com.evcockpit.diagnostics.DiagnosticsHub
import com.evcockpit.domain.usecase.BoundedNetworkRetry
import com.evcockpit.domain.usecase.RouteForecastPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import com.evcockpit.domain.model.*

internal interface OpenMeteoService {
    @GET("v1/forecast")
    suspend fun weather(
        @Query("latitude") latitudes: String,
        @Query("longitude") longitudes: String,
        @Query("current") fields: String = "temperature_2m,wind_speed_10m,weather_code",
        @Query("timezone") timezone: String = "GMT",
        @Query("timeformat") timeFormat: String = "unixtime"
    ): Response<JsonElement>

    @GET("v1/forecast")
    suspend fun hourlyForecast(
        @Query("latitude") latitudes: String,
        @Query("longitude") longitudes: String,
        @Query("hourly") fields: String =
            "temperature_2m,wind_speed_10m,weather_code,precipitation_probability",
        @Query("timezone") timezone: String = "GMT",
        @Query("timeformat") timeFormat: String = "unixtime",
        @Query("forecast_days") forecastDays: Int = 7,
        @Query("temperature_unit") temperatureUnit: String = "celsius",
        @Query("wind_speed_unit") windUnit: String = "kmh",
        @Query("current") currentFields: String = "temperature_2m,wind_speed_10m,weather_code"
    ): Response<JsonElement>
}

/** A bounded, cancellable request for real model-based current conditions. */
@Singleton
class OpenMeteoWeatherRepository @Inject constructor(
    private val appConfig: AppConfigRepository,
    private val diagnostics: DiagnosticsHub
) {
    // Shared only for active route warning checks; never persists location or
    // transforms model output into live meteorological observations.
    private val _latestForecast = MutableStateFlow(RouteWeatherState())
    val latestForecast: StateFlow<RouteWeatherState> = _latestForecast
    // Serialize foreground phone/AA requests to avoid duplicate API calls
    // and preserve the 30-minute per-route automatic cadence.
    private val routeMutex = Mutex()

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .build()
    private fun api(root: String): OpenMeteoService = Retrofit.Builder()
        .baseUrl(root)
        .client(http)
        .addConverterFactory(GsonConverterFactory.create())
        .build().create(OpenMeteoService::class.java)

    /**
     * Query Open-Meteo hourly FORECAST for ETA at up to eight GPS-grounded
     * points on the remaining route. A missing GPS projection yields no guess.
     */
    suspend fun routeWeather(
        route: RouteSnapshot,
        actualGpsCoordinate: GeoCoordinate,
        manual: Boolean = false
    ): Result<RouteWeatherState> = routeMutex.withLock {
        try {
            require(actualGpsCoordinate.valid()) { "Güncel GPS koordinatı doğrulanamadı" }
            val now = System.currentTimeMillis()
            val cached = _latestForecast.value
            if (!manual && cached.routeId == route.routeId &&
                cached.points.isNotEmpty() &&
                cached.fetchedAtMillis?.let { now >= it &&
                    now - it < 30L * 60_000L } == true
            ) return@withLock Result.success(cached)

            val sites = withContext(Dispatchers.Default) {
                RouteForecastPolicy.sample(route, now)
            }
            if (sites.isEmpty()) throw IllegalArgumentException(
                "Güncel GPS rota ilerlemesi veya OSRM süre tahmini bulunamadı"
            )
            // The first request coordinate comes from actual, fresh GPS;
            // the following <=8 coordinates are GPS-snapped OSRM model ETAs.
            // Open-Meteo accepts multiple coordinates in a single HTTP call.
            val allCoordinates = listOf(actualGpsCoordinate) + sites.map { it.coordinate }
            diagnostics.weatherAttempt()
            val response = withContext(Dispatchers.IO) {
                val latitudes = allCoordinates.joinToString(",") {
                    String.format(Locale.US, "%.5f", it.latitude)
                }
                val longitudes = allCoordinates.joinToString(",") {
                    String.format(Locale.US, "%.5f", it.longitude)
                }
                val root = appConfig.configuration.first().endpoints.weatherBaseUrl
                BoundedNetworkRetry.io {
                    api(root).hourlyForecast(latitudes, longitudes)
                }
            }
            if (!response.isSuccessful) {
                response.errorBody()?.close()
                throw IOException("Open-Meteo HTTP ${response.code()}")
            }
            val data = response.body()?.takeUnless { it.isJsonNull }
                ?: throw IOException("Open-Meteo boş yanıt döndürdü")
            val objects = when {
                data.isJsonArray -> data.asJsonArray.map {
                    it.takeIf { element -> element.isJsonObject }?.asJsonObject
                }
                data.isJsonObject -> listOf(data.asJsonObject)
                else -> throw IOException("Open-Meteo beklenmeyen JSON yanıtı")
            }
            if (objects.size != allCoordinates.size) {
                throw IOException("Open-Meteo yanıtındaki konum sayısı eşleşmiyor")
            }
            val receivedAt = System.currentTimeMillis()
            val current = objects.first()?.let {
                CurrentWeatherParser.match(it, actualGpsCoordinate, receivedAt)
            }
            val forecasts = sites.mapIndexedNotNull { index, site ->
                objects[index + 1]?.let { HourlyForecastParser.match(site, it) }
            }
            if (forecasts.isEmpty() && current == null) {
                throw IOException("Güncel veya saatlik hava modeli doğrulanamadı")
            }
            val error = when {
                forecasts.size != sites.size -> "Bazı geçiş saatlerine ait hava tahmini alınamadı"
                current == null -> "Güncel konum hava modeli alınamadı"
                else -> null
            }
            val fetched = RouteWeatherState(
                routeId = route.routeId,
                routeLengthKm = sites.last().distanceFromStartKm,
                current = current,
                currentFetchedAtMillis = receivedAt.takeIf { current != null },
                points = forecasts,
                fetchedAtMillis = receivedAt.takeIf { forecasts.isNotEmpty() },
                error = error
            )
            _latestForecast.value = fetched
            diagnostics.weatherFinished(true, receivedAt)
            Result.success(fetched)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            diagnostics.weatherFinished(false)
            Result.failure(error)
        }
    }

    /**
     * Fetch current conditions at an actual, validated GPS coordinate.
     * Used by the 30-minute spoken alert, not by the route sample cache.
     */
    suspend fun currentAt(coordinate: GeoCoordinate): Result<WeatherWaypoint> = try {
        require(coordinate.valid()) { "Geçersiz GPS konumu" }
        diagnostics.weatherAttempt()
        val response = withContext(Dispatchers.IO) {
            val root = appConfig.configuration.first().endpoints.weatherBaseUrl
            BoundedNetworkRetry.io {
                api(root).weather(
                    String.format(Locale.US, "%.5f", coordinate.latitude),
                    String.format(Locale.US, "%.5f", coordinate.longitude)
                )
            }
        }
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            throw IOException("Open-Meteo HTTP ${response.code()}")
        }
        val root = response.body()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IOException("Open-Meteo güncel model yanıtı bulunamadı")
        val receivedAt = System.currentTimeMillis()
        val current = CurrentWeatherParser.match(root, coordinate, receivedAt)
            ?: throw IOException("Güncel konum model saati veya değerleri geçersiz")
        diagnostics.weatherFinished(true, receivedAt, "güncel konum MODEL")
        Result.success(current)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        diagnostics.weatherFinished(false)
        Result.failure(error)
    }

    companion object {
    /**
     * Geodesic sampling every 100 km including start and exact final destination.
     * Cap is fail-closed rather than silently skipping unrequested route segments.
     */
    internal fun sample(path: List<GeoCoordinate>): List<Pair<GeoCoordinate, Double>> {
        if (!validRouteGeometry(path)) return emptyList()
        val cumulative = DoubleArray(path.size)
        for (i in 1 until path.size) {
            cumulative[i] = cumulative[i - 1] + distance(path[i - 1], path[i])
        }
        val total = cumulative.last()
        if (total <= 1.0 || !total.isFinite()) return emptyList()
        val count = ceil(total / 100_000.0).toInt() + 1
        if (count !in 2..65) return emptyList()
        val steps = mutableListOf(0.0)
        var d = 100_000.0
        while (d < total - 1.0) {
            steps += d
            d += 100_000.0
        }
        steps += total
        return steps.map { target ->
            var segment = 0
            while (segment < path.lastIndex - 1 && cumulative[segment + 1] < target)
                segment++
            val length = cumulative[segment + 1] - cumulative[segment]
            val f = if (length > 0.0) (target - cumulative[segment]) / length else 0.0
            val a = path[segment]
            val b = path[segment + 1]
            GeoCoordinate(
                a.latitude + (b.latitude - a.latitude) * f,
                a.longitude + (b.longitude - a.longitude) * f
            ) to target
        }
    }

    private fun distance(a: GeoCoordinate, b: GeoCoordinate): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dlat = lat2 - lat1
        val dlon = Math.toRadians(b.longitude - a.longitude)
        val x = sin(dlat/2).pow(2) + cos(lat1)*cos(lat2)*sin(dlon/2).pow(2)
        return 2.0 * 6_371_008.8 * asin(sqrt(x.coerceIn(0.0,1.0)))
    }

    }
}
