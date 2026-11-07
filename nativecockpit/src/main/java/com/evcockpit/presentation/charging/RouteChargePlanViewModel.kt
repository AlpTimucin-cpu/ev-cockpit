package com.evcockpit.presentation.charging

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.evcockpit.data.repository.OsrmRoadDetourRepository
import com.evcockpit.data.settings.AppConfigRepository
import com.evcockpit.domain.model.*
import com.evcockpit.domain.repository.*
import com.evcockpit.domain.usecase.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/** User-chosen charge targets are scenarios, not requested BMS charge commands. */
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

/**
 * One energy-profile source for cockpit AND charger: AppConfigRepository.
 * No reliance on defunct shared-preference battery/consumption keys.
 */
@HiltViewModel
class RouteChargePlanViewModel @Inject constructor(
    @ApplicationContext context: Context,
    routes: RouteRepository,
    charging: ChargingRepository,
    vehicle: VehicleRepository,
    private val locations: LocationRepository,
    private val configuration: AppConfigRepository,
    private val roadVerifier: OsrmRoadDetourRepository
) : ViewModel() {
    private val preferences = context.getSharedPreferences(
        "eya_energy_calibration", Context.MODE_PRIVATE
    )
    private val tracker = RouteProgressTracker()

    private data class UserPlanSettings(
        // Null means inherit the explicitly confirmed vehicle profile reserve.
        val reserveOverridePercent: Float? = null,
        val preference: RouteStopPreference = RouteStopPreference.OPTIMAL
    )

    private val options = MutableStateFlow(UserPlanSettings(
        reserveOverridePercent = if (preferences.contains("arrivalReservePct")) {
            preferences.getFloat("arrivalReservePct", Float.NaN)
                .takeIf { it.isFinite() && it in 5f..40f }
        } else null,
        preference = runCatching {
            RouteStopPreference.valueOf(
                preferences.getString("routeStopPreference", "OPTIMAL").orEmpty()
            )
        }.getOrDefault(RouteStopPreference.OPTIMAL)
    ))

    fun setArrivalReservePercent(value: Float) {
        if (!value.isFinite()) return
        val safe = value.coerceIn(5f, 40f)
        preferences.edit().putFloat("arrivalReservePct", safe).apply()
        options.value = options.value.copy(reserveOverridePercent = safe)
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

    private val prepared = combine(routes.currentRoute, charging.catalog) { route, catalog ->
        withContext(Dispatchers.Default) {
            Prepared(
                route = route,
                candidates = if (route == null) emptyList()
                    else AutomaticDcChargingPlanner.candidates(route, catalog.stations),
                cacheStale = catalog.staleCache
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), Prepared())

    private val roadCatalog: StateFlow<RoadDetourCatalog> = prepared
        .flatMapLatest { preparedRoute ->
            flow {
                val active = preparedRoute.route?.takeIf { !it.routeId.isNullOrBlank() }
                if (active == null) {
                    emit(RoadDetourCatalog())
                } else {
                    emit(RoadDetourCatalog(routeId = active.routeId, loading = true))
                    emit(roadVerifier.inspect(active, preparedRoute.candidates))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), RoadDetourCatalog())

    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(10_000L)
        }
    }

    private data class RouteInputs(
        val prepared: Prepared,
        val config: AppConfiguration,
        val gps: GpsFix?
    )

    private val routeInputs = combine(
        prepared, configuration.configuration, locations.gpsFix
    ) { prepared, config, gps -> RouteInputs(prepared, config, gps) }

    val uiState: StateFlow<RouteChargingUiState> = combine(
        routeInputs, roadCatalog, vehicle.telemetry, clock, options
    ) { selected, roads, telemetry, now, selectedOptions ->
        val prepared = selected.prepared
        val route = prepared.route
        val reserve = selectedOptions.reserveOverridePercent?.toDouble()
            ?: selected.config.vehicle.arrivalReservePercent
        val displayReserve = (reserve?.toFloat() ?: 20f).coerceIn(5f, 40f)
        val currentRoads = roads.takeIf { it.routeId == route?.routeId }
            ?: RoadDetourCatalog(routeId = route?.routeId, loading = true)
        fun state(plan: AutomaticChargePlan, source: String): RouteChargingUiState =
            RouteChargingUiState(
                routeId = route?.routeId,
                arrivalReservePercent = displayReserve,
                stopPreference = selectedOptions.preference,
                plan = plan,
                socSource = source,
                catalogIsStale = prepared.cacheStale,
                roadDetours = currentRoads
            )

        if (route?.routeId == null) return@combine state(
            AutomaticChargePlan.Empty, "MODEL/ESTIMATE"
        )
        val gps = selected.gps?.takeIf { it.usable(now) }
        val progressed = tracker.enrich(route, gps, now)
        val energy = RemainingRouteEnergyPolicy.estimate(
            progressed, telemetry, selected.config.vehicle, now, reserve
        )
        if (energy !is BaselineEnergyResult.Estimate) {
            return@combine state(
                AutomaticChargePlan.Unavailable(
                    (energy as BaselineEnergyResult.Unavailable).reason
                ),
                "DOĞRULANMIŞ BMS / GPS VE KULLANICI PROFİLİ BEKLENİYOR"
            )
        }
        val battery = VerifiedBatterySocPolicy.read(telemetry, now)
            ?: return@combine state(
                AutomaticChargePlan.Unavailable("Doğrulanmış BMS SoC bulunamadı"),
                "BMS BEKLENİYOR"
            )

        // Per-station road differences were measured FROM THE ORIGINAL origin.
        // Do not reuse those checks far along a live trip: user must reroute
        // from fresh GPS to get new station roads and corrected distances.
        val fraction = progressed?.validProgress()
        if (fraction == null || fraction > 0.005f) {
            return@combine state(
                AutomaticChargePlan.Unavailable(
                    "Yolculuk başladı; güncel GPS noktasından rotayı yeniden oluşturun"
                ),
                "BMS GÜNCEL · TÜKETİM KULLANICI MODELİ"
            )
        }
        val remainingKm = progressed.remainingKm
            ?: return@combine state(
                AutomaticChargePlan.Unavailable("Güncel kalan rota mesafesi yok"),
                "GPS BEKLENİYOR"
            )
        val originalKm = route.totalKm
            ?: return@combine state(
                AutomaticChargePlan.Unavailable("OSRM mesafesi yok"),
                "OSRM BEKLENİYOR"
            )
        val traveled = originalKm - remainingKm
        val candidates = prepared.candidates.asSequence()
            .filter { it.distanceFromStartKm > traveled + 1.0 }
            .map { candidate ->
                candidate.copy(
                    distanceFromStartKm = candidate.distanceFromStartKm - traveled,
                    roadDetour = currentRoads.evidence(candidate.station.id)
                )
            }
            .toList()
        val plan = AutomaticDcChargingPlanner.plan(
            route.copy(totalKm = remainingKm), candidates, energy,
            requireNotNull(selected.config.vehicle.usableBatteryKwh),
            battery.percent, requireNotNull(reserve),
            selectedOptions.preference.chargingTargetPercent
        )
        state(plan, "GERÇEK BMS SoC · KULLANICI TÜKETİM MODELİ")
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000L), RouteChargingUiState()
    )
}
