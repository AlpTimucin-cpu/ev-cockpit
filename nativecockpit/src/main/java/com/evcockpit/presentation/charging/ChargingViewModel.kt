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
import com.evcockpit.domain.usecase.EpdkProvenancePolicy
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay

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
    val error: String? = null,
    val sourceFresh: Boolean = false,
    val sourceRecordCount: Int? = null,
    val publicRecordCount: Int? = null,
    val downloadSuccessAtMillis: Long? = null,
    val verifiedDcCcsStationCount: Int = 0,
    val explicitDcCcsPowerCount: Int = 0
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
        val fetchError: String?,
        val nowMs: Long
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

    private val freshnessClock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000L)
        }
    }

    val uiState: StateFlow<ChargingUiState> = combine(
        chargingRepository.catalog, filter, route, refreshError, freshnessClock
    ) { catalog, selected, actualRoute, error, now ->
        Inputs(catalog, selected, actualRoute?.routePoints.orEmpty(), error, now)
    }.mapLatest { input ->
        withContext(Dispatchers.Default) {
            val catalog = input.catalog
            val selected = input.selected
            val routePoints = input.points
            val sourceFresh = !catalog.staleCache &&
                EpdkProvenancePolicy.isFresh(catalog.verifiedAtUtc, input.nowMs)
            // A stale national export is not a live charging recommendation.
            val safeStations = catalog.stations.takeIf { sourceFresh }.orEmpty()
            val verifiedCcs = safeStations.count { it.hasDcCcs }
            val withExplicitPower = safeStations.count { station ->
                station.sockets.any { socket ->
                    socket.isDc && socket.isCcs &&
                        com.evcockpit.car.CarChargingListPolicy.explicitPowerKw(
                            socket.powerRaw
                        ) != null
                }
            }
            // Exact corridor checks use indexed local route segments, not
            // a scan of every OSRM vertex for every one of 13k+ stations.
            val index=if(routePoints.size>=2) StationCorridor.index(routePoints) else null
            val alongIds=if(index!=null)
                safeStations.asSequence().filter(index::isDcAlongRoute).map { it.id }.toSet()
            else emptySet()
            val visible=safeStations.asSequence()
                .filter { when(selected){
                    StationFilter.ALL -> true
                    StationFilter.DC -> it.hasDc
                    StationFilter.ROUTE_CCS -> it.hasDcCcs && it.id in alongIds
                    StationFilter.ROUTE_DC -> it.id in alongIds
                }}.toList()
            ChargingUiState(
                visible = visible,
                allStations = safeStations,
                selectedFilter = selected,
                total = catalog.stations.size,
                corridorCount = if (index != null) alongIds.size else null,
                corridorStations = if (index != null)
                    safeStations.filter { it.id in alongIds } else emptyList(),
                routeProvided = routePoints.size >= 2,
                verifiedAtUtc = catalog.verifiedAtUtc,
                loading = catalog.loading,
                fromCache = catalog.staleCache || !sourceFresh,
                error = input.fetchError ?: catalog.error
                    ?: if (!sourceFresh && catalog.stations.isNotEmpty())
                        "EPDK kaynak verisi 72 saatten eski: şarj önerileri gizlendi."
                    else null,
                sourceFresh = sourceFresh,
                sourceRecordCount = catalog.sourceRecordCount,
                publicRecordCount = catalog.publicRecordCount,
                downloadSuccessAtMillis = catalog.downloadSuccessAtMillis,
                verifiedDcCcsStationCount = verifiedCcs,
                explicitDcCcsPowerCount = withExplicitPower
            )
        }
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),ChargingUiState())
}
