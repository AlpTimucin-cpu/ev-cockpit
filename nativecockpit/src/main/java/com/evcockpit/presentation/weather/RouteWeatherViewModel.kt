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
 * Active-screen forecasts. Android Doze cannot guarantee 30-minute WorkManager
 * execution; there is intentionally no background location polling here.
 */
@HiltViewModel
class RouteWeatherViewModel @Inject constructor(
    private val weather: OpenMeteoWeatherRepository,
    private val routes: RouteRepository,
    private val locations: LocationRepository
) : ViewModel() {
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    /** Explicit user action: never start a periodic job or ask for background GPS. */
    fun refresh() {
        refreshRequests.trySend(Unit)
    }

    val uiState: StateFlow<RouteWeatherState> = routes.currentRoute
        .flatMapLatest { route ->
            flow {
                if (route?.routeId.isNullOrBlank()) {
                    emit(RouteWeatherState())
                    return@flow
                }
                val activeRoute = requireNotNull(route)
                val routeId = requireNotNull(activeRoute.routeId)
                val progress = RouteProgressTracker()
                var previous: RouteWeatherState? = null
                emit(RouteWeatherState(routeId = routeId, loading = true))

                while (true) {
                    val now = System.currentTimeMillis()
                    val fix = locations.gpsFix.value?.takeIf { it.usable(now) }
                    if (fix == null) {
                        emit(previous?.copy(
                            loading = false,
                            error = "Güncel GPS yok; önceki hava tahmini korunuyor"
                        ) ?: RouteWeatherState(
                            routeId = routeId,
                            error = "Hava tahmini için güncel ve hassas GPS konumu gerekli"
                        ))
                        // No synthetic starting position and no hot-loop while GPS is missing.
                        locations.gpsFix.first { it?.usable(System.currentTimeMillis()) == true }
                        continue
                    }
                    val projected = progress.enrich(activeRoute, fix, now)
                    val result = if (projected?.validProgress() != null) {
                        weather.routeWeather(projected)
                    } else Result.failure(IllegalStateException(
                        "Araç konumu gerçek OSRM rotasına güvenle eşleştirilemiyor"
                    ))
                    val next = result.fold(
                        onSuccess = { it },
                        onFailure = { failure ->
                            previous?.copy(
                                loading = false,
                                error = "Hava güncellenemedi: ${failure.message.orEmpty()}. " +
                                    "Son alınan tahmin gösteriliyor"
                            ) ?: RouteWeatherState(
                                routeId = routeId,
                                error = "Hava tahmini alınamadı: ${failure.message.orEmpty()}"
                            )
                        }
                    )
                    previous = next.takeIf { it.points.isNotEmpty() }
                    emit(next)
                    // Thirty minutes only while observed; manual refresh wakes early.
                    withTimeoutOrNull(30 * 60_000L) { refreshRequests.receive() }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), RouteWeatherState())
}
