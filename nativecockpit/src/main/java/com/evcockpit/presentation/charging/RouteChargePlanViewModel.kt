package com.evcockpit.presentation.charging

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import com.evcockpit.domain.model.*
import com.evcockpit.domain.repository.*
import com.evcockpit.domain.usecase.*
import com.evcockpit.data.repository.OsrmRoadDetourRepository

/** ABRP-style stop preferences alter the charging target in the model.
 * Actual stop count and charging time are not guaranteed.
 */
enum class RouteStopPreference(val chargingTargetPercent: Double, val titleTr: String) {
    OPTIMAL(80.0, "Optimal"),
    FEWER(90.0, "Daha az"),
    MINIMUM(100.0, "En az")
}

data class RouteChargingUiState(
    val routeId: String? = null,
    val arrivalReservePercent: Float = 20f,
    val stopPreference: RouteStopPreference = RouteStopPreference.OPTIMAL,
    val plan: AutomaticChargePlan = AutomaticChargePlan.Empty,
    val socSource: String = "MODEL/ESTIMATE",
    val catalogIsStale: Boolean = false,
    val roadDetours: RoadDetourCatalog = RoadDetourCatalog()
)

/** Separate use case owner: no changes to the media, OBD or navigation UI. */
@HiltViewModel
class RouteChargePlanViewModel @Inject constructor(
    @ApplicationContext context: Context,
    routes: RouteRepository,
    charging: ChargingRepository,
    vehicle: VehicleRepository,
    private val roadVerifier: OsrmRoadDetourRepository
) : ViewModel() {
    private val preferences = context.getSharedPreferences(
        "eya_energy_calibration", Context.MODE_PRIVATE
    )

    private data class UserPlanSettings(
        val reservePercent: Float = 20f,
        val preference: RouteStopPreference = RouteStopPreference.OPTIMAL
    )

    private val options = MutableStateFlow(UserPlanSettings(
        reservePercent = preferences.getFloat("arrivalReservePct", 20f)
            .takeIf { it.isFinite() && it in 5f..40f } ?: 20f,
        preference = runCatching {
            RouteStopPreference.valueOf(
                preferences.getString("routeStopPreference", "OPTIMAL").orEmpty()
            )
        }.getOrDefault(RouteStopPreference.OPTIMAL)
    ))

    /** Changes are applied to the next calculation immediately and persisted. */
    fun setArrivalReservePercent(value: Float) {
        val safe = value.coerceIn(5f, 40f)
        preferences.edit().putFloat("arrivalReservePct", safe).apply()
        options.value = options.value.copy(reservePercent = safe)
    }

    fun setStopPreference(value: RouteStopPreference) {
        preferences.edit().putString("routeStopPreference", value.name).apply()
        options.value = options.value.copy(preference = value)
    }

    private data class Prepared(
        val route: RouteSnapshot? = null,
        val candidates: List<DCCorridorCandidate> = emptyList(),
        val cacheStale: Boolean = false
    )

    // Index and geodesic projection only when route or EPDK catalog changes,
    // never every GPS/OBD heartbeat.
    private val prepared = combine(routes.currentRoute, charging.catalog) { route, catalog ->
        withContext(Dispatchers.Default) {
            Prepared(
                route,
                if (route == null) emptyList()
                else AutomaticDcChargingPlanner.candidates(route, catalog.stations),
                catalog.staleCache
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), Prepared())

    // Read-only bounded OSRM via checks, separate from active navigation.
    // Route or EPDK catalog changes cancel previous verification immediately.
    private val roadCatalog: StateFlow<RoadDetourCatalog> = prepared
        .flatMapLatest { preparedRoute ->
            flow {
                val route = preparedRoute.route
                if (route?.routeId.isNullOrBlank()) {
                    emit(RoadDetourCatalog())
                } else {
                    emit(RoadDetourCatalog(routeId = route.routeId, loading = true))
                    emit(roadVerifier.inspect(route, preparedRoute.candidates))
                }
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            RoadDetourCatalog()
        )

    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(10_000L)
        }
    }

    private fun config(key: String): Double? = runCatching {
        preferences.getFloat(key, Float.NaN).toDouble()
            .takeIf { it.isFinite() }
    }.getOrNull()

    val uiState: StateFlow<RouteChargingUiState> = combine(
        prepared, roadCatalog, vehicle.telemetry, clock, options
    ) { selected, roads, telemetry, now, selectedOptions ->
        val route = selected.route
        val currentRoads = roads.takeIf { it.routeId == route?.routeId }
            ?: RoadDetourCatalog(routeId = route?.routeId, loading = true)
        if (route?.routeId == null)
            return@combine RouteChargingUiState(
                arrivalReservePercent = selectedOptions.reservePercent,
                stopPreference = selectedOptions.preference
            )

        val capacity = config("usableBatteryKwh")
        val consumption = config("referenceWhPerKm")
        val reserve = selectedOptions.reservePercent.toDouble()
        if (capacity == null || capacity <= 0 || consumption == null ||
            consumption <= 0 || reserve == null || reserve !in 0.0..100.0
        ) return@combine RouteChargingUiState(
            routeId = route.routeId,
            arrivalReservePercent = selectedOptions.reservePercent,
            stopPreference = selectedOptions.preference,
            plan = AutomaticChargePlan.Unavailable(
                "Kalibre edilmiş kapasite ve tüketim bilgileri gerekli"
            ),
            catalogIsStale = selected.cacheStale,
            roadDetours = currentRoads
        )

        val freshObd = telemetry.socPercent.current(now)
        val measuredAt = telemetry.socPercent.measuredAtMillis
        // Never invent a departure SoC; the user requested real OBD data.
        if (freshObd == null || measuredAt == null) {
            return@combine RouteChargingUiState(
                routeId = route.routeId,
                arrivalReservePercent = selectedOptions.reservePercent,
                stopPreference = selectedOptions.preference,
                plan = AutomaticChargePlan.Unavailable(
                    "Gerçek BMS SoC ölçümü bekleniyor. OBD bağlı ve PID doğrulanmış olmalı."
                ),
                socSource = "OBD / BMS BAĞLANTISI BEKLENİYOR",
                catalogIsStale = selected.cacheStale,
                roadDetours = currentRoads
            )
        }
        val soc = freshObd
        val source = DepartureSoc.FreshObd(soc, measuredAt)

        val baseline = EvBaselineEnergyEstimator.estimate(
            BaselineEnergyInputs(
                route = route,
                departureSoc = source,
                usableBatteryKwh = capacity,
                consumptionWhPerKm = consumption,
                consumptionBasis = ConsumptionBasis.REFERENCE_MODEL,
                requiredArrivalSocPercent = reserve,
                nowMs = now,
                speedKmh = telemetry.speedKmh.current(now)
            )
        )
        val plan = AutomaticDcChargingPlanner.plan(
            route, selected.candidates.map { candidate ->
                candidate.copy(roadDetour = currentRoads.evidence(candidate.station.id))
            }, baseline, capacity, soc, reserve,
            selectedOptions.preference.chargingTargetPercent
        )
        RouteChargingUiState(
            routeId = route.routeId,
            arrivalReservePercent = selectedOptions.reservePercent,
            stopPreference = selectedOptions.preference,
            plan = plan,
            socSource = "CANLI OBD SoC · TAHMİNİ ENERJİ MODELİ",
            catalogIsStale = selected.cacheStale,
            roadDetours = currentRoads
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), RouteChargingUiState())
}
