# EV Cockpit — Production Hardware / R8 / Media — Tam Kaynak Kodlar

Bu kaynak dosyası geliştirme dalındaki eksiksiz Kotlin, Gradle, ProGuard, XML ve CI içeriklerini telefon GitHub tarayıcısından incelemek/kopyalamak için üretilmiştir. Silinen simülasyon üreticileri pakete dahil değildir. **Tam telefon ve araç testi olmadan production sertifikası sayılamaz.**

## 1. `nativecockpit/src/main/java/com/evcockpit/presentation/navigation/NavigationViewModel.kt`

````kotlin
package com.evcockpit.presentation.navigation

import android.content.Context
import android.util.Log
import com.evcockpit.BuildConfig
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.evcockpit.car.CarNavigationOwnership
import com.evcockpit.data.location.FusedCurrentLocationProvider
import com.evcockpit.domain.model.*
import com.evcockpit.domain.repository.LocationRepository
import com.evcockpit.domain.repository.RouteRepository
import com.evcockpit.domain.repository.VehicleRepository
import com.evcockpit.domain.usecase.NavigationSpeechBridge
import com.evcockpit.domain.usecase.RouteProgressTracker
import com.evcockpit.domain.usecase.EvBaselineEnergyEstimator
import com.evcockpit.domain.usecase.BaselineEnergyInputs
import com.evcockpit.domain.usecase.BaselineEnergyResult
import com.evcockpit.domain.usecase.DepartureSoc
import com.evcockpit.domain.usecase.ConsumptionBasis
import com.evcockpit.presentation.cockpit.CockpitUiState
import com.evcockpit.presentation.route.RoutePlannerUiState
import com.evcockpit.presentation.voice.AppAudioDucking
import com.evcockpit.presentation.voice.NativeVoiceGuide
import com.evcockpit.presentation.voice.VoicePreference

data class VoiceUiState(
    val enabled: Boolean = true,
    val volume: Float = 0.8f,
    val status: NativeVoiceGuide.Status = NativeVoiceGuide.Status.LOADING,
    val error: String? = null
)

sealed interface RouteLoadStatus {
    data object Idle : RouteLoadStatus
    data object Empty : RouteLoadStatus
    data object Loading : RouteLoadStatus
    data object GpsLost : RouteLoadStatus
    data class Success(val routeId: String) : RouteLoadStatus
    data class Error(val message: String) : RouteLoadStatus
}

data class NavigationUiState(
    val cockpit: CockpitUiState = CockpitUiState(),
    val planner: RoutePlannerUiState = RoutePlannerUiState(),
    val voice: VoiceUiState = VoiceUiState(),
    val routeState: NavigationRouteState = NavigationRouteState(),
    val loadStatus: RouteLoadStatus = RouteLoadStatus.Empty
)

/** Single source of truth for the route editor AND the main navigation panel. */
data class NavigationRouteState(
    val startLocation: GeoCoordinate? = null,
    val startLabel: String? = null,
    val destination: RoutePlace? = null,
    val routeResult: RouteSnapshot? = null,
    val calculating: Boolean = false,
    val error: String? = null
)

/** Single Activity ViewModel; one shared RouteRepository feeds planner, cockpit and car. */
@HiltViewModel
class NavigationViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val routes: RouteRepository,
    private val locationRepository: LocationRepository,
    private val currentLocation: FusedCurrentLocationProvider,
    private val vehicleRepository: VehicleRepository,
    audioDucking: AppAudioDucking
) : ViewModel() {
    private val prefs = VoicePreference(context)
    // Read only explicitly provisioned, independently verified calibration inputs.
    // No hard-coded battery capacity, consumption or assumed SoC.
    private val modelPrefs = context.applicationContext.getSharedPreferences(
        "eya_energy_calibration", Context.MODE_PRIVATE
    )
    private val guide = NativeVoiceGuide(context, audioDucking)
    private val speechBridge = NavigationSpeechBridge()
    private val progress = RouteProgressTracker()
    private val _foreground = MutableStateFlow(false)
    private var gpsWaitSinceMs = System.currentTimeMillis()
    // Genuine ELM327 measurements and Fused provider fixes only.
    private data class LiveSources(val telemetry: VehicleTelemetry, val gps: GpsFix?)
    private val sources = combine(
        vehicleRepository.telemetry, locationRepository.gpsFix
    ) { telemetry, gps -> LiveSources(telemetry, gps) }
    // Exposed effective location, sourced from the same flow as cockpit and TTS.
    // Do not inject synthetic locations into FusedLocationProviderClient.
    val gpsLocation: StateFlow<GpsFix?> = sources.map { it.gps }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _gpsError = MutableStateFlow<String?>(null)
    val gpsError: StateFlow<String?> = _gpsError.asStateFlow()
    private val _planner = MutableStateFlow(RoutePlannerUiState())
    private val _voice = MutableStateFlow(
        VoiceUiState(prefs.enabled, prefs.volume, guide.status)
    )
    private var originJob: Job? = null
    private var destinationJob: Job? = null
    private var gpsJob: Job? = null
    private var planningJob: Job? = null

    /** Tick only expires previously measured values, never invents a new GPS fix. */
    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000L)
        }
    }
    private val cockpit = combine(
        sources, routes.currentRoute, ticker
    ) { source, route, now ->
        val usableGps = source.gps?.takeIf { it.usable(now) }
        val lastGpsAt = source.gps?.recordedAtMillis
            ?.takeIf { it <= now && it >= gpsWaitSinceMs } ?: gpsWaitSinceMs
        val telemetry = source.telemetry
        val energy = estimateEnergy(route, telemetry, now)
        val baseRoute = progress.enrich(route, usableGps, now)
        val visualRoute = if (energy is BaselineEnergyResult.Estimate) {
            baseRoute?.copy(estimatedArrivalSocPercent = energy.arrivalSocPercent)
        } else baseRoute?.copy(estimatedArrivalSocPercent = null)
        CockpitUiState(
            socPercent = telemetry.socPercent.current(now),
            speedKmh = telemetry.speedKmh.current(now),
            voltageV = telemetry.voltageV.current(now),
            batteryTemperatureC = telemetry.batteryTemperatureC.current(now),
            powerKw = telemetry.powerKw.current(now),
            chargingNeededEstimate = (energy as? BaselineEnergyResult.Estimate)?.chargingStopNeeded,
            chargingEnergyKwh = (energy as? BaselineEnergyResult.Estimate)?.requiredChargingEnergyKwh,
            energyEstimateStatus = (energy as? BaselineEnergyResult.Unavailable)?.reason,
            socSourceLabel = if (energy is BaselineEnergyResult.Estimate &&
                energy.usedLiveObd) "LIVE OBD · MODEL/ESTIMATE" else null,
            gpsWarning = if (_foreground.value &&
                now - lastGpsAt > 10_000L && usableGps == null
            ) "Waiting for GPS signal..." else null,
            route = visualRoute,
            gpsFix = usableGps
        )
    }
    /**
     * Fail-closed bridge to the tested energy estimator.
     * Calibration keys must be set from a separately validated configuration.
     * Unverified SoC/usable capacity never become made-up vehicle measurements.
     */
    private fun estimateEnergy(
        route: RouteSnapshot?,
        telemetry: VehicleTelemetry,
        now: Long
    ): BaselineEnergyResult? {
        if (route?.routeId == null) return null
        // Damaged/old preference types must not crash the navigation UI.
        fun readFloat(key: String): Double? = runCatching {
            if (modelPrefs.contains(key)) modelPrefs.getFloat(key, Float.NaN)
                .toDouble().takeIf { it.isFinite() } else null
        }.getOrNull()
        val capacity = readFloat("usableBatteryKwh") ?: Double.NaN
        val consumption = readFloat("referenceWhPerKm") ?: Double.NaN
        val reserve = readFloat("arrivalReservePct") ?: Double.NaN
        if (!capacity.isFinite() || capacity <= 0.0 ||
            !consumption.isFinite() || consumption <= 0.0 ||
            !reserve.isFinite() || reserve !in 0.0..100.0
        ) return BaselineEnergyResult.Unavailable("No calibrated capacity/consumption/reserve data")

        // No synthetic SoC or default 80% substitution.
        val fresh = telemetry.socPercent.current(now)
            ?: return BaselineEnergyResult.Unavailable("Waiting for real BMS SoC")
        val measuredAt = telemetry.socPercent.measuredAtMillis
            ?: return BaselineEnergyResult.Unavailable("No BMS timestamp")
        val soc = DepartureSoc.FreshObd(fresh, measuredAt)

        return EvBaselineEnergyEstimator.estimate(
            BaselineEnergyInputs(
                route = route, departureSoc = soc,
                usableBatteryKwh = capacity,
                consumptionWhPerKm = consumption,
                consumptionBasis = ConsumptionBasis.REFERENCE_MODEL,
                requiredArrivalSocPercent = reserve,
                nowMs = now,
                speedKmh = telemetry.speedKmh.current(now)
            )
        )
    }

    private val plannerState = combine(
        _planner, routes.options, routes.selectedIndex
    ) { state, options, index ->
        state.copy(options = options, selectedIndex = index)
    }
    // One projection: MapLibre, route dialog and progress panel observe the same
    // emission. No second independently collected routeState StateFlow.
    val uiState: StateFlow<NavigationUiState> = combine(
        cockpit, plannerState, _voice
    ) { cockpit, planner, voice ->
        val route = cockpit.route
        val routeState = NavigationRouteState(
            startLocation = planner.startLocation,
            startLabel = planner.originText.takeIf {
                planner.startLocation != null && it.isNotBlank()
            },
            destination = planner.destinationSelected,
            routeResult = route,
            calculating = planner.planning,
            error = planner.error
        )
        val phase = when {
            planner.planning -> RouteLoadStatus.Loading
            planner.error != null -> RouteLoadStatus.Error(planner.error)
            route?.routeId != null && cockpit.gpsWarning != null ->
                RouteLoadStatus.GpsLost
            route?.routeId != null -> RouteLoadStatus.Success(route.routeId)
            else -> RouteLoadStatus.Empty
        }
        NavigationUiState(cockpit, planner, voice, routeState, phase)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), NavigationUiState())

    init {
        viewModelScope.launch {
            snapshotFlow { guide.status to guide.lastError }.collect { (status, error) ->
                _voice.update { it.copy(status = status, error = error) }
            }
        }
        // Measured GPS drives both route progress and maneuver announcements.
        viewModelScope.launch {
            var activeSpeechRouteId: String? = null
            combine(
                routes.currentRoute, sources, _foreground,
                CarNavigationOwnership.navigating, _voice
            ) { route, source, foreground, carNavigating, voice ->
                SpeechInputs(route, source.gps, foreground && !carNavigating, voice)
            }.collect { current ->
                if (activeSpeechRouteId != current.route?.routeId) {
                    guide.stop()
                    speechBridge.reset()
                    activeSpeechRouteId = current.route?.routeId
                }
                if (!current.allowed || !current.voice.enabled ||
                    (current.voice.status != NativeVoiceGuide.Status.READY &&
                        current.voice.status != NativeVoiceGuide.Status.AUDIO_FOCUS_DENIED)
                ) {
                    guide.stop()
                } else {
                    speechBridge.evaluate(
                        current.route, current.fix, System.currentTimeMillis()
                    ).cue?.let { cue ->
                        val queued = guide.speak(
                            cue.message, current.voice.volume,
                            onUtteranceStarted = {
                                viewModelScope.launch {
                                    speechBridge.confirmStarted(
                                        cue.token, System.currentTimeMillis()
                                    )
                                }
                            },
                            onUtteranceFailed = {
                                viewModelScope.launch { speechBridge.cancel(cue.token) }
                            }
                        )
                        if (!queued) speechBridge.cancel(cue.token)
                    }
                }
            }
        }
    }

    private data class SpeechInputs(
        val route: RouteSnapshot?, val fix: GpsFix?,
        val allowed: Boolean, val voice: VoiceUiState
    )

    fun setForeground(isForeground: Boolean) {
        _foreground.value = isForeground
        if (isForeground) gpsWaitSinceMs = System.currentTimeMillis()
        if (!isForeground) {
            guide.stop()
            speechBridge.cancelPending()
            locationRepository.stop()
        }
    }
    fun startGps() {
        _gpsError.value = null
        locationRepository.start()
    }

    fun stopGps() = locationRepository.stop()

    /** Last known Fused fix, delivered only after the user grants runtime permission. */
    @Suppress("DEPRECATION")
    fun acceptLastLocation(location: android.location.Location?) {
        if (location == null || location.isFromMockProvider || !location.hasAccuracy()) {
            reportGpsError("Could not read location. Check GPS and precise location settings.")
            return
        }
        val fix = GpsFix(
            coordinate = GeoCoordinate(location.latitude, location.longitude),
            accuracyMeters = location.accuracy,
            recordedAtMillis = location.time,
            bearingDegrees = location.bearing.takeIf { location.hasBearing() && it.isFinite() },
            speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() && it.isFinite() }
        )
        if (fix.usable(System.currentTimeMillis())) {
            locationRepository.acceptFix(fix)
            _gpsError.value = null
        } else _gpsError.value = "Location is not sufficiently recent or accurate."

    }
    fun reportGpsError(message: String) {
        _gpsError.value = message
        _planner.update { it.copy(locatingGps = false, error = message) }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        prefs.enabled = enabled
        _voice.update { it.copy(enabled = enabled) }
        if (!enabled) guide.stop()
    }
    fun setVoiceVolume(volume: Float) {
        val safe = volume.coerceIn(0.15f, 1f)
        prefs.volume = safe
        _voice.update { it.copy(volume = safe) }
    }
    fun testVoice() {
        val voice = _voice.value
        if (_foreground.value && voice.enabled &&
            (voice.status == NativeVoiceGuide.Status.READY ||
                 voice.status == NativeVoiceGuide.Status.AUDIO_FOCUS_DENIED)) {
            guide.speak("EV Cockpit. English speech test.", voice.volume)
        }
    }

    fun editOrigin(value: String) {
        val text = value.take(100)
        originJob?.cancel(); gpsJob?.cancel(); planningJob?.cancel()
        routes.clear() // never show an obsolete route for a newly edited origin
        _planner.update { it.copy(originText = text, originSelected = null,
            originFromGps = false, gpsOrigin = null, locatingGps = false,
            originMatches = emptyList(), error = null, planning = false) }
        originJob = search(text, true)
    }
    fun editDestination(value: String) {
        val text = value.take(100)
        destinationJob?.cancel(); planningJob?.cancel()
        routes.clear()
        _planner.update { it.copy(destinationText = text, destinationSelected = null,
            destinationMatches = emptyList(), planning = false, error = null) }
        destinationJob = search(text, false)
    }
    private fun search(value: String, origin: Boolean): Job = viewModelScope.launch {
        if (value.trim().length < 3) {
            _planner.update { if (origin) it.copy(searchingOrigin = false)
                else it.copy(searchingDestination = false) }
            return@launch
        }
        delay(550L) // protect the Photon demo server
        _planner.update { if (origin) it.copy(searchingOrigin = true)
            else it.copy(searchingDestination = true) }
        try {
            val found = routes.searchResult(value).getOrThrow()
            val error = if (found.isEmpty()) "Address not found. Enter a more specific location." else null
            _planner.update { if (origin) it.copy(originMatches = found, searchingOrigin = false, error = error)
                else it.copy(destinationMatches = found, searchingDestination = false, error = error) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            val message = when (error) {
                is UnknownHostException -> "No internet connection"
                is SocketTimeoutException -> "Photon server timed out"
                is IOException -> error.message ?: "Could not reach the address service"
                else -> "Address search failed: ${error.message?.take(80) ?: "bilinmeyen hata"}"
            }
            _planner.update { if (origin) it.copy(searchingOrigin = false, error = message)
                else it.copy(searchingDestination = false, error = message) }
        }
    }
    fun selectOrigin(place: RoutePlace) {
        originJob?.cancel(); gpsJob?.cancel(); planningJob?.cancel(); routes.clear()
        _planner.update { it.copy(originText = place.label, originSelected = place,
            originFromGps = false, gpsOrigin = null, locatingGps = false,
            originMatches = emptyList(), error = null, planning = false) }
    }
    fun selectDestination(place: RoutePlace) {
        destinationJob?.cancel(); planningJob?.cancel(); routes.clear()
        _planner.update { it.copy(destinationText = place.label, destinationSelected = place,
            destinationMatches = emptyList(), error = null, planning = false) }
    }
    /** Activity first verifies fine/coarse runtime permission. */
    fun useCurrentGps() {
        gpsJob?.cancel(); originJob?.cancel(); planningJob?.cancel(); routes.clear()
        gpsJob = viewModelScope.launch {
            _planner.update { it.copy(locatingGps = true, originFromGps = false,
                gpsOrigin = null, originSelected = null, originMatches = emptyList(), error = null) }
            try {
                val cached = locationRepository.gpsFix.value
                    ?.takeIf { it.usable(System.currentTimeMillis()) && it.accuracyMeters <= 35f }
                    ?.coordinate
                val result = if (cached != null)
                    Result.success(cached to locationRepository.gpsFix.value)
                else currentLocation.getCurrentMeasurement()

                // A single fresh Fused measurement updates the planner origin
                // and the same GPS StateFlow observed by MapLibre and the cockpit.
                result.getOrNull()?.second?.let(locationRepository::acceptFix)
                _gpsError.value = result.exceptionOrNull()?.message
                _planner.update { state ->
                    if (result.isSuccess) state.copy(
                        locatingGps = false, originFromGps = true,
                        gpsOrigin = result.getOrNull()?.first,
                        originText = "My current GPS location", error = null
                    ) else state.copy(locatingGps = false, originFromGps = false,
                        error = result.exceptionOrNull()?.message ?: "Could not obtain GPS location")
                }
            } catch (cancel: CancellationException) { throw cancel }
        }
    }
    fun locationPermissionDenied() {
        _gpsError.value = "Location permission was not granted."
        gpsJob?.cancel()
        _planner.update { it.copy(locatingGps = false, originFromGps = false,
            gpsOrigin = null, error = "Location permission was not granted; you can select the origin manually") }
    }
    fun plan() {
        if (!_planner.value.canCalculate) return
        planningJob?.cancel()
        planningJob = viewModelScope.launch {
            val state = _planner.value
            val start = state.startLocation
            val target = state.destinationSelected
            if (start == null || target == null) {
                _planner.update { it.copy(error = "Origin and destination have not been selected") }
                return@launch
            }
            _planner.update { it.copy(planning = true, error = null) }
            routes.clear() // Don't retain a successful stale route during failed replanning.
            try {
                when (val result = routes.calculateResult(start, target).getOrThrow()) {
                    is RouteCalculationResult.Success ->
                        _planner.update { it.copy(planning = false, error = null) }
                    is RouteCalculationResult.Failure ->
                        _planner.update { it.copy(
                            planning = false,
                            error = when {
                                result.message.contains("internet", ignoreCase = true) ->
                                    "No Internet Connection"
                                else -> "Could Not Calculate Route (OSRM Error): " + result.message.take(90)
                            }
                        ) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                _planner.update { it.copy(planning = false,
                    error = "Route error: ${error.message?.take(100) ?: "beklenmeyen hata"}") }
            }
        }
    }
    fun selectAlternative(index: Int) { routes.choose(index); speechBridge.reset() }
    fun clearRoute() {
        originJob?.cancel(); destinationJob?.cancel(); gpsJob?.cancel(); planningJob?.cancel()
        routes.clear(); speechBridge.reset(); guide.stop(); _planner.value = RoutePlannerUiState()
    }
    override fun onCleared() {
        guide.close()
        locationRepository.stop()
        super.onCleared()
    }
}
````

## 2. `nativecockpit/src/main/java/com/evcockpit/presentation/cockpit/CockpitViewModel.kt`

````kotlin
package com.evcockpit.presentation.cockpit

import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.model.RouteSnapshot

/** Main MapLibre cockpit values originate from the central NavigationViewModel. */
data class CockpitUiState(
    val socPercent: Double? = null,
    val speedKmh: Double? = null,
    val voltageV: Double? = null,
    val batteryTemperatureC: Double? = null,
    val powerKw: Double? = null,
    val estimatedRangeKm: Double? = null,
    // MODEL/TAHMİN only; never present a missing OBD field as live.
    val chargingNeededEstimate: Boolean? = null,
    val chargingEnergyKwh: Double? = null,
    val energyEstimateStatus: String? = null,
    val socSourceLabel: String? = null,
    val gpsWarning: String? = null,
    val route: RouteSnapshot? = null,
    val gpsFix: GpsFix? = null
)
````

## 3. `nativecockpit/src/main/java/com/evcockpit/presentation/cockpit/CockpitScreen.kt`

````kotlin
package com.evcockpit.presentation.cockpit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.evcockpit.presentation.media.MediaLauncher
import com.evcockpit.presentation.media.MediaLaunchState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evcockpit.BuildConfig
import com.evcockpit.R
import com.evcockpit.domain.model.*
import com.evcockpit.domain.usecase.AutomaticChargePlan
import com.evcockpit.presentation.charging.ChargingUiState
import com.evcockpit.presentation.charging.RouteChargingUiState
import com.evcockpit.presentation.map.MapLibreCockpit
import com.evcockpit.presentation.media.ParkedStreamingDialog
import com.evcockpit.presentation.media.rememberPhoneMediaAllowed
import com.evcockpit.presentation.navigation.NavigationRouteState
import com.evcockpit.presentation.navigation.RouteLoadStatus
import com.evcockpit.presentation.obd.ObdSettingsDialog
import com.evcockpit.presentation.route.RoutePlannerDialog
import com.evcockpit.presentation.route.RoutePlannerUiState
import com.evcockpit.presentation.voice.NativeVoiceGuide
import com.evcockpit.presentation.voice.TtsPackageWarningDialog

/** One full-size map, with compact overlays for the essential driving information. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CockpitScreen(
    state: CockpitUiState,
    routeState: NavigationRouteState,
    loadStatus: RouteLoadStatus,
    obdConnection: ObdConnectionState,
    engineering: EngineeringSnapshot,
    engineeringMetrics: EngineeringMetrics,
    onObdRefresh: () -> Unit,
    onObdConnect: (String) -> Unit,
    onObdDisconnect: () -> Unit,
    onObdMebDiagnostic: (String) -> Unit,
    onObdClearDiagnostic: () -> Unit,
    routePlannerUi: RoutePlannerUiState,
    onRouteOriginChange:(String)->Unit,
    onRouteDestinationChange:(String)->Unit,
    onRouteOriginSelected:(RoutePlace)->Unit,
    onRouteDestinationSelected:(RoutePlace)->Unit,
    onRouteFromGps:()->Unit,
    onRoutePlan:()->Unit,
    onRouteAlternative:(Int)->Unit,
    onRouteClear:()->Unit,
    onGpsClick: () -> Unit,
    gpsError: String? = null,
    chargingUi: ChargingUiState,
    automaticChargePlan: RouteChargingUiState,
    weatherUi: RouteWeatherState,
    onStationFilter: (StationFilter) -> Unit,
    onMapViewport: (GeoViewport) -> Unit,
    onRefreshStations: () -> Unit,
    voiceEnabled: Boolean,
    onVoiceToggle: () -> Unit,
    voiceVolume: Float,
    onVoiceVolumeChange: (Float) -> Unit,
    voiceStatus: NativeVoiceGuide.Status,
    voiceError: String?,
    onVoiceTest: () -> Unit
) {
    val route = state.route ?: routeState.routeResult
    var routePlanner by remember { mutableStateOf(false) }
    var charging by remember { mutableStateOf(false) }
    var media by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var engineeringOpen by remember { mutableStateOf(false) }
    var voiceSettings by remember { mutableStateOf(false) }
    var station by remember { mutableStateOf<ChargingStation?>(null) }
    var missingVoice by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var mediaLaunch by remember { mutableStateOf<MediaLaunchState?>(null) }
    val phoneMediaAllowed = rememberPhoneMediaAllowed()
    val snackbar = remember { SnackbarHostState() }
    val error = (loadStatus as? RouteLoadStatus.Error)?.message
        ?: gpsError ?: voiceError ?: chargingUi.error
        ?: weatherUi.error?.takeIf { weatherUi.routeId == route?.routeId }

    LaunchedEffect(error) {
        if (!error.isNullOrBlank()) snackbar.showSnackbar(error.take(160))
    }
    LaunchedEffect(voiceStatus) {
        if (voiceStatus == NativeVoiceGuide.Status.TURKISH_VOICE_MISSING) missingVoice = true
    }

    if (routePlanner) RoutePlannerDialog(
        state = routePlannerUi, onDismiss = { routePlanner = false },
        onOriginChange = onRouteOriginChange, onDestinationChange = onRouteDestinationChange,
        onOriginSelected = onRouteOriginSelected, onDestinationSelected = onRouteDestinationSelected,
        onGps = onRouteFromGps, onPlan = onRoutePlan,
        onChooseAlternative = onRouteAlternative, onClear = onRouteClear
    )
    if (engineeringOpen) TechnicalAnalysisSheet(
        snapshot = engineering, metrics = engineeringMetrics,
        obdPhase = obdConnection.phase,
        onDismiss = { engineeringOpen = false }
    )
    if (settings) ObdSettingsDialog(
        state = obdConnection, onRefresh = onObdRefresh, onConnect = onObdConnect,
        onDisconnect = onObdDisconnect, onMebDiagnostic = onObdMebDiagnostic,
        onClearDiagnostic = onObdClearDiagnostic,
        onDismiss = { settings = false }
    )
    if (media) ParkedStreamingDialog(
        playbackAllowed = phoneMediaAllowed, onDismiss = { media = false },
        onLaunchMediaEngine = {
            mediaLaunch = MediaLauncher.launchMediaEngine(context)
            if (mediaLaunch is MediaLaunchState.Launched) media = false
        }
    )
    if (mediaLaunch == MediaLaunchState.NotInstalled || mediaLaunch == MediaLaunchState.Blocked) {
        AlertDialog(
            onDismissRequest = { mediaLaunch = null },
            containerColor = Navy,
            title = { Text(stringResource(R.string.media_engine_unavailable_title)) },
            text = { Text(stringResource(if (mediaLaunch == MediaLaunchState.NotInstalled)
                R.string.media_engine_not_installed else R.string.media_engine_blocked)) },
            confirmButton = {
                TextButton(onClick = { mediaLaunch = null }) {
                    Text(stringResource(R.string.native_close))
                }
            }
        )
    }
    if (missingVoice) TtsPackageWarningDialog(onDismiss = { missingVoice = false })
    if (voiceSettings) AlertDialog(
        onDismissRequest = { voiceSettings = false },
        containerColor = Navy,
        title = { Text(stringResource(R.string.native_voice_guidance)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(when (voiceStatus) {
                    NativeVoiceGuide.Status.LOADING -> R.string.native_preparing_english_speech
                    NativeVoiceGuide.Status.READY -> R.string.native_english_speech_ready
                    NativeVoiceGuide.Status.TURKISH_VOICE_MISSING -> R.string.native_english_tts_data_missing
                    NativeVoiceGuide.Status.INIT_FAILED -> R.string.native_could_not_initialize_the_speech_engine
                    NativeVoiceGuide.Status.AUDIO_FOCUS_DENIED -> R.string.native_could_not_obtain_audio_focus
                }))
                Text(stringResource(R.string.cockpit_voice_volume))
                Slider(
                    value = voiceVolume.takeIf { it.isFinite() }?.coerceIn(.15f, 1f) ?: 1f,
                    onValueChange = onVoiceVolumeChange, valueRange = .15f..1f
                )
                OutlinedButton(onClick = onVoiceTest, enabled = voiceEnabled &&
                    (voiceStatus == NativeVoiceGuide.Status.READY ||
                        voiceStatus == NativeVoiceGuide.Status.AUDIO_FOCUS_DENIED)) {
                    Text(stringResource(R.string.native_english_speech_test))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { voiceSettings = false }) { Text(stringResource(R.string.native_close)) }
        }
    )
    if (charging) ModalBottomSheet(
        onDismissRequest = { charging = false }, containerColor = Navy
    ) {
        ChargingPanel(
            ui = chargingUi, onFilter = onStationFilter, onRefresh = onRefreshStations,
            onStation = { station = it; charging = false }
        )
    }
    station?.let { selected ->
        val fallbackName = stringResource(R.string.cockpit_station_name)
        val fallbackOperator = stringResource(R.string.cockpit_operator_unknown)
        val fallbackAddress = stringResource(R.string.cockpit_address_unknown)
        val unknownValue = stringResource(R.string.cockpit_unknown_value)
        AlertDialog(
            onDismissRequest = { station = null }, containerColor = Navy,
            title = { Text(selected.name.ifBlank { fallbackName }) },
            text = {
                Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(selected.operator.ifBlank { fallbackOperator })
                    Text(selected.address.ifBlank { fallbackAddress })
                    selected.sockets.forEach { socket ->
                        Text(stringResource(R.string.cockpit_socket_summary,
                            socket.kind.toString(), socket.type.toString(),
                            socket.powerRaw.ifBlank { unknownValue }))
                    }
                    Text(stringResource(R.string.cockpit_source_verified, selected.sourceVerifiedAtUtc), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.native_live_availability_price_and_road_access_are_unknown))
                }
            },
            confirmButton = {
                TextButton(onClick = { station = null }) { Text(stringResource(R.string.native_close)) }
            }
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
        val landscape = maxWidth > maxHeight
        val short = maxHeight < 480.dp
        val compactBattery = landscape && short
        val batteryWidth = if (compactBattery) maxWidth * .49f
            else 256.dp.coerceAtMost(maxWidth * .64f)
        val arrivalWidth = if (landscape) 294.dp.coerceAtMost(maxWidth * .43f) else maxWidth - 32.dp

        MapLibreCockpit(
            gpsFix = state.gpsFix, routePoints = route?.routePoints.orEmpty(),
            stations = chargingUi.visible,
            plannedStopIds = (automaticChargePlan.plan as? AutomaticChargePlan.Estimate)
                ?.stops?.map { it.station.id }?.toSet().orEmpty(),
            weatherPoints = weatherUi.points.takeIf { weatherUi.routeId == route?.routeId }.orEmpty(),
            speedProfile = route?.profileSpeeds.orEmpty(),
            onViewportChanged = onMapViewport, onStationTapped = { station = it },
            modifier = Modifier.fillMaxSize()
        )
        if (obdConnection.phase != com.evcockpit.domain.model.ObdPhase.CONNECTED) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 90.dp),
                color = Navy.copy(alpha = .94f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "OBD Bağlantısı Bekleniyor",
                    color = SoftText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
        BatteryHud(
            state = state, compact = compactBattery,
            modifier = Modifier.align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start))
                .padding(start = 16.dp, top = 16.dp).width(batteryWidth)
        )
        Column(
            Modifier.align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                .padding(top = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            Surface(color = Navy.copy(alpha = .96f), shape = RoundedCornerShape(6.dp)) {
                if (landscape) Row(verticalAlignment = Alignment.CenterVertically) {
                    VoiceButton(onClick = { voiceSettings = true })
                    QuickActions(
                        gpsEnabled = true,
                        hasGpsFix = state.gpsFix != null,
                        voiceEnabled = voiceEnabled,
                        onGpsClick = onGpsClick,
                        onVoiceToggle = onVoiceToggle
                    )
                } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    VoiceButton(onClick = { voiceSettings = true })
                    QuickActions(
                        gpsEnabled = true,
                        hasGpsFix = state.gpsFix != null,
                        voiceEnabled = voiceEnabled,
                        onGpsClick = onGpsClick,
                        onVoiceToggle = onVoiceToggle
                    )
                }
            }
        }
        Column(
            Modifier.align(if (landscape) Alignment.TopEnd else Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(
                    if (landscape) WindowInsetsSides.Top + WindowInsetsSides.End
                    else WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                ))
                .padding(
                    start = if (landscape) 0.dp else 16.dp,
                    end = 16.dp,
                    top = if (landscape) 80.dp else 0.dp,
                    bottom = if (landscape) 0.dp else 112.dp
                ).width(arrivalWidth)
        ) {
            ArrivalHud(
                route = route, state = state, loading = loadStatus is RouteLoadStatus.Loading,
                compact = short, onOpen = { routePlanner = true }
            )
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding().padding(bottom = if (landscape) 80.dp else 260.dp)
        )
        CockpitDock(
            modifier = Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            onRoute = { routePlanner = true }, onCharging = { charging = true },
            onMedia = { media = true }, onEngineering = { engineeringOpen = true },
            onSettings = { settings = true; onObdRefresh() }
        )
    }
}

@Composable
private fun VoiceButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(stringResource(R.string.native_voice), color = NeonGreen)
    }
}

@Composable
private fun QuickActions(
    gpsEnabled: Boolean,
    hasGpsFix: Boolean,
    voiceEnabled: Boolean,
    onGpsClick: () -> Unit,
    onVoiceToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier) {
        IconButton(onClick = onGpsClick, enabled = gpsEnabled) {
            Icon(Icons.Default.GpsFixed,
                contentDescription = stringResource(R.string.cockpit_gps),
                tint = if (hasGpsFix) NeonGreen else SoftText)
        }
        IconButton(onClick = onVoiceToggle) {
            Icon(
                if (voiceEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                contentDescription = stringResource(if (voiceEnabled)
                    R.string.native_mute_voice_guidance else R.string.native_unmute_voice_guidance),
                tint = if (voiceEnabled) NeonGreen else SoftText
            )
        }
    }
}

@Composable
private fun BatteryHud(state: CockpitUiState, compact: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier, color = Navy.copy(alpha = .96f), shape = RoundedCornerShape(6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelLarge,
                color = SoftText, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            if (compact) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BatteryPercent(state.socPercent, true, Modifier.weight(.58f))
                    RangeValue(state.estimatedRangeKm, Modifier.weight(.42f))
                }
            } else {
                BatteryPercent(state.socPercent, false)
                RangeValue(state.estimatedRangeKm)
            }
            LinearProgressIndicator(
                progress = { state.socPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }
                    ?.div(100.0)?.toFloat() ?: 0f },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(3.dp),
                color = NeonGreen, trackColor = GlassLine
            )
            if (state.socPercent == null) Text(stringResource(R.string.cockpit_measurement_unavailable),
                style = MaterialTheme.typography.labelSmall, color = SoftText)
        }
    }
}

@Composable
private fun BatteryPercent(value: Double?, compact: Boolean, modifier: Modifier = Modifier) {
    val percentSymbol = stringResource(R.string.cockpit_percent_symbol)
    val unknownValue = stringResource(R.string.cockpit_unknown_value)
    val number = value?.takeIf { it.isFinite() && it in 0.0..100.0 }
        ?.let { CockpitFormat.percent(it).removeSuffix(percentSymbol) } ?: unknownValue
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(number,
            fontSize = if (compact) 72.sp else 96.sp,
            lineHeight = if (compact) 80.sp else 104.sp,
            fontWeight = FontWeight.Light, color = Color.White,
            maxLines = 1, softWrap = false)
        Text(percentSymbol, Modifier.padding(bottom = if (compact) 8.dp else 12.dp),
            fontSize = if (compact) 30.sp else 40.sp,
            lineHeight = if (compact) 36.sp else 46.sp, color = SoftText)
    }
}

@Composable
private fun RangeValue(km: Double?, modifier: Modifier = Modifier) {
    val unit = stringResource(R.string.cockpit_distance_unit)
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(CockpitFormat.distance(km).removeSuffix(" $unit"),
                Modifier.alignByBaseline(), fontSize = 39.sp, lineHeight = 46.sp,
                fontWeight = FontWeight.Light, color = NeonGreen, maxLines = 1, softWrap = false)
            Text(unit, Modifier.alignByBaseline(),
                style = MaterialTheme.typography.labelMedium, color = SoftText)
        }
        Text(stringResource(R.string.cockpit_estimated_range),
            style = MaterialTheme.typography.labelSmall, color = SoftText)
    }
}

@Composable
private fun ArrivalHud(route: RouteSnapshot?, state: CockpitUiState, loading: Boolean, compact: Boolean, onOpen: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        color = Navy.copy(alpha = .96f), shape = RoundedCornerShape(6.dp)
    ) {
        Column(Modifier.padding(if (compact) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
            Text(
                route?.nextTurnInstruction?.takeIf { it.isNotBlank() }
                    ?: stringResource(if (loading) R.string.native_calculating_route
                        else R.string.native_waiting_for_maneuver),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(CockpitFormat.eta(route?.estimatedArrivalEpochMs),
                        style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Text(stringResource(R.string.cockpit_arrival),
                        style = MaterialTheme.typography.labelMedium, color = SoftText)
                }
                Column(Modifier.weight(1f)) {
                    Text(CockpitFormat.percent(route?.estimatedArrivalSocPercent),
                        style = MaterialTheme.typography.headlineSmall, color = NeonGreen)
                    Text(stringResource(R.string.cockpit_arrival_soc),
                        style = MaterialTheme.typography.labelMedium, color = SoftText)
                }
            }
            if (!compact) Text(
                route?.destination?.takeIf { it.isNotBlank() } ?: stringResource(R.string.cockpit_plan_route),
                style = MaterialTheme.typography.bodySmall, color = SoftText,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            state.socSourceLabel?.let { source ->
                Text(source, style = MaterialTheme.typography.labelSmall, color = SoftText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CockpitDock(
    modifier: Modifier = Modifier,
    onRoute: () -> Unit, onCharging: () -> Unit, onMedia: () -> Unit,
    onEngineering: () -> Unit, onSettings: () -> Unit
) {
    Surface(modifier.fillMaxWidth(), color = Navy.copy(alpha = .98f), shape = RoundedCornerShape(6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            DockAction(Icons.Default.Map, stringResource(R.string.native_route), onRoute, Modifier.weight(1f))
            DockAction(Icons.Default.Bolt, stringResource(R.string.native_charging), onCharging, Modifier.weight(1f))
            DockAction(Icons.Default.SmartDisplay, stringResource(R.string.native_media), onMedia, Modifier.weight(1f))
            DockAction(Icons.Default.Analytics, "Hesaplar", onEngineering, Modifier.weight(1f))
            DockAction(Icons.Default.Settings, stringResource(R.string.native_settings), onSettings, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DockAction(icon: ImageVector, title: String, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier.heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(icon, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(20.dp))
        Text(title, color = SoftText, fontSize = 11.sp, lineHeight = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ChargingPanel(ui: ChargingUiState, onFilter: (StationFilter) -> Unit,
    onRefresh: () -> Unit, onStation: (ChargingStation) -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.native_epdk_station_filters), style = MaterialTheme.typography.headlineSmall)
        StationFilter.entries.forEach { filter ->
            val title = stringResource(when (filter) {
                StationFilter.ALL -> R.string.cockpit_filter_all
                StationFilter.DC -> R.string.cockpit_filter_dc
                StationFilter.ROUTE_CCS -> R.string.cockpit_filter_route_ccs
                StationFilter.ROUTE_DC -> R.string.cockpit_filter_route_dc
            })
            FilterChip(selected = ui.selectedFilter == filter,
                onClick = { onFilter(filter) }, label = { Text(title) },
                enabled = ui.routeProvided || filter == StationFilter.ALL || filter == StationFilter.DC)
        }
        TextButton(onClick = onRefresh) { Text(stringResource(R.string.native_refresh)) }
        if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        ui.error?.let { Text(it, color = SoftText) }
        Text(stringResource(R.string.cockpit_station_count, ui.visible.size, ui.total), style = MaterialTheme.typography.bodySmall)
        Text(ui.verifiedAtUtc ?: stringResource(R.string.cockpit_source_date_unknown), style = MaterialTheme.typography.bodySmall)
        ui.visible.take(100).forEach { candidate ->
            TextButton(onClick = { onStation(candidate) }, modifier = Modifier.fillMaxWidth()) {
                Text(candidate.name, modifier = Modifier.fillMaxWidth(), maxLines = 2)
            }
        }
    }
}
````

## 4. `nativecockpit/src/main/java/com/evcockpit/presentation/obd/ObdSettingsDialog.kt`

````kotlin
package com.evcockpit.presentation.obd

import androidx.compose.ui.res.stringResource
import com.evcockpit.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.evcockpit.domain.model.ObdConnectionState
import com.evcockpit.domain.model.ObdPhase
import com.evcockpit.domain.model.MebResponseStatus
import java.util.Locale

@Composable
fun ObdSettingsDialog(
    state: ObdConnectionState,
    onRefresh: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onMebDiagnostic: (String) -> Unit,
    onClearDiagnostic: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.90f).imePadding(),
            shape=MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.native_obd_connection_and_meb_diagnostics),style=MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.native_bluetooth_classic_spp_elm327_pair_the_adapter_in_android_bluetooth_set),
                    style=MaterialTheme.typography.bodySmall)
                Text("Durum: ${phaseLabel(state.phase)}")
                if (state.phase != ObdPhase.CONNECTED) {
                    Text("OBD Bağlantısı Bekleniyor", style=MaterialTheme.typography.bodyMedium)
                }
                state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                OutlinedButton(onClick=onRefresh, enabled=true, modifier=Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.native_refresh_paired_devices_request_permission))
                }
                if (state.devices.isEmpty()) Text(stringResource(R.string.native_no_paired_devices_this_screen_does_not_scan))
                state.devices.forEach { device ->
                    OutlinedButton(onClick={onConnect(device.address)},
                        enabled=state.phase != ObdPhase.CONNECTED && state.phase != ObdPhase.CONNECTING,
                        modifier=Modifier.fillMaxWidth()) { Text(device.name) }
                }
                if (state.phase == ObdPhase.CONNECTING) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.phase == ObdPhase.CONNECTED) {
                    Text(if (state.lastPollSucceeded) "Actual SAE 010D speed response received"
                         else "010D speed response not yet validated")
                    HorizontalDivider()
                    Text(stringResource(R.string.native_experimental_meb_ecu_readings),style=MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.native_test_only_while_parked_and_in_your_own_vehicle_queries_use_read_only_u) +
                        "Community PID sources have not been validated on your device; results are not applied to navigation SoC/SoH fields.",
                        style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick={ { onMebDiagnostic("22028C") } },
                        enabled=!state.diagnosticBusy,modifier=Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.native_read_bms_state_of_charge_22028c))
                    }
                    OutlinedButton(onClick={ { onMebDiagnostic("221E0E") } },
                        enabled=!state.diagnosticBusy,modifier=Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.native_maximum_battery_temperature_221e0e))
                    }
                    OutlinedButton(onClick={ { onMebDiagnostic("221E3D") } },
                        enabled=!state.diagnosticBusy,modifier=Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.native_battery_current_raw_response_only_221e3d))
                    }
                    if (state.diagnosticBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.diagnosticResult?.let { item ->
                        Text("Son sorgu: ${item.request} · ${diagnosticLabel(item.status)}",
                            style=MaterialTheme.typography.bodyMedium)
                        if (item.status == MebResponseStatus.VALID_RAW) {
                            item.candidateBmsSocPercent?.let { percent ->
                                Text(String.format(Locale.forLanguageTag("en-US"),
                                    "Candidate BMS SoC: %.1f %% (compare with the vehicle display)",percent))
                            }
                            item.candidateMaxBatteryTempC?.let { temp ->
                                Text(String.format(Locale.forLanguageTag("en-US"),
                                    "Candidate maximum battery temperature: %.2f °C",temp))
                            }
                            item.payloadHex?.let { Text("Raw payload: $it",
                                style=MaterialTheme.typography.bodySmall) }
                        } else {
                            Text(stringResource(R.string.native_no_validated_ecu_response_received_data_was_not_used),
                                style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick=onClearDiagnostic,modifier=Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.native_clear_diagnostic_result))
                    }
                    Button(onClick=onDisconnect,modifier=Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.native_disconnect_obd))
                    }
                }
                Text(stringResource(R.string.native_bms_soc_diagnostic_candidate_dashboard_soc_and_soh_are_different_quant) +
                     "Technical battery readings are shown separately under Hesaplar; they are not official SoH results.",
                     style=MaterialTheme.typography.bodySmall)
                TextButton(onClick=onDismiss,modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.native_back_to_map)) }
            }
        }
    }
}

private fun phaseLabel(phase: ObdPhase): String = when (phase) {
    ObdPhase.DISCONNECTED -> "Disconnected"
    ObdPhase.CONNECTING -> "Connecting"
    ObdPhase.CONNECTED -> "Connected"
    ObdPhase.ERROR -> "Error"
}

private fun diagnosticLabel(status: MebResponseStatus): String = when (status) {
    MebResponseStatus.VALID_RAW -> "Valid raw response"
    MebResponseStatus.NO_DATA -> "No data"
    MebResponseStatus.NEGATIVE -> "ECU rejected request"
    MebResponseStatus.WRONG_ECU -> "Unexpected ECU"
    MebResponseStatus.WRONG_IDENTIFIER -> "Unexpected data identifier"
    MebResponseStatus.INCOMPLETE -> "Incomplete response"
    MebResponseStatus.MALFORMED -> "Invalid response"
    MebResponseStatus.ADAPTER_ERROR -> "Adapter error"
}
````

## 5. `nativecockpit/src/main/java/com/evcockpit/MainActivity.kt`

````kotlin
package com.evcockpit

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.evcockpit.domain.repository.ObdConnectionController
import com.evcockpit.domain.repository.VehicleRepository
import com.evcockpit.domain.model.VehicleTelemetry
import com.evcockpit.data.settings.UserSettings
import com.evcockpit.data.settings.UserSettingsRepository
import com.evcockpit.presentation.settings.SettingsScreen
import com.evcockpit.presentation.settings.SettingsViewModel
import com.evcockpit.presentation.sound.ActiveSoundManager
import com.evcockpit.presentation.voice.AppAudioDucking
import com.evcockpit.presentation.voice.LocalAppAudioDucking
import com.evcockpit.domain.model.ObdPhase
import com.evcockpit.presentation.navigation.NavigationViewModel
import com.evcockpit.presentation.charging.ChargingViewModel
import com.evcockpit.presentation.charging.RouteChargePlanViewModel
import com.evcockpit.presentation.weather.RouteWeatherViewModel
import com.evcockpit.presentation.cockpit.CockpitScreen
import com.evcockpit.presentation.cockpit.CockpitTheme
import com.evcockpit.presentation.media.enterOwnVideoPipWhenParked

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val navigation: NavigationViewModel by viewModels()
    private val charging: ChargingViewModel by viewModels()
    private val chargePlan: RouteChargePlanViewModel by viewModels()
    private val weather: RouteWeatherViewModel by viewModels()
    @Inject lateinit var obd: ObdConnectionController
    @Inject lateinit var vehicleRepository: VehicleRepository
    @Inject lateinit var settingsRepository: UserSettingsRepository
    @Inject lateinit var activeSound: ActiveSoundManager
    @Inject lateinit var audioDucking: AppAudioDucking

    private data class SoundFrame(
        val settings: UserSettings,
        val speed: Double?,
        val sampleAt: Long?,
        val ducked: Boolean
    )

    private val bluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) obd.refreshPaired() else obd.permissionDenied()
    }

    private fun refreshObdDevices() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else obd.refreshPaired()
    }

    private fun connectObd(address: String) {
        try {
            obd.connect(address)
            if (obd.connection.value.phase != ObdPhase.CONNECTING &&
                obd.connection.value.phase != ObdPhase.CONNECTED) return
            // Explicit user action from a visible Activity; never auto-reconnect.
            ContextCompat.startForegroundService(
                this, Intent(this, ObdForegroundService::class.java)
            )
        } catch (_: Exception) {
            obd.disconnect() // Don't leave an unsupervised background socket.
            Toast.makeText(
                this, "OBD arka plan servisi başlatılamadı. Bağlantı kapatıldı.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun disconnectObd() {
        obd.disconnect()
        stopService(Intent(this, ObdForegroundService::class.java))
    }

    /**
     * For a future OWNED player Activity only; call after a trusted Park signal.
     * Third-party streaming packages own their own PiP and DRM surfaces.
     * Existing MapLibre and media app navigation are unaffected.
     */
    fun enterParkedOwnedVideoPip(
        verifiedParked: Boolean,
        ownVideoPlaying: Boolean
    ): Boolean = enterOwnVideoPipWhenParked(verifiedParked, ownVideoPlaying)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Observe real telemetry only while the phone UI is resumed.
        // Freshness check prevents stale/absent OBD readings producing sound.
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val ticker = flow {
                    while (true) {
                        emit(System.currentTimeMillis())
                        delay(1_000L)
                    }
                }
                combine(
                    settingsRepository.settings,
                    vehicleRepository.telemetry,
                    obd.connection,
                    audioDucking.ducked,
                    ticker
                ) { settings, telemetry, connection, ducked, now ->
                    SoundFrame(
                        settings = settings,
                        speed = if (connection.phase == ObdPhase.CONNECTED)
                            telemetry.speedKmh.current(now, 10_000L)
                        else null,
                        sampleAt = telemetry.speedKmh.measuredAtMillis,
                        ducked = ducked
                    )
                }.collect { frame ->
                    activeSound.setSpeechDucking(frame.ducked)
                    activeSound.update(frame.settings, frame.speed, frame.sampleAt, true)
                }
            }
        }

        setContent {
            val context = LocalContext.current
            val fused = remember(context) {
                LocationServices.getFusedLocationProviderClient(context.applicationContext)
            }
            var pendingRouteRequest by remember { mutableStateOf(false) }
            val locationManager = remember(context) {
                context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            }
            var fusedToken by remember { mutableStateOf<CancellationTokenSource?>(null) }
            DisposableEffect(fused) {
                onDispose { fusedToken?.cancel() }
            }
            // Route selection already has its own validated Fused request in ViewModel.
            // Map refresh uses an independent one-shot request, not stale lastLocation.
            @Suppress("MissingPermission")
            fun granted(forRoute: Boolean) {
                if (!locationManager.isLocationEnabled) {
                    navigation.reportGpsError("Location services are off. Enable location in your phone settings.")
                    return
                }
                navigation.startGps()
                if (forRoute) {
                    navigation.useCurrentGps()
                    return
                }
                val fine = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val request = CurrentLocationRequest.Builder()
                    .setPriority(if (fine) Priority.PRIORITY_HIGH_ACCURACY
                                 else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                    .setMaxUpdateAgeMillis(3_000L)
                    .setDurationMillis(15_000L)
                    .build()
                fusedToken?.cancel()
                val token = CancellationTokenSource()
                fusedToken = token
                try {
                    fused.getCurrentLocation(request, token.token)
                        .addOnSuccessListener { location ->
                            if (location == null) {
                                navigation.reportGpsError(
                                    "No current location found. Try again outdoors."
                                )
                            } else {
                                navigation.acceptLastLocation(location)
                            }
                        }
                        .addOnFailureListener { error ->
                            navigation.reportGpsError(
                                "Could not get location: " + (error.message ?: "GPS error")
                            )
                        }
                } catch (_: SecurityException) {
                    navigation.reportGpsError("Location permission is no longer valid. Grant it again.")
                }
            }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                val accepted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                if (accepted) granted(pendingRouteRequest)
                else {
                    if (pendingRouteRequest) navigation.locationPermissionDenied()
                    else navigation.reportGpsError(
                        "Location permission was not granted. Enable it in Settings."
                    )
                }
                pendingRouteRequest = false
            }
            val requestGps: (Boolean) -> Unit = { fromRoute ->
                val allowed = listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ).any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
                if (allowed) granted(fromRoute)
                else {
                    pendingRouteRequest = fromRoute
                    permissionLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ))
                }
            }
            val gpsError by navigation.gpsError.collectAsStateWithLifecycle()
            val ui by navigation.uiState.collectAsStateWithLifecycle()
            val stations by charging.uiState.collectAsStateWithLifecycle()
            val automaticChargePlan by chargePlan.uiState.collectAsStateWithLifecycle()
            val routeWeather by weather.uiState.collectAsStateWithLifecycle()
            val obdConnection by obd.connection.collectAsStateWithLifecycle()
            val engineering by obd.engineering.collectAsStateWithLifecycle()
            val engineeringMetrics by obd.engineeringMetrics.collectAsStateWithLifecycle()
            val soundSettings by settingsViewModel.settings.collectAsStateWithLifecycle()
            var audioSettingsOpen by rememberSaveable { mutableStateOf(false) }
            CompositionLocalProvider(LocalAppAudioDucking provides audioDucking) {
                CockpitTheme(nightMode = soundSettings.isNightModeEnabled) {
                    Box(Modifier.fillMaxSize()) {
                        CockpitScreen(
                    state = ui.cockpit,
                    routeState = ui.routeState,
                    loadStatus = ui.loadStatus,
                    routePlannerUi = ui.planner,
                    onRouteOriginChange = navigation::editOrigin,
                    onRouteDestinationChange = navigation::editDestination,
                    onRouteOriginSelected = navigation::selectOrigin,
                    onRouteDestinationSelected = navigation::selectDestination,
                    onRouteFromGps = { requestGps(true) },
                    onRoutePlan = navigation::plan,
                    onRouteAlternative = navigation::selectAlternative,
                    onRouteClear = navigation::clearRoute,
                    onGpsClick = { requestGps(false) },
                    gpsError = gpsError,
                    chargingUi = stations,
                    automaticChargePlan = automaticChargePlan,
                    weatherUi = routeWeather,
                    onStationFilter = charging::changeFilter,
                    onMapViewport = charging::viewportChanged,
                    onRefreshStations = charging::refresh,
                    obdConnection = obdConnection,
                    engineering = engineering,
                    engineeringMetrics = engineeringMetrics,
                    onObdRefresh = ::refreshObdDevices,
                    onObdConnect = ::connectObd,
                    onObdDisconnect = ::disconnectObd,
                    onObdMebDiagnostic = obd::requestMebDiagnostic,
                    onObdClearDiagnostic = obd::clearMebDiagnostic,
                    voiceEnabled = ui.voice.enabled,
                    onVoiceToggle = { navigation.setVoiceEnabled(!ui.voice.enabled) },
                    voiceVolume = ui.voice.volume,
                    onVoiceVolumeChange = navigation::setVoiceVolume,
                    voiceStatus = ui.voice.status,
                    voiceError = ui.voice.error,
                    onVoiceTest = navigation::testVoice
                )
                        // Separate settings affordance: original CockpitScreen untouched.
                        IconButton(
                            modifier = Modifier.align(Alignment.TopEnd)
                                .padding(top = 42.dp, end = 8.dp),
                            onClick = { audioSettingsOpen = true }
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = "Ses ayarları")
                        }
                        if (audioSettingsOpen) SettingsScreen(
                            viewModel = settingsViewModel,
                            activeSound = activeSound,
                            onClose = { audioSettingsOpen = false }
                        )
                    }
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        activeSound.onResume()
        navigation.setForeground(true)
        val allowed = listOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allowed) navigation.startGps()
    }
    override fun onPause() {
        activeSound.onPause()
        navigation.setForeground(false)
        // OBD is retained only while the user-started connectedDevice FGS runs.
        super.onPause()
    }
}
````

## 6. `nativecockpit/src/main/java/com/evcockpit/car/EyaCarNavigationScreen.kt`

````kotlin
package com.evcockpit.car

import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.model.RouteSnapshot
import com.evcockpit.domain.model.validRouteGeometry
import com.evcockpit.domain.repository.RouteRepository
import com.evcockpit.domain.usecase.ManeuverGuidancePolicy
import com.evcockpit.domain.usecase.RouteProgressTracker
import com.evcockpit.presentation.voice.NativeVoiceGuide
import com.evcockpit.presentation.voice.AppAudioDucking
import com.evcockpit.presentation.voice.VoicePreference
import kotlin.math.roundToInt

/** Real OSRM geometry + measured GPS only. Foreground Android Auto session, not a navigation FGS. */
class EyaCarNavigationScreen(
    context: CarContext,
    private val routes: RouteRepository,
    ducking: AppAudioDucking
) : Screen(context) {
    private val tracker = CarGpsTracker(context.applicationContext)
    private val surface = EyaCarMapSurface(context)
    private val manager = context.getCarService(NavigationManager::class.java)
    private val voice = NativeVoiceGuide(context.applicationContext, ducking)
    private val voicePrefs = VoicePreference(context.applicationContext)
    private val speechPolicy = ManeuverGuidancePolicy()
    private val progress = RouteProgressTracker()
    private val guidancePolicy = CarGuidancePolicy()
    private val gate = CarTripUpdateGate()
    private var work: CoroutineScope? = null
    private var route: RouteSnapshot? = routes.currentRoute.value
    private var fix: GpsFix? = null
    private var guidance: CarGuidance? = null
    private var summary = CarRouteSummary.from(route, null, System.currentTimeMillis())
    private var activeRouteId: String? = null
    private var withoutGpsSinceMs: Long = 0L
    private var closed = false

    private val callbacks = object : NavigationManagerCallback {
        override fun onStopNavigation() {
            // Host navigation ownership changed: immediately stop all trip IPC and voice.
            endNavigation()
        }
        override fun onAutoDriveEnabled() {
            // No mock GPS or Auto Drive mode is available in this build.
            Log.w("EYA-CAR", "Auto Drive request ignored: real location required")
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                manager.setNavigationManagerCallback(callbacks)
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(surface)
                tracker.start()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                work = scope
                val ticker = flow {
                    while (kotlin.coroutines.coroutineContext.isActive) {
                        emit(System.currentTimeMillis())
                        delay(1_000L)
                    }
                }
                scope.launch {
                    combine(routes.currentRoute, tracker.gpsFix, ticker) { r, f, now ->
                        Triple(r, f?.takeIf { it.usable(now) }, now)
                    }.collect { (r, f, now) ->
                        route = r
                        fix = f
                        surface.update(r, f)
                        val fresh = guidancePolicy.snapshot(r, f, now)
                        guidance = fresh
                        val newSummary = CarRouteSummary.from(r, f, now)
                        if (newSummary != summary) {
                            summary = newSummary
                            invalidate()
                        }
                        updateActiveNavigation(r, f, fresh, now)
                    }
                }
            }
            override fun onStop(owner: LifecycleOwner) {
                // No foreground service yet: stop rather than claim background navigation.
                endNavigation()
                work?.cancel(); work = null
                tracker.stop()
                surface.release()
                manager.clearNavigationManagerCallback()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                endNavigation()
                work?.cancel(); work = null
                tracker.stop()
                surface.release()
                if (!closed) { closed = true; voice.close() }
            }
        })
    }

    private fun beginNavigation() {
        if (activeRouteId != null) return
        val current = guidance ?: return // user must have a REAL route and a fresh GPS fix
        if (route?.routeId != current.routeId) return
        try {
            manager.navigationStarted()
            activeRouteId = current.routeId
            CarNavigationOwnership.setNavigating(true)
            withoutGpsSinceMs = 0L
            gate.clear()
            speechPolicy.reset()
            updateActiveNavigation(route, fix, current, System.currentTimeMillis())
            invalidate()
        } catch (e: RuntimeException) {
            Log.w("EyaCarNavigation", "Host refused navigation start", e)
            endNavigation()
        }
    }

    private fun endNavigation() {
        val wasActive = activeRouteId != null
        activeRouteId = null
        withoutGpsSinceMs = 0L
        CarNavigationOwnership.setNavigating(false)
        voice.stop()
        speechPolicy.reset()
        gate.clear()
        if (wasActive) {
            try { manager.navigationEnded() }
            catch (e: RuntimeException) { Log.w("EyaCarNavigation", "Host navigation end", e) }
            invalidate()
        }
    }

    private fun updateActiveNavigation(
        currentRoute: RouteSnapshot?, currentFix: GpsFix?,
        current: CarGuidance?, now: Long
    ) {
        val started = activeRouteId ?: return
        if (currentRoute?.routeId != started || !validRouteGeometry(currentRoute.routePoints)) {
            endNavigation() // new alternative means a new explicit trip
            return
        }
        if (current == null || current.routeId != started) {
            if (withoutGpsSinceMs == 0L) withoutGpsSinceMs = now
            voice.stop()
            if (now - withoutGpsSinceMs >= 15_000L) endNavigation()
            return // never publish stale maneuver or estimated distance
        }
        withoutGpsSinceMs = 0L
        if (gate.shouldPublish(current, now)) {
            try { manager.updateTrip(CarTripFactory.trip(current)) }
            catch (e: RuntimeException) { Log.w("EyaCarNavigation", "Trip update rejected", e) }
            invalidate()
        }
        if (voicePrefs.enabled && currentFix != null &&
            voice.status == NativeVoiceGuide.Status.READY) {
            val enriched = progress.enrich(currentRoute, currentFix, now)
            val announcement = speechPolicy.nextAnnouncement(enriched, currentFix, now)
            if (announcement != null) {
                // An enqueue is not proof that the speech engine started.
                val queued = voice.speak(
                    announcement, voicePrefs.volume,
                    onUtteranceStarted = {
                        // NativeVoiceGuide marshals this callback onto the main thread.
                        speechPolicy.confirmSpoken(System.currentTimeMillis())
                    },
                    onUtteranceFailed = { speechPolicy.cancelPending() }
                )
                if (!queued) speechPolicy.cancelPending()
            }
        } else if (!voicePrefs.enabled) voice.stop()
    }

    override fun onGetTemplate(): Template {
        val active = activeRouteId != null
        val g = guidance?.takeIf { it.routeId == activeRouteId }
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle(summary.title).addText(summary.detail).build())
            .addRow(Row.Builder().setTitle("GPS").addText(summary.locationStatus).build())

        if (active) {
            val step = g?.nextCue
            val meters = g?.nextMeters
            pane.addRow(Row.Builder().setTitle("Next maneuver")
                .addText(if (step != null && meters != null) {
                    "$step · ${meters.roundToInt()} m"
                } else "Waiting for current GPS and maneuver data").build())
            pane.addRow(Row.Builder().setTitle("End navigation")
                .setOnClickListener { endNavigation() }.build())
        } else if (guidance != null) {
            pane.addRow(Row.Builder().setTitle("Start navigation")
                .setOnClickListener { beginNavigation() }.build())
        } else {
            pane.addRow(Row.Builder().setTitle("To get started")
                .addText("Select a route and grant location permission on the phone").build())
        }
        val content = PaneTemplate.Builder(pane.build())
        if (carContext.carAppApiLevel < 7) {
            @Suppress("DEPRECATION")
            return content.setTitle("EV Cockpit").build()
        }
        return MapWithContentTemplate.Builder()
            .setContentTemplate(content.setHeader(
                Header.Builder().setTitle("EV Cockpit").build()
            ).build())
            .build()
    }
}
````

## 7. `nativecockpit/src/main/java/com/evcockpit/data/repository/Elm327VehicleRepository.kt`

````kotlin
package com.evcockpit.data.repository

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.evcockpit.data.obd.ElmSpeedParser
import com.evcockpit.data.obd.MebUdsParser
import com.evcockpit.domain.model.EngineeringMath
import com.evcockpit.domain.model.EngineeringMetrics
import com.evcockpit.domain.model.EngineeringSnapshot
import com.evcockpit.domain.model.MebResponseStatus
import com.evcockpit.domain.model.ObdConnectionState
import com.evcockpit.domain.model.ObdPhase
import com.evcockpit.domain.model.PairedObdDevice
import com.evcockpit.domain.model.TimedValue
import com.evcockpit.domain.model.VehicleTelemetry
import com.evcockpit.domain.repository.ObdConnectionController
import com.evcockpit.domain.repository.ObdTransport
import com.evcockpit.domain.usecase.Elm327Session
import com.evcockpit.domain.usecase.ElmSessionPhase
import com.evcockpit.domain.repository.VehicleRepository

/**
 * Bluetooth Classic ELM327 SPP. All traffic is serialized in one worker:
 * - standard 010D speed polling when the ECU supports it;
 * - explicit, one-shot, read-only UDS 22 queries from a strict allowlist.
 * ELM adapter settings are restored after diagnostics; no VIN, no writes,
 * no unverified battery values flowing into cockpit telemetry.
 */
@Singleton
class Elm327VehicleRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : VehicleRepository, ObdConnectionController {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _telemetry = MutableStateFlow(VehicleTelemetry())
    override val telemetry: StateFlow<VehicleTelemetry> = _telemetry.asStateFlow()
    private val _connection = MutableStateFlow(ObdConnectionState())
    override val connection: StateFlow<ObdConnectionState> = _connection.asStateFlow()
    private val _engineering = MutableStateFlow(EngineeringSnapshot())
    override val engineering: StateFlow<EngineeringSnapshot> = _engineering.asStateFlow()
    private val _metrics = MutableStateFlow(EngineeringMetrics())
    override val engineeringMetrics: StateFlow<EngineeringMetrics> = _metrics.asStateFlow()

    private fun recalculate() {
        _metrics.value = EngineeringMath.calculate(_engineering.value, System.currentTimeMillis())
    }
    private val diagnosticRequests = Channel<String>(capacity=1)
    private var task: Job? = null
    @Volatile private var socket: BluetoothSocket? = null
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private fun hasPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override fun refreshPaired() {
        if (!hasPermission()) { permissionDenied(); return }
        try {
            val found = adapter?.bondedDevices.orEmpty().mapNotNull { device ->
                val address = device.address.orEmpty()
                if (!BluetoothAdapter.checkBluetoothAddress(address)) return@mapNotNull null
                PairedObdDevice(device.name?.take(60) ?: "Paired Bluetooth device", address)
            }.sortedBy { it.name.lowercase() }
            _connection.value = _connection.value.copy(devices=found,
                error=if (adapter == null) "Bluetooth hardware was not found" else null)
        } catch (_: SecurityException) { permissionDenied() }
    }

    override fun permissionDenied() {
        _connection.value = _connection.value.copy(error="Bluetooth connection permission is required")
    }

    @SuppressLint("MissingPermission")
    override fun connect(address: String) {
        if (_connection.value.phase == ObdPhase.CONNECTING ||
            _connection.value.phase == ObdPhase.CONNECTED) return
        if (!hasPermission()) { permissionDenied(); return }
        refreshPaired()
        val device = try { adapter?.bondedDevices?.firstOrNull { it.address == address } }
            catch (_: SecurityException) { null }
        if (device == null) {
            _connection.value = _connection.value.copy(error="Pair the adapter in Android Bluetooth settings first")
            return
        }
        task?.cancel()
        task = scope.launch {
            _connection.value = _connection.value.copy(phase=ObdPhase.CONNECTING,
                selectedDevice=address,error=null,lastSpeedAtMs=null,
                lastPollSucceeded=false,diagnosticBusy=false,diagnosticResult=null)
            _telemetry.value = VehicleTelemetry()
            _engineering.value = EngineeringSnapshot()
            _metrics.value = EngineeringMetrics()
            try {
                withContext(Dispatchers.IO) { runSession(device) }
            } catch (_: CancellationException) {
                // disconnect() closes socket to unblock Bluetooth reads.
            } catch (e: Exception) {
                _connection.value = _connection.value.copy(phase=ObdPhase.ERROR,
                    diagnosticBusy=false,
                    error=when(e) {
                        is SecurityException -> "Bluetooth permission denied"
                        is IOException -> "ELM327 connection/response failed; check the adapter"
                        else -> "Could not connect to ELM327"
                    })
            } finally {
                socket?.closeSilently();socket=null
                _telemetry.value=VehicleTelemetry()
                _engineering.value=EngineeringSnapshot()
                _metrics.value=EngineeringMetrics()
                if (_connection.value.phase != ObdPhase.ERROR)
                    _connection.value=_connection.value.copy(phase=ObdPhase.DISCONNECTED,
                        diagnosticBusy=false,diagnosticResult=null)
            }
        }
    }

    override fun requestMebDiagnostic(command: String) {
        if (command !in MebUdsParser.ALLOWED_REQUESTS) return
        if (_connection.value.phase != ObdPhase.CONNECTED || _connection.value.diagnosticBusy) return
        _connection.value=_connection.value.copy(diagnosticBusy=true,diagnosticResult=null,error=null)
        if (diagnosticRequests.trySend(command).isFailure) {
            _connection.value=_connection.value.copy(diagnosticBusy=false,
                error="The OBD diagnostic queue is busy")
        }
    }

    override fun clearMebDiagnostic() {
        _connection.value=_connection.value.copy(diagnosticResult=null)
    }

    @SuppressLint("MissingPermission")
    private suspend fun runSession(device: BluetoothDevice) {
        val conn=device.createRfcommSocketToServiceRecord(spp)
        socket=conn
        val watchdog=scope.launch {
            delay(12_000L)
            if (!conn.isConnected) conn.closeSilently()
        }
        try { conn.connect() } finally { watchdog.cancel() }
        if (!conn.isConnected) throw IOException("SPP connection unavailable")
        // Adapt the same physical RFCOMM socket to the tested Elm327Session.
        // The single runSession worker still owns all CAN header/filter changes.
        val transport = object : ObdTransport {
            override val connected: Boolean get() = conn.isConnected
            override suspend fun connect(timeoutMillis: Long) {
                if (!conn.isConnected) throw IOException("SPP socket is no longer connected")
            }
            override suspend fun exchange(command: String, timeoutMillis: Long): String =
                sendAndRead(conn, command)
            override fun disconnect() { conn.closeSilently() }
        }
        val protocol = Elm327Session(transport)
        try {
            protocol.initialize() // ATZ, ATE0, ATL0; READY only on real response.
            if (protocol.state.value.phase != ElmSessionPhase.READY) {
                throw IOException("ELM327 initialization was not acknowledged")
            }
            for (command in listOf("ATS0","ATH0","ATSP0"))
                sendAndRead(conn, command)
            _connection.value = _connection.value.copy(phase=ObdPhase.CONNECTED, error=null)
        // Background-in-app polling starts immediately after explicit pairing.
        // A single worker owns ALL ELM I/O: no parallel CAN commands or write DIDs.
        val cycle = listOf(
            "22028C", "221E3B", "221E3D", "221E0E", "221E0F",
            "221E33", "221E34", "221E1B", "221E32", "222AB2"
        )
        var cursor = 0
        while (kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive == true) {
            val manual = diagnosticRequests.tryReceive().getOrNull()
            val command = manual ?: cycle[cursor++ % cycle.size]
            try {
                val response = runMebReadOnlyQuery(conn, protocol, command)
                val parsed = MebUdsParser.parse(command, response, System.currentTimeMillis())
                if (parsed.status == MebResponseStatus.VALID_RAW) {
                    _engineering.value = EngineeringMath.update(
                        _engineering.value, command, parsed.payloadHex, parsed.receivedAtMillis
                    )
                } else {
                    _engineering.value = _engineering.value.copy(
                        unsuccessfulReads = _engineering.value.unsuccessfulReads + 1
                    )
                }
                recalculate()
                if (manual != null) {
                    _connection.value = _connection.value.copy(
                        diagnosticBusy = false, diagnosticResult = parsed, error = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _engineering.value = _engineering.value.copy(
                    unsuccessfulReads = _engineering.value.unsuccessfulReads + 1
                )
                if (manual != null) _connection.value = _connection.value.copy(
                    diagnosticBusy = false,
                    error = "MEB read failed; inspect adapter CAN protocol support"
                )
                // A rejected, unsupported or timed-out DID is not evidence of an
                // ECU defect; continue with other safe reads.
            }

            try {
                val speed = ElmSpeedParser.decode(protocol.readRawOnce("010D"))
                if (speed != null) {
                    val now = System.currentTimeMillis()
                    _telemetry.value = VehicleTelemetry(speedKmh = TimedValue(speed, now))
                    _engineering.value = _engineering.value.copy(speedKmh = TimedValue(speed, now))
                    recalculate()
                    _connection.value = _connection.value.copy(
                        lastPollSucceeded = true, lastSpeedAtMs = now
                    )
                } else {
                    _telemetry.value = VehicleTelemetry()
                    _connection.value = _connection.value.copy(lastPollSucceeded = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _telemetry.value = VehicleTelemetry()
                _connection.value = _connection.value.copy(lastPollSucceeded = false)
            }
            delay(700L)
        }
        } finally {
            protocol.close() // Closes only the socket owned by this session.
        }
    }

    private suspend fun runMebReadOnlyQuery(
        conn: BluetoothSocket, protocol: Elm327Session, command: String
    ): String {
        // ELM: battery ECU 29-bit/500kbps; 2AB2 capacity is 11-bit 710/77A.
        // UDS service 22 READ ONLY; this function never sends service 2E/31/14.
        var initiated=false
        try {
            val capacityRead = command == "222AB2"
            // Match CAN settings actually observed in this vehicle's Car Scanner
            // session. Older ELM firmware requires 6-digit ATSH + ATCP17.
            val configuration = if (capacityRead) listOf(
                "ATH1", "ATSP6", "ATCAF1", "ATCFC1",
                "ATSH710", "ATCRA77A", "ATFCSH710",
                "ATFCSD300000", "ATFCSM1"
            ) else listOf(
                "ATH1", "ATSP7", "ATCP17", "ATCAF1", "ATCFC1",
                "ATSHFC007B", "ATCRA17FE007B", "ATFCSH17FC007B",
                "ATFCSD300000", "ATFCSM1"
            )
            for (adapterCommand in configuration) {
                val reply=sendAndRead(conn,adapterCommand)
                if (reply.contains('?') || reply.uppercase().contains("ERROR"))
                    throw IOException("ELM protocol setting unsupported")
            }
            initiated=true
            return protocol.readRawOnce(command)
        } finally {
            if (initiated || conn.isConnected) {
                // Drop restrictive CAN RX filter / custom ISO-TP flow control
                // before trying generic SAE 010D across any 11-bit ECU.
                for (reset in listOf("ATFCSM0", "ATCRA", "ATSP0", "ATH0")) {
                    try { sendAndRead(conn, reset) }
                    catch (_: Exception) { /* fail closed on the next read */ }
                }
            }
        }
    }

    private suspend fun sendAndRead(conn:BluetoothSocket,command:String):String {
        conn.outputStream.write((command+"\r").toByteArray(Charsets.US_ASCII))
        conn.outputStream.flush()
        return withTimeout(6_000L) {
            val input=conn.inputStream
            val builder=StringBuilder()
            val chunk=ByteArray(256)
            while (!builder.contains('>')) {
                val ready=input.available()
                if (ready>0) {
                    val count=input.read(chunk,0,minOf(chunk.size,ready))
                    if (count<0) throw IOException("ELM327 connection closed")
                    builder.append(String(chunk,0,count,Charsets.US_ASCII))
                    if (builder.length>4096) throw IOException("ELM327 reply oversized")
                } else delay(35)
            }
            builder.toString()
        }
    }

    override fun disconnect() {
        task?.cancel();task=null
        socket?.closeSilently();socket=null
        while (diagnosticRequests.tryReceive().isSuccess) { /* discard pending reads */ }
        _telemetry.value=VehicleTelemetry()
        _engineering.value=EngineeringSnapshot()
        _metrics.value=EngineeringMetrics()
        _connection.value=_connection.value.copy(phase=ObdPhase.DISCONNECTED,
            selectedDevice=null,lastPollSucceeded=false,lastSpeedAtMs=null,
            diagnosticBusy=false,diagnosticResult=null,error=null)
    }

    private fun BluetoothSocket.closeSilently() { try { close() } catch (_:Exception) { } }
}
````

## 8. `nativecockpit/src/main/java/com/evcockpit/domain/usecase/Elm327Session.kt`

````kotlin
package com.evcockpit.domain.usecase

import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.evcockpit.domain.repository.ObdTransport

enum class ElmSessionPhase { DISCONNECTED, CONNECTING, READY, ERROR }

data class ElmSessionState(
    val phase: ElmSessionPhase = ElmSessionPhase.DISCONNECTED,
    val error: String? = null
)

/**
 * Coroutine-safe serial ELM command queue: Mutex is shared by AT initialization
 * and read requests. close() is deliberately non-blocking: socket.close()/GATT
 * close() must interrupt an in-flight exchange, even if it holds the mutex.
 *
 * close() means "disconnect", not "dispose": the same instance may initialize
 * again. Generation tokens reject callbacks/results from an earlier connection.
 */
class Elm327Session(private val transport: ObdTransport) {
    private val commands = Mutex()
    private val _state = MutableStateFlow(ElmSessionState())
    val state: StateFlow<ElmSessionState> = _state.asStateFlow()
    @Volatile private var generation = 0L

    suspend fun initialize(): String = commands.withLock {
        val token = generation
        try {
            startSession(token)
        } catch (cancel: CancellationException) {
            transport.disconnect()
            if (token == generation) _state.value = ElmSessionState()
            throw cancel
        } catch (error: Exception) {
            transport.disconnect()
            if (token == generation) {
                _state.value = ElmSessionState(
                    ElmSessionPhase.ERROR,
                    error.message ?: "Could not initialize ELM327"
                )
            }
            throw error
        }
    }

    suspend fun readOnly(command: String): String = commands.withLock {
        val query = command.trim().uppercase(Locale.ROOT).replace(" ", "")
        require(query.matches(Regex("(?:01|09)[0-9A-F]{2,6}|22[0-9A-F]{4}"))) {
            "Only permitted read-only queries are allowed"
        }

        val token = generation
        var last: Exception? = null
        repeat(3) { attempt ->
            ensureCurrent(token)
            try {
                if (_state.value.phase != ElmSessionPhase.READY || !transport.connected) {
                    startSession(token)
                }
                val result = transport.exchange(query, 3_000L)
                ensureCurrent(token)
                if (result.isBlank() || result.contains("NO DATA", true) ||
                    result.contains("ERROR", true) || result.contains("?", true)
                ) throw IOException("OBD did not return a valid response")
                return@withLock result
            } catch (cancel: CancellationException) {
                transport.disconnect()
                if (token == generation) _state.value = ElmSessionState()
                if (cancel !is TimeoutCancellationException) throw cancel
                last = cancel
            } catch (error: Exception) {
                transport.disconnect()
                last = error
                if (error is SecurityException) {
                    _state.value = ElmSessionState(ElmSessionPhase.ERROR, "Bluetooth permission is missing")
                    throw error
                }
            }
            ensureCurrent(token)
            if (attempt < 2) {
                _state.value = ElmSessionState(ElmSessionPhase.CONNECTING)
                delay(400L * (1 shl attempt))
            }
        }
        val error = IOException("Could not read OBD after three attempts", last)
        if (token == generation) {
            _state.value = ElmSessionState(ElmSessionPhase.ERROR, error.message)
        }
        throw error
    }

    /**
     * One tolerant read for an already-connected vehicle session.
     * A genuine ECU may reply NO DATA to an unsupported PID. Return that
     * response to the parser without fabricating values or reconnecting.
     * The caller owns the single CAN configuration/polling coroutine.
     */
    suspend fun readRawOnce(command: String): String = commands.withLock {
        val query = command.trim().uppercase(Locale.ROOT).replace(" ", "")
        require(query.matches(Regex("(?:01|09)[0-9A-F]{2,6}|22[0-9A-F]{4}"))) {
            "Only read-only PID and UDS requests are allowed"
        }
        val token = generation
        ensureCurrent(token)
        check(_state.value.phase == ElmSessionPhase.READY && transport.connected) {
            "ELM327 session is not ready"
        }
        val reply = transport.exchange(query, 6_000L)
        ensureCurrent(token)
        reply // NO DATA is a real ECU reply; caller decides whether it is useful.
    }

    private suspend fun startSession(token: Long): String {
        ensureCurrent(token)
        _state.value = ElmSessionState(ElmSessionPhase.CONNECTING)
        if (!transport.connected) transport.connect(12_000L)
        ensureCurrent(token)

        return try {
            var identity = ""
            // Serialized coroutine command queue: never overlap AT and PID writes.
            for (cmd in listOf("ATZ", "ATE0", "ATL0")) {
                val reply = transport.exchange(cmd, if (cmd == "ATZ") 6_000L else 3_000L)
                ensureCurrent(token)
                if (reply.isBlank() || reply.contains("ERROR", true) || reply.contains("?"))
                    throw IOException("ELM327 initialization command failed: $cmd")
                if (cmd == "ATZ") identity = reply
            }
            _state.value = ElmSessionState(ElmSessionPhase.READY)
            identity
        } catch (error: Exception) {
            transport.disconnect()
            throw error
        }
    }

    private fun ensureCurrent(token: Long) {
        if (token != generation) throw IOException("OBD session closed")
    }

    @Synchronized
    fun close() {
        ++generation // Invalidates in-flight commands before the socket is closed.
        _state.value = ElmSessionState()
        transport.disconnect() // SPP read and BLE await are unblocked by transport.
    }
}
````

## 9. `nativecockpit/src/main/java/com/evcockpit/presentation/sound/ActiveSoundManager.kt`

````kotlin
package com.evcockpit.presentation.sound

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.PlaybackException
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.evcockpit.R
import com.evcockpit.data.settings.UserSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class EngineProfile(val label: String, val idle: Int, val accel: Int) {
    V8("V8", R.raw.v8_idle, R.raw.v8_accel),
    F1("F1", R.raw.f1_idle, R.raw.f1_accel),
    Futuristic("Futuristic", R.raw.futuristic_idle, R.raw.futuristic_accel),
    Turbine("Turbine", R.raw.turbine_idle, R.raw.turbine_accel),
    Stealth("Stealth", R.raw.stealth_idle, R.raw.stealth_accel);

    companion object {
        fun from(value: String): EngineProfile = entries.firstOrNull {
            it.name == value
        } ?: V8
    }
}

/**
 * In-cabin only synthetic audio. Never replace the vehicle's external AVAS.
 * Mutate ExoPlayer from the main thread; no music library or real engine sample.
 * A real fresh OBD speed is required (no speed => automatic silence).
 */
@Singleton
class ActiveSoundManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val main = Handler(Looper.getMainLooper())
    private var idle: ExoPlayer? = null
    private var acceleration: ExoPlayer? = null
    private var currentProfile: EngineProfile? = null
    private var lastSpeedKmh: Double? = null
    private var lastSpeedAtMs: Long? = null
    private var enabled = false
    private var volume = 1f
    private var ducked = false
    private var previewing = false
    private var previewToken = 0L
    private var activeInForeground = false
    private var lastSpeedInput: Double? = null
    private var lastInputTime: Long? = null

    private fun player(resourceId: Int): ExoPlayer? {
        var candidate: ExoPlayer? = null
        return try {
            // Resource existence is checked before Media3 starts asynchronous loading.
            context.resources.openRawResource(resourceId).use { /* readable */ }
            candidate = ExoPlayer.Builder(context).build()
            candidate.apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                    false
                )
                setHandleAudioBecomingNoisy(true)
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("EYA-ActiveSound", "Engine audio decoder/source unavailable", error)
                        pausePlayers()
                    }
                })
                setMediaItem(MediaItem.fromUri(
                    Uri.parse("android.resource://${context.packageName}/$resourceId")
                ))
                repeatMode = Player.REPEAT_MODE_ONE
                prepare()
            }
        } catch (error: Exception) {
            Log.e("EYA-ActiveSound", "Engine WAV could not be loaded", error)
            runCatching { candidate?.release() }
            null
        }
    }

    private fun selectProfile(profile: EngineProfile): Boolean {
        if (profile == currentProfile && idle != null && acceleration != null) return true
        runCatching { idle?.release() }
        runCatching { acceleration?.release() }
        idle = player(profile.idle)
        acceleration = player(profile.accel)
        if (idle == null || acceleration == null) {
            runCatching { idle?.release() }
            runCatching { acceleration?.release() }
            idle = null
            acceleration = null
            currentProfile = null
            return false // Missing assets fail closed; never crash the UI.
        }
        currentProfile = profile
        return true
    }

    /** Call at most once per real OBD sample; time is the sample timestamp. */
    fun update(
        settings: UserSettings,
        speedKmh: Double?,
        speedAtMs: Long?,
        foreground: Boolean
    ) {
        main.post {
            activeInForeground = foreground
            enabled = settings.isEngineSoundEnabled
            volume = settings.engineVolume.coerceIn(0f, 1.5f)
            lastSpeedInput = speedKmh
            lastInputTime = speedAtMs
            if (!foreground || !enabled || previewing ||
                speedKmh == null || speedAtMs == null) {
                if (!previewing) pausePlayers()
                return@post
            }
            if (!selectProfile(EngineProfile.from(settings.engineSoundProfile))) return@post
            if (lastSpeedAtMs == speedAtMs) {
                // Poll tick without a new CAN measurement: do not reset accel mode.
                applyVolumes()
                return@post
            }
            val deltaSeconds =
                lastSpeedAtMs?.let { (speedAtMs - it).toDouble() / 1000.0 } ?: 0.0
            val decision = EngineMotionPolicy.decide(
                speedKmh, lastSpeedKmh, deltaSeconds
            )
            lastSpeedKmh = speedKmh
            lastSpeedAtMs = speedAtMs
            runCatching {
                acceleration?.playbackParameters = PlaybackParameters(1f, decision.pitch)
                idle?.playbackParameters = PlaybackParameters(1f, 0.95f)
                when (decision.mode) {
                    EngineMotionPolicy.Mode.ACCELERATING -> {
                        idle?.pause()
                        acceleration?.play()
                    }
                    EngineMotionPolicy.Mode.IDLE -> {
                        acceleration?.pause()
                        idle?.play()
                    }
                    EngineMotionPolicy.Mode.SILENT -> pausePlayers()
                }
                applyVolumes()
            }.onFailure { error ->
                Log.e("EYA-ActiveSound", "Playback update failed", error)
                pausePlayers()
            }
        }
    }

    fun setSpeechDucking(active: Boolean) {
        main.post {
            ducked = active
            applyVolumes()
        }
    }

    private fun applyVolumes() {
        // Media3 player's volume is 0..1. UI 150% means max permitted app gain,
        // NOT boosting above device/system maximum (which risks clipping).
        val normalized = (volume / 1.5f).coerceIn(0f, 1f)
        val multiplier = if (ducked) 0.2f else 1f
        idle?.volume = normalized * multiplier * 0.75f
        acceleration?.volume = normalized * multiplier
    }

    fun preview(settings: UserSettings) {
        main.post {
            if (!activeInForeground) return@post
            previewToken++
            val token = previewToken
            previewing = true
            volume = settings.engineVolume.coerceIn(0f, 1.5f)
            if (!selectProfile(EngineProfile.from(settings.engineSoundProfile))) {
                previewing = false
                return@post
            }
            runCatching {
                idle?.pause()
                acceleration?.playbackParameters = PlaybackParameters(1f, 1.25f)
                acceleration?.play()
                applyVolumes()
            }.onFailure { error ->
                Log.e("EYA-ActiveSound", "Preview playback failed", error)
                previewing = false
                pausePlayers()
                return@post
            }
            main.postDelayed({
                if (previewToken == token) {
                    previewing = false
                    pausePlayers()
                }
            }, 3_000L)
        }
    }

    private fun pausePlayers() {
        runCatching { idle?.pause() }
        runCatching { acceleration?.pause() }
        lastSpeedAtMs = null
        lastSpeedKmh = null
    }

    fun onResume() { main.post { activeInForeground = true } }

    fun onPause() {
        main.post {
            activeInForeground = false
            previewing = false
            previewToken++
            pausePlayers()
        }
    }

    /** Use only when application-owned audio lifetime ends. */
    fun release() {
        main.post {
            pausePlayers()
            idle?.release()
            acceleration?.release()
            idle = null
            acceleration = null
            currentProfile = null
        }
    }
}
````

## 10. `nativecockpit/src/main/java/com/evcockpit/data/radio/RadioBrowserApi.kt`

````kotlin
package com.evcockpit.data.radio

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

/** Radio Browser's JSON fields; blank values are rejected in RadioRepository. */
data class RadioStation(
    @SerializedName("stationuuid") val id: String = "",
    val name: String = "",
    @SerializedName("url_resolved") val urlResolved: String = "",
    val favicon: String = "",
    val tags: String = "",
    val votes: Int = 0
)

interface RadioBrowserApi {
    // Radio Browser Turkish stations; ranked in descending community vote order.
    @GET("json/stations/bycountryexact/Turkey")
    suspend fun stations(
        @Query("hidebroken") hideBroken: Boolean = true,
        @Query("order") order: String = "votes",
        @Query("reverse") reverse: Boolean = true,
        @Query("limit") limit: Int = 500
    ): List<RadioStation>
}
````

## 11. `nativecockpit/src/main/java/com/evcockpit/data/radio/RadioRepository.kt`

````kotlin
package com.evcockpit.data.radio

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Singleton
class RadioRepository @Inject constructor(private val api: RadioBrowserApi) {
    /** Cold stream; each refresh fetches current radio listings and wraps failures. */
    fun stations(): Flow<Result<List<RadioStation>>> = flow {
        emit(Result.success(playableStations(api.stations())))
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(Result.failure(error))
    }.flowOn(Dispatchers.IO)

    companion object {
        // AndroidManifest usesCleartextTraffic=false: never silently upgrade an
        // HTTP-only stream to HTTPS or relax app-wide network security.
        internal fun playableStations(stations: List<RadioStation>): List<RadioStation> =
            stations.asSequence()
                .filter {
                    !it.id.isNullOrBlank() && !it.name.isNullOrBlank() &&
                        it.urlResolved?.toHttpUrlOrNull()?.isHttps == true
                }
                .sortedByDescending { it.votes }
                .distinctBy { it.urlResolved }
                .distinctBy { it.id }
                .toList()
    }
}
````

## 12. `nativecockpit/src/main/java/com/evcockpit/presentation/media/MediaLauncher.kt`

````kotlin
package com.evcockpit.presentation.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

sealed interface MediaLaunchState {
    data class Launched(val packageName: String) : MediaLaunchState
    data object NotInstalled : MediaLaunchState
    data object Blocked : MediaLaunchState
}

/** External DRM players keep their own playback, focus and picture-in-picture lifecycle. */
object MediaLauncher {
    // Real package IDs must match the installed media-engine APK.
    val mediaEnginePackages = listOf(
        "me.app.fermataX.auto.evcockpitpreview.debug",
        "me.app.fermataX.auto.evcockpitpreview"
    )

    /** No package name guessing; every candidate is declared in manifest queries. */
    fun launchMediaEngine(context: Context): MediaLaunchState {
        var blocked = false
        for (packageName in mediaEnginePackages) {
            when (val result = launchPackage(context, packageName)) {
                is MediaLaunchState.Launched -> return result
                MediaLaunchState.Blocked -> blocked = true
                MediaLaunchState.NotInstalled -> Unit
            }
        }
        return if (blocked) MediaLaunchState.Blocked else MediaLaunchState.NotInstalled
    }

    fun launchOfficialApp(context: Context, site: MediaSite): MediaLaunchState =
        when (site) {
            MediaSite.NETFLIX -> launchPackage(context, "com.netflix.mediaclient")
            MediaSite.DISNEY -> launchPackage(context, "com.disney.disneyplus")
            MediaSite.PRIME -> launchPackage(context, "com.amazon.avod.thirdpartyclient")
            // YouTube deliberately uses our protected in-app WebView.
            MediaSite.YOUTUBE -> MediaLaunchState.Blocked
        }

    /** Compatibility for existing callers expecting Result<Unit>. */
    fun openOfficialApp(context: Context, site: MediaSite): Result<Unit> =
        when (val result = launchOfficialApp(context, site)) {
            is MediaLaunchState.Launched -> Result.success(Unit)
            MediaLaunchState.NotInstalled -> Result.failure(
                ActivityNotFoundException("External media application is not installed")
            )
            MediaLaunchState.Blocked -> Result.failure(
                SecurityException("External media application launch is restricted")
            )
        }

    private fun launchPackage(context: Context, packageName: String): MediaLaunchState {
        val manager = context.packageManager
        try {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(packageName, 0)
            val intent = manager.getLaunchIntentForPackage(packageName)
                ?: manager.getLeanbackLaunchIntentForPackage(packageName)
                ?: return MediaLaunchState.Blocked

            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return MediaLaunchState.Launched(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return MediaLaunchState.NotInstalled
        } catch (_: ActivityNotFoundException) {
            // Application was disabled/uninstalled after package lookup.
            return MediaLaunchState.Blocked
        } catch (error: SecurityException) {
            Log.w("EYA-MEDIA-UAT", "Restricted external media launch", error)
            return MediaLaunchState.Blocked
        } catch (error: RuntimeException) {
            Log.w("EYA-MEDIA-UAT", "External media launch failed", error)
            return MediaLaunchState.Blocked
        }
    }
}
````

## 13. `nativecockpit/src/main/java/com/evcockpit/presentation/media/RadioScreen.kt`

````kotlin
package com.evcockpit.presentation.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.evcockpit.R
import com.evcockpit.presentation.voice.LocalAppAudioDucking

@Composable
fun RadioScreen(model: RadioViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val audioDucking = LocalAppAudioDucking.current
    val voiceDucked = if (audioDucking != null)
        audioDucking.ducked.collectAsStateWithLifecycle().value else false
    val player = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
    }
    LaunchedEffect(player, voiceDucked) {
        // TTS navigation/welcome reduces only our radio player to 20%.
        player.volume = if (voiceDucked) 0.2f else 1f
    }
    var query by rememberSaveable { mutableStateOf("") }
    val filteredStations = remember(state.stations, query) {
        val term = query.trim()
        if (term.isEmpty()) state.stations
        else state.stations.filter {
            it.name.contains(term, ignoreCase = true) ||
                (it.tags?.contains(term, ignoreCase = true) == true)
        }
    }
    var currentName by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var playbackFailed by remember { mutableStateOf(false) }
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
            }
            override fun onPlayerError(error: PlaybackException) {
                playbackFailed = true
                buffering = false
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        player.addListener(listener)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.radio_description), style = MaterialTheme.typography.bodySmall)
        currentName?.let { name ->
            Text(stringResource(R.string.radio_selected, name))
            if (buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (playbackFailed) Text(stringResource(R.string.radio_playback_error))
            Row {
                TextButton(onClick = {
                    if (!playbackFailed && (playing || player.playWhenReady)) player.pause()
                    else { if (player.playerError != null) player.prepare(); playbackFailed = false; player.play() }
                }) {
                    Text(stringResource(if (playing || buffering) R.string.radio_pause else R.string.radio_play))
                }
                TextButton(onClick = { player.stop(); currentName = null; playbackFailed = false }) {
                    Text(stringResource(R.string.radio_stop))
                }
            }
        }
        when {
            state.loading -> CircularProgressIndicator()
            state.failed -> Text(stringResource(R.string.radio_load_error))
            state.stations.isEmpty() -> Text(stringResource(R.string.radio_empty))
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(80) },
            label = { Text(stringResource(R.string.radio_search)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.radio_results, filteredStations.size, state.stations.size),
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = model::refresh, enabled = !state.loading) {
                Text(stringResource(R.string.radio_refresh))
            }
        }
        LazyColumn(Modifier.fillMaxWidth().height(260.dp)) {
            items(filteredStations, key = { it.id }) { station ->
                Column(Modifier.fillMaxWidth().clickable {
                    currentName = station.name
                    playbackFailed = false
                    player.setMediaItem(MediaItem.fromUri(station.urlResolved))
                    player.prepare()
                    player.play()
                }.padding(vertical = 12.dp, horizontal = 8.dp)) {
                    Text(station.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.radio_votes, station.votes),
                        style = MaterialTheme.typography.bodySmall)
                    if (!station.tags.isNullOrBlank()) Text(station.tags,
                        maxLines = 2, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
        Text(stringResource(R.string.radio_attribution), style = MaterialTheme.typography.labelSmall)
    }
}
````

## 14. `nativecockpit/build.gradle.kts`

````kotlin
import java.io.File
import java.io.DataOutputStream
import kotlin.math.sin
import kotlin.math.min
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// Local release signing credentials live in ignored local.properties.
// GitHub Actions supplies the same values as environment variables.
val localSigning = Properties().apply {
    val config = rootProject.file("local.properties")
    if (config.isFile) config.inputStream().use { load(it) }
}

fun signingValue(propertyKey: String, environmentKey: String): String? =
    providers.environmentVariable(environmentKey).orNull
        ?.takeIf { it.isNotBlank() }
        ?: localSigning.getProperty(propertyKey)?.trim()?.takeIf { it.isNotBlank() }

val storePath = signingValue("eya.release.storeFile", "EYA_RELEASE_STORE_FILE")
val storePasswordValue = signingValue("eya.release.storePassword", "EYA_RELEASE_STORE_PASSWORD")
val keyAliasValue = signingValue("eya.release.keyAlias", "EYA_RELEASE_KEY_ALIAS")
val keyPasswordValue = signingValue("eya.release.keyPassword", "EYA_RELEASE_KEY_PASSWORD")
val releaseStore = storePath?.let { path ->
    File(path).let { if (it.isAbsolute) it else rootProject.file(path) }
}
val signedReleaseReady = releaseStore?.isFile == true &&
    !storePasswordValue.isNullOrBlank() &&
    !keyAliasValue.isNullOrBlank() &&
    !keyPasswordValue.isNullOrBlank()

android {
    namespace = "com.evcockpit"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.evcockpit"
        minSdk = 28
        targetSdk = 35
        versionCode = 22
        versionName = "2.0.5"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        // No debug-key fallback. This is populated only with real owner credentials.
        if (signedReleaseReady) {
            create("eyaProduction") {
                storeFile = releaseStore
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Remains the explicitly marked UAT/simulation variant.
            isMinifyEnabled = false
        }
        getByName("release") {
            isDebuggable = false
            // Diagnostic release: disable R8 code + resource shrinking until crash is reproduced.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signedReleaseReady) {
                signingConfig = signingConfigs.getByName("eyaProduction")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = false }
}

// Fail closed: a signing-less artifact must NEVER be called a production APK.
gradle.taskGraph.whenReady {
    val buildingRelease = allTasks.any { task ->
        task.project.path == ":nativecockpit" &&
            task.name in setOf("assembleRelease", "packageRelease", "validateSigningRelease")
    }
    if (buildingRelease && !signedReleaseReady) {
        throw GradleException(
            "Production release signing is not configured. Set " +
                "eya.release.storeFile/storePassword/keyAlias/keyPassword " +
                "in ignored local.properties (or EYA_RELEASE_* environment variables)."
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.activity:activity-ktx:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    implementation("com.google.dagger:hilt-android:2.58")
    ksp("com.google.dagger:hilt-compiler:2.58")
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    implementation("org.maplibre.gl:android-sdk-turf:6.0.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// Generate ten original, copyright-free mono WAV loops during Gradle builds.
// GitHub's text-only mobile workflow does not require uploading binary assets.
val generatedSoundRes = layout.buildDirectory.dir("generated/active-sound/res")
android.sourceSets.getByName("main").res.srcDir(generatedSoundRes)

val generateOriginalEngineWavAssets = tasks.register("generateOriginalEngineWavAssets") {
    val output = generatedSoundRes
    outputs.dir(output)
    doLast {
        val raw = output.get().asFile.resolve("raw").apply { mkdirs() }
        val sampleRate = 22_050
        val count = sampleRate * 2
        val profiles = linkedMapOf(
            "v8" to 64.0, "f1" to 175.0, "futuristic" to 115.0,
            "turbine" to 93.0, "stealth" to 42.0
        )
        for ((profile, fundamental) in profiles) {
            for (accelerating in listOf(false, true)) {
                val mode = if (accelerating) "accel" else "idle"
                val file = raw.resolve("${profile}_${mode}.wav")
                DataOutputStream(file.outputStream().buffered()).use { out ->
                    fun word(n: Int) {
                        out.writeByte(n and 255)
                        out.writeByte((n ushr 8) and 255)
                    }
                    fun dword(n: Int) {
                        word(n and 0xffff)
                        word(n ushr 16)
                    }
                    out.writeBytes("RIFF")
                    dword(36 + count * 2)
                    out.writeBytes("WAVEfmt ")
                    dword(16)
                    word(1)  // signed 16-bit PCM, mono
                    word(1)
                    dword(sampleRate)
                    dword(sampleRate * 2)
                    word(2)
                    word(16)
                    out.writeBytes("data")
                    dword(count * 2)
                    for (i in 0 until count) {
                        val t = i.toDouble() / sampleRate
                        val f = fundamental * if (accelerating) 1.75 else 1.0
                        val pulse = 1.0 + 0.10 * sin(
                            2.0 * Math.PI * (if (profile == "v8") 8.0 else 5.0) * t
                        )
                        val signal = (
                            0.52 * sin(2 * Math.PI * f * t) +
                            0.24 * sin(2 * Math.PI * 2 * f * t) +
                            0.12 * sin(2 * Math.PI * 3 * f * t)
                        ) * pulse
                        // Short fades avoid audible clicks at repeat boundaries.
                        val edge = min(i, count - 1 - i)
                        val fade = (edge.toDouble() / 256).coerceIn(0.0, 1.0)
                        val pcm = (signal * fade * 0.23 * 32767)
                            .toInt().coerceIn(-32768, 32767)
                        word(pcm and 0xffff)
                    }
                }
            }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(generateOriginalEngineWavAssets) }
````

## 15. `nativecockpit/proguard-rules.pro`

````proguard
# :nativecockpit production R8 preservation rules.
# R8 optimization is enabled in the release build. Consumer rules from
# AndroidX, Hilt, Retrofit, Gson, MapLibre and Media3 remain in effect.
# Broad keeps below prioritize compatibility but reduce shrinking efficiency.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable
-keepclasseswithmembers class * { native <methods>; }

# Hilt/Dagger: preserve generated bindings, entrypoints and reflection.
-keep class dagger.hilt.** { *; }
-keep class com.evcockpit.EyaApplication { *; }
-keep class com.evcockpit.MainActivity { *; }
-keep class com.evcockpit.Hilt_* { *; }
-keep class com.evcockpit.**.Hilt_* { *; }
-keep class com.evcockpit.**_Factory { *; }
-keep class com.evcockpit.**_MembersInjector { *; }
-keep class com.evcockpit.Dagger* { *; }
-keep class com.evcockpit.ObdForegroundService { *; }
-keep class com.evcockpit.car.EyaCarAppService { *; }

# Retrofit and Gson: preserve reflective API interfaces, model signatures,
# coroutine continuation and annotation-driven field mappings.
-keep class kotlin.coroutines.Continuation
-keep class com.evcockpit.data.** { *; }
-keep interface com.evcockpit.data.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# MapLibre native JNI / dynamic class loading.
-keep class org.maplibre.** { *; }
-keep class org.maplibre.android.** { *; }

# Media3 service and player internals. Preserve for conservative initial R8
# rollout, then trim with tested consumer rules once a signed build is verified.
-keep class androidx.media3.** { *; }

# Real Bluetooth ELM327 state machine and Android Auto entrypoints.
-keep class com.evcockpit.domain.usecase.Elm327Session { *; }
-keep class com.evcockpit.data.repository.Elm327VehicleRepository { *; }
-keep class com.evcockpit.data.obd.** { *; }
-keep class com.evcockpit.car.** { *; }

# No -dontoptimize / -dontwarn. Missing classes remain build errors.
````

## 16. `gradle.properties`

````properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
# Keep the requested compatibility flag; release uses R8 minify and resource shrink.
android.enableR8.fullMode=false
````

## 17. `nativecockpit/src/main/AndroidManifest.xml`

````xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <!-- Android 13+: only request at runtime when posting a notification. -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="androidx.car.app.NAVIGATION_TEMPLATES" />
    <uses-permission android:name="androidx.car.app.ACCESS_SURFACE" />

    <queries>
        <package android:name="me.app.fermataX.auto.evcockpitpreview.debug" />
        <package android:name="me.app.fermataX.auto.evcockpitpreview" />
        <intent><action android:name="android.intent.action.TTS_SERVICE" /></intent>
        <package android:name="com.netflix.mediaclient" />
        <package android:name="com.disney.disneyplus" />
        <package android:name="com.amazon.avod.thirdpartyclient" />
    </queries>

    <application
        android:name=".EyaApplication"
        android:allowBackup="false"
        android:label="@string/app_name"
        android:icon="@drawable/ic_eya_car"
        android:usesCleartextTraffic="false"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity android:name=".MainActivity"
            android:exported="true"
            android:supportsPictureInPicture="true"
            android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <meta-data android:name="com.google.android.gms.car.application"
            android:resource="@xml/automotive_app_desc" />
        <meta-data android:name="androidx.car.app.minCarApiLevel" android:value="1" />
        <service android:name=".car.EyaCarAppService"
            android:label="@string/app_name"
            android:icon="@drawable/ic_eya_car"
            android:exported="true">
            <intent-filter>
                <action android:name="androidx.car.app.CarAppService" />
                <category android:name="androidx.car.app.category.NAVIGATION" />
            </intent-filter>
        </service>
        <service
            android:name=".ObdForegroundService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />
        <!-- Only an explicit user-initiated Bluetooth connection starts this service.
             No background auto-connect, no unrestricted background location service. -->
    </application>
</manifest>
````

## 18. `.github/workflows/build-nativecockpit.yml`

````yaml
name: Native Cockpit · Kotlin Compose APK

on:
  workflow_dispatch:
  pull_request:
    branches:
      - feature/ev-cockpit
  push:
    branches:
      - feature/ev-cockpit

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 45
    steps:
      - name: Checkout
        uses: actions/checkout@v4
      - name: Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - name: Verify preinstalled Android SDK on GitHub runner
        shell: bash
        run: |
          set -euo pipefail
          echo "SDK: ${ANDROID_HOME:-unset}"
          sdkmanager_bin="$(find "$ANDROID_HOME/cmdline-tools" -type f -path '*/bin/sdkmanager' -print -quit)"
          test -x "$sdkmanager_bin"
          echo "$(dirname "$sdkmanager_bin")" >> "$GITHUB_PATH"
          "$sdkmanager_bin" --version
      - name: Install Android API 36
        run: sdkmanager "platforms;android-36" "build-tools;36.0.0"
      - name: Gradle 8.14.3
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "8.14.3"
      - name: Validate radio, media, map and release settings
        run: python3 scripts/check_cockpit_radio_media_map_release.py
      - name: Audit code XML and README brand terms
        run: python3 scripts/audit_branding.py
      - name: Validate Turkish welcome session wiring
        run: python3 scripts/check_welcome_greeting.py
      - name: Enforce real GPS and OBD inputs
        run: python3 scripts/check_production_hardware.py
      - name: Native Cockpit unit tests
        run: gradle :nativecockpit:testDebugUnitTest --stacktrace --no-daemon
      - name: Validate release R8 shrinking without signing keys
        run: gradle :nativecockpit:minifyReleaseWithR8 --stacktrace --no-daemon
      - name: Compile native debug APK
        run: gradle :nativecockpit:assembleDebug --stacktrace --no-daemon
      - name: Verify APK exists
        run: |
          set -euo pipefail
          apk="nativecockpit/build/outputs/apk/debug/nativecockpit-debug.apk"
          test -s "$apk"
          sha256sum "$apk" > nativecockpit-debug.sha256
          ls -lh "$apk"
      - name: Verify Android Auto navigation service and OBD permissions in APK
        shell: bash
        run: |
          set -euo pipefail
          apk="nativecockpit/build/outputs/apk/debug/nativecockpit-debug.apk"
          aapt="$ANDROID_HOME/build-tools/36.0.0/aapt"
          test -x "$aapt"
          "$aapt" dump xmltree "$apk" AndroidManifest.xml > manifest-tree.txt
          grep -q 'androidx.car.app.CarAppService' manifest-tree.txt
          grep -q 'androidx.car.app.category.NAVIGATION' manifest-tree.txt
          grep -q 'android.permission.BLUETOOTH_CONNECT' manifest-tree.txt
          echo "APK includes Android Auto navigation service and paired Bluetooth permission"
      - name: Publish test APK and checksum
        uses: actions/upload-artifact@v4
        with:
          name: EV-Cockpit-Native-Kotlin-Debug-APK
          path: |
            nativecockpit/build/outputs/apk/debug/nativecockpit-debug.apk
            nativecockpit-debug.sha256
          if-no-files-found: error
          retention-days: 14

  release:
    if: github.event_name != 'pull_request'
    # Only trusted push/dispatch; do not expose signing secrets to pull requests.
    needs: build
    runs-on: ubuntu-latest
    timeout-minutes: 50
    steps:
      - name: Checkout
        uses: actions/checkout@v4
      - name: Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - name: Install Android SDK
        shell: bash
        run: |
          set -euo pipefail
          sdkmanager_bin="$(find "$ANDROID_HOME/cmdline-tools" -type f -path '*/bin/sdkmanager' -print -quit)"
          test -x "$sdkmanager_bin"
          echo "$(dirname "$sdkmanager_bin")" >> "$GITHUB_PATH"
          "$sdkmanager_bin" "platforms;android-36" "build-tools;36.0.0"
      - name: Gradle 8.14.3
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "8.14.3"

      - name: Restore owner keystore from one GitHub Secret
        shell: bash
        env:
          EYA_SIGNING_BUNDLE_BASE64: ${{ secrets.EYA_SIGNING_BUNDLE_BASE64 }}
        run: |
          set -euo pipefail
          if [ -z "$EYA_SIGNING_BUNDLE_BASE64" ]; then
            {
              echo "### Production signing is blocked"
              echo 'Required repository secret: `EYA_SIGNING_BUNDLE_BASE64`.'
              echo 'Restore the existing owner signing bundle under Settings → Secrets and variables → Actions.'
              echo 'The debug APK is published separately. No unsigned or debug-signed APK is substituted for Release.'
              echo 'Keep the existing signing identity for updates; do not regenerate it for each build.'
            } >> "$GITHUB_STEP_SUMMARY"
            echo "::error::Missing EYA_SIGNING_BUNDLE_BASE64 GitHub Secret"
            exit 1
          fi
          umask 077
          # The base64 bundle contains the owner's persistent JKS and credentials.
          # It is NEVER printed, uploaded, committed, or exposed to untrusted PRs.
          python3 - <<'PY'
          import base64
          import hashlib
          import json
          import os
          from pathlib import Path

          payload = json.loads(base64.b64decode(
              os.environ["EYA_SIGNING_BUNDLE_BASE64"], validate=True
          ).decode("utf-8"))
          if payload.get("schema") != "eya-signing-v1":
              raise ValueError("Unsupported signing bundle")
          data = base64.b64decode(payload["keystore_base64"], validate=True)
          if hashlib.sha256(data).hexdigest() != payload["keystore_sha256"]:
              raise ValueError("Keystore integrity check failed")
          path = Path(os.environ["RUNNER_TEMP"]) / "eya-release.jks"
          path.write_bytes(data)
          path.chmod(0o600)

          names = {
              "EYA_RELEASE_STORE_FILE": str(path),
              "EYA_RELEASE_STORE_PASSWORD": payload["store_password"],
              "EYA_RELEASE_KEY_ALIAS": payload["key_alias"],
              "EYA_RELEASE_KEY_PASSWORD": payload["key_password"],
          }
          for key, value in names.items():
              if not value or "\n" in value or "\r" in value:
                  raise ValueError(f"Invalid signing setting: {key}")
              if key != "EYA_RELEASE_STORE_FILE":
                  print(f"::add-mask::{value}")
          with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as env:
              for key, value in names.items():
                  env.write(f"{key}={value}\n")
          print("Signing identity restored securely (certificate not published)")
          PY

      - name: Validate owner certificate
        shell: bash
        run: |
          set -euo pipefail
          keytool -list -keystore "$EYA_RELEASE_STORE_FILE" \
            -storepass:env EYA_RELEASE_STORE_PASSWORD \
            -alias "$EYA_RELEASE_KEY_ALIAS" >/dev/null
          echo "Owner certificate verified"

      - name: Build signed production Release APK (R8 enabled)
        shell: bash
        run: |
          set -euo pipefail
          gradle :nativecockpit:assembleRelease --stacktrace --no-daemon

      - name: Verify APK certificate and prepare artifact
        shell: bash
        run: |
          set -euo pipefail
          apk="nativecockpit/build/outputs/apk/release/nativecockpit-release.apk"
          test -s "$apk"
          "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs "$apk"
          "$ANDROID_HOME/build-tools/36.0.0/aapt" dump badging "$apk" |
            grep -q "package: name='com.evcockpit'"
          cp "$apk" app-release.apk
          sha256sum app-release.apk > app-release.sha256
          ls -lh app-release.apk

      - name: Publish signed Release APK
        uses: actions/upload-artifact@v4
        with:
          name: EV-Cockpit-Signed-Stable-Release
          path: |
            app-release.apk
            app-release.sha256
          if-no-files-found: error
          retention-days: 30

      - name: Erase temporary signing material
        if: always()
        shell: bash
        run: |
          rm -f "$RUNNER_TEMP/eya-release.jks" "$RUNNER_TEMP/eya-release.jks.enc"
````

## 19. `scripts/check_production_hardware.py`

````python
#!/usr/bin/env python3
"""Fail the CI build when native production code reintroduces fake GPS or OBD."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "nativecockpit/src/main/java/com/evcockpit"

def read(relative: str) -> str:
    return (JAVA / relative).read_text(encoding="utf-8")

nav = read("presentation/navigation/NavigationViewModel.kt")
phone = read("MainActivity.kt")
cockpit = read("presentation/cockpit/CockpitScreen.kt")
obd_dialog = read("presentation/obd/ObdSettingsDialog.kt")
car = read("car/EyaCarNavigationScreen.kt")
repo = read("data/repository/Elm327VehicleRepository.kt")
fg = read("ObdForegroundService.kt")
gradle = (ROOT / "nativecockpit/build.gradle.kts").read_text(encoding="utf-8")
proguard = (ROOT / "nativecockpit/proguard-rules.pro").read_text(encoding="utf-8")

assert not (JAVA / "presentation/navigation/DebugRouteSimulation.kt").exists()
assert not (JAVA / "presentation/navigation/NavigationSourceSelector.kt").exists()
assert not (ROOT / "nativecockpit/src/test/java/com/evcockpit/presentation/navigation/DebugRouteSimulationTest.kt").exists()

for source in (nav, phone, cockpit, obd_dialog):
    assert "DebugRouteSimulation" not in source
    assert "_simulationEnabled" not in source
    assert "simulationEnabled" not in source
    assert "onSimulationToggle" not in source
assert "autoDriveTestRequested" not in car
assert "source.simulated" not in nav
assert 'fresh ?: 80.0' not in nav
assert 'ExplicitAssumption(' not in nav
assert "DepartureSoc.FreshObd(fresh, measuredAt)" in nav
assert "vehicleRepository.telemetry, locationRepository.gpsFix" in nav
assert "locationRepository.start()" in nav
assert "isFromMockProvider" in nav
assert "createRfcommSocketToServiceRecord(spp)" in repo
assert "conn.connect()" in repo
assert "Elm327Session(transport)" in repo
assert "ElmSessionPhase.READY" in repo
assert 'protocol.readRawOnce("010D")' in repo
assert "runMebReadOnlyQuery(conn, protocol, command)" in repo
assert "protocol.close()" in repo

assert "withContext(Dispatchers.IO) { runSession(device) }" in repo
assert "_telemetry.value = VehicleTelemetry()" in repo
assert "ObdPhase.CONNECTED" in fg
assert "OBD Bağlantısı Bekleniyor" in cockpit

assert "isMinifyEnabled = true" in gradle
assert "isShrinkResources = true" in gradle
assert not any(line.strip() == "-dontoptimize" for line in proguard.splitlines())
print("OK: real-only navigation, fail-closed ELM327 and R8 production guards")
````


## 20. `nativecockpit/src/test/java/com/evcockpit/domain/usecase/Elm327SessionTest.kt`

````kotlin
package com.evcockpit.domain.usecase

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import com.evcockpit.domain.repository.ObdTransport

class Elm327SessionTest {
    private class FakeTransport : ObdTransport {
        override var connected: Boolean = false
            private set
        val commands = mutableListOf<String>()
        var failOnce = false
        var noDataNext = false
        var alwaysDropOnRead = false
        var connections = 0
        var disconnections = 0
        var blockRead: CompletableDeferred<Unit>? = null
        val readEntered = CompletableDeferred<Unit>()
        override suspend fun connect(timeoutMillis: Long) {
            connections++
            connected = true
        }
        override suspend fun exchange(command: String, timeoutMillis: Long): String {
            commands.add(command)
            if (command == "221E0E" && blockRead != null) {
                if (!readEntered.isCompleted) readEntered.complete(Unit)
                blockRead?.await()
            }
            if (alwaysDropOnRead && command == "221E0E") {
                connected = false
                throw IOException("Bluetooth out of range / ignition off")
            }
            if (failOnce && command == "221E0E") {
                failOnce = false
                throw IOException("Adapter disconnected")
            }
            if (noDataNext && command == "221E0E") {
                noDataNext = false
                return "NO DATA"
            }
            return when (command) {
                "ATZ" -> "ELM327 v1.5"
                "ATE0", "ATL0" -> "OK"
                else -> "62 1E 0E 04 68"
            }
        }
        override fun disconnect() {
            disconnections++
            connected = false
        }
    }

    @Test fun startsWithExactInitializationSequence() = runBlocking {
        val fake = FakeTransport()
        val session = Elm327Session(fake)
        session.initialize()
        assertEquals(listOf("ATZ", "ATE0", "ATL0"), fake.commands)
        assertEquals("62 1E 0E 04 68", session.readOnly("22 1E 0E"))
        session.close()
    }

    @Test fun retriesConnectionAfterReadFailure() = runBlocking {
        val fake = FakeTransport().apply { failOnce = true }
        val session = Elm327Session(fake)
        session.initialize()
        assertEquals("62 1E 0E 04 68", session.readOnly("221E0E"))
        assertEquals(2, fake.connections)
        assertEquals(2, fake.commands.count { it == "ATZ" })
        session.close()
    }

    @Test fun permanentDropRetriesThreeTimesThenExposesError() = runBlocking {
        val adapter = FakeTransport().apply { alwaysDropOnRead = true }
        val session = Elm327Session(adapter)
        session.initialize()
        val result = runCatching { session.readOnly("221E0E") }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(3, adapter.commands.count { it == "221E0E" })
        assertEquals(3, adapter.connections)
        assertTrue(adapter.disconnections >= 3)
        assertFalse(adapter.connected)
        assertEquals(ElmSessionPhase.ERROR, session.state.value.phase)
        assertTrue(session.state.value.error?.contains("after three attempts") == true)
        // The caller/controller must publish the error to ObdConnectionState.ERROR.
        session.close()
    }

    @Test fun closedSessionCanInitializeAgainWithFreshTransportState() = runBlocking {
        val adapter = FakeTransport()
        val session = Elm327Session(adapter)
        session.initialize()
        assertEquals(ElmSessionPhase.READY, session.state.value.phase)
        session.close()
        assertEquals(ElmSessionPhase.DISCONNECTED, session.state.value.phase)
        assertFalse(adapter.connected)
        session.initialize() // explicit caller reconnect after close
        assertEquals(2, adapter.connections)
        assertEquals(ElmSessionPhase.READY, session.state.value.phase)
        assertEquals(2, adapter.commands.count { it == "ATZ" })
        session.close()
    }

    @Test fun closeDuringReadCannotPublishStaleResultIntoNewSession() = runBlocking {
        val adapter = FakeTransport().apply {
            blockRead = CompletableDeferred()
        }
        val session = Elm327Session(adapter)
        session.initialize()
        val pending = async {
            runCatching { session.readOnly("221E0E") }
        }
        adapter.readEntered.await()
        session.close() // Must not wait on the command mutex.
        assertEquals(ElmSessionPhase.DISCONNECTED, session.state.value.phase)
        adapter.blockRead?.complete(Unit) // A late old response arrives.
        assertTrue(pending.await().isFailure)
        assertEquals(ElmSessionPhase.DISCONNECTED, session.state.value.phase)
        adapter.blockRead = null
        session.initialize()
        assertEquals(ElmSessionPhase.READY, session.state.value.phase)
        session.close()
    }

    @Test fun dangerousNonReadCommandsAreRejected(): Unit = runBlocking {
        val session = Elm327Session(FakeTransport())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { session.readOnly("2F1234") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { session.readOnly("ATSH7E0") }
        }
    }
    @Test fun rawOnceNeedsReadyAndReturnsGenuineNoDataWithoutReconnecting() = runBlocking {
        val fake = FakeTransport()
        val session = Elm327Session(fake)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { session.readRawOnce("010D") }
        }
        session.initialize()
        assertEquals("62 1E 0E 04 68", session.readRawOnce("221E0E"))
        fake.noDataNext = true
        assertEquals("NO DATA", session.readRawOnce("221E0E"))
        assertEquals(1, fake.connections)
        assertEquals(ElmSessionPhase.READY, session.state.value.phase)
        session.close()
    }

    @Test fun rawOnceNeverAcceptsCommandsThatWriteToEcu(): Unit = runBlocking {
        val session = Elm327Session(FakeTransport())
        session.initialize()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { session.readRawOnce("2E1234") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { session.readRawOnce("ATSH710") }
        }
        session.close()
    }

}
````
