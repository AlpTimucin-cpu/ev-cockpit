package com.evcockpit.presentation.charging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.evcockpit.domain.model.*
import com.evcockpit.domain.repository.ChargingRepository
import com.evcockpit.domain.repository.RouteRepository
import com.evcockpit.domain.usecase.StationCorridor

data class ChargingUiState(
    val visible: List<ChargingStation> = emptyList(),
    // Unfiltered EPDK catalog for map display even when list filters are active.
    val allStations: List<ChargingStation> = emptyList(),
    val selectedFilter: StationFilter = StationFilter.ALL,
    val total: Int = 0,
    val corridorCount: Int? = null,
    val corridorStations: List<ChargingStation> = emptyList(),
    val routeProvided: Boolean = false,
    val verifiedAtUtc: String? = null,
    val loading: Boolean = true,
    val fromCache: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ChargingViewModel @Inject constructor(
    private val chargingRepository: ChargingRepository,
    routeRepository: RouteRepository
): ViewModel() {
    private val viewport=MutableStateFlow<GeoViewport?>(null)
    private val filter=MutableStateFlow(StationFilter.ALL)
    private val route=routeRepository.currentRoute
    private val refreshError = MutableStateFlow<String?>(null)

    private data class Inputs(
        val catalog: ChargingCatalog,
        val selected: StationFilter,
        val points: List<GeoCoordinate>,
        val fetchError: String?
    )

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            refreshError.value = null
            // A transient EPDK/network exception must not cancel the ViewModel.
            chargingRepository.refreshResult().onFailure { error ->
                refreshError.value = when (error) {
                    is UnknownHostException -> "Connection error: No internet connection"
                    is SocketTimeoutException -> "Connection error: EPDK is not responding"
                    else -> "Connection error: Could not refresh charging stations"
                }
            }
        }
    }
    fun changeFilter(value: StationFilter) { filter.value=value }
    fun viewportChanged(bounds: GeoViewport) { if(bounds.valid())viewport.value=bounds }

    val uiState: StateFlow<ChargingUiState> = combine(
        chargingRepository.catalog, filter, route, refreshError
    ) { catalog, selected, actualRoute, error ->
        Inputs(catalog, selected, actualRoute?.routePoints.orEmpty(), error)
    }.mapLatest { input ->
        withContext(Dispatchers.Default) {
            val catalog = input.catalog
            val selected = input.selected
            val routePoints = input.points
            // Exact corridor checks use indexed local route segments, not
            // a scan of every OSRM vertex for every one of 13k+ stations.
            val index=if(routePoints.size>=2) StationCorridor.index(routePoints) else null
            val alongIds=if(index!=null)
                catalog.stations.asSequence().filter(index::isDcAlongRoute).map { it.id }.toSet()
            else emptySet()
            val visible=catalog.stations.asSequence()
                .filter { when(selected){
                    StationFilter.ALL -> true
                    StationFilter.DC -> it.hasDc
                    StationFilter.ROUTE_CCS -> it.hasDcCcs && it.id in alongIds
                    StationFilter.ROUTE_DC -> it.id in alongIds
                }}.toList()
            ChargingUiState(
                visible = visible,
                allStations = catalog.stations,
                selectedFilter = selected,
                total = catalog.stations.size,
                corridorCount = if (index != null) alongIds.size else null,
                corridorStations = if (index != null)
                    catalog.stations.filter { it.id in alongIds } else emptyList(),
                routeProvided = routePoints.size >= 2,
                verifiedAtUtc = catalog.verifiedAtUtc,
                loading = catalog.loading,
                fromCache = catalog.staleCache,
                error = input.fetchError ?: catalog.error
            )
        }
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),ChargingUiState())
}
