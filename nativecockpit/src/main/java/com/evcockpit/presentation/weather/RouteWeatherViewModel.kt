package com.evcockpit.presentation.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.evcockpit.data.weather.OpenMeteoWeatherRepository
import com.evcockpit.domain.model.RouteWeatherState
import com.evcockpit.domain.repository.LocationRepository
import com.evcockpit.domain.repository.RouteRepository
import com.evcockpit.domain.usecase.RouteProgressTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground-observed 30-minute refresh. No WorkManager/background GPS:
 * Doze cannot guarantee any fixed 30-minute update time.
 * Each new route ID triggers an immediate model request once real GPS exists.
 */
@HiltViewModel
class RouteWeatherViewModel @Inject constructor(
    private val weather: OpenMeteoWeatherRepository,
    private val routes: RouteRepository,
    private val locations: LocationRepository
) : ViewModel() {
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    fun refresh() {
        refreshRequests.trySend(Unit)
    }

    val uiState: StateFlow<RouteWeatherState> = routes.currentRoute
        .distinctUntilChangedBy { it?.routeId }
        .flatMapLatest { route ->
            flow {
                val routeId = route?.routeId
                val progress = RouteProgressTracker()
                var previous: RouteWeatherState? = null
                var manual = false
                emit(RouteWeatherState(routeId = routeId, loading = true))

                while (true) {
                    val startedAt = System.currentTimeMillis()
                    val fix = locations.gpsFix.value?.takeIf {
                        it.usable(startedAt) && it.accuracyMeters <= 35f
                    }
                    if (fix == null) {
                        emit(previous?.copy(
                            loading = false,
                            error = "Hassas GPS yok; son model verisi korunuyor"
                        ) ?: RouteWeatherState(
                            routeId = routeId,
                            error = "Güncel ve hassas GPS gerekli; hava kullanılamıyor"
                        ))
                        locations.gpsFix.first {
                            it?.usable(System.currentTimeMillis()) == true &&
                                it.accuracyMeters <= 35f
                        }
                        continue
                    }
                    val result = if (routeId == null || route == null) {
                        weather.currentAt(fix.coordinate).map { current ->
                            RouteWeatherState(
                                current = current,
                                currentFetchedAtMillis = System.currentTimeMillis()
                            )
                        }
                    } else {
                        val projected = progress.enrich(route, fix, startedAt)
                        if (projected?.validProgress() != null) {
                            weather.routeWeather(projected, fix.coordinate, manual = manual)
                        } else {
                            weather.currentAt(fix.coordinate).map { current ->
                                RouteWeatherState(
                                    routeId = routeId,
                                    current = current,
                                    currentFetchedAtMillis = System.currentTimeMillis(),
                                    error = "OSRM rota eşleşmesi yok; geçiş tahmini kullanılamıyor"
                                )
                            }
                        }
                    }
                    val next = result.fold(
                        onSuccess = { it },
                        onFailure = { failure ->
                            val message = "Open-Meteo modeli güncellenemedi: " +
                                failure.message.orEmpty().take(100) + "."
                            previous?.copy(loading = false, error = message) ?:
                                RouteWeatherState(routeId = routeId, error = message)
                        }
                    )
                    previous = next.takeIf { it.current != null || it.points.isNotEmpty() }
                    emit(next)
                    // Automatic: >=30 minutes between attempt START times, even on
                    // HTTP failures. Manual refresh is explicit and may be earlier.
                    val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                    val remaining = (30L * 60_000L - elapsed).coerceAtLeast(0L)
                    manual = withTimeoutOrNull(remaining) {
                        refreshRequests.receive()
                        true
                    } == true
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), RouteWeatherState())
}
