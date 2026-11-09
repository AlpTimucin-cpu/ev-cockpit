# EV Cockpit — Full Production Source Snapshot

Source versions below are taken from branch `feature/production-brand-docs-r8-final` after the November 4 production hardening changes. The source of truth remains the Kotlin, Gradle, ProGuard, and YAML files at these paths. A source snapshot is not a signed APK or physical-device certification.

## 1. `nativecockpit/src/main/java/com/evcockpit/presentation/navigation/NavigationViewModel.kt`

```kotlin
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
```

## 2. `nativecockpit/src/main/java/com/evcockpit/presentation/cockpit/CockpitScreen.kt`

```kotlin
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
            // No fake zero-percent bar when ELM327/BMS data is unavailable.
            val validSoc = state.socPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }
            if (validSoc != null) {
                LinearProgressIndicator(
                    progress = { (validSoc / 100.0).toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(3.dp),
                    color = NeonGreen, trackColor = GlassLine
                )
            } else {
                Text(
                    stringResource(R.string.cockpit_measurement_unavailable),
                    style = MaterialTheme.typography.labelSmall, color = SoftText
                )
            }
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
        if (number != unknownValue) {
            Text(percentSymbol, Modifier.padding(bottom = if (compact) 8.dp else 12.dp),
                fontSize = if (compact) 30.sp else 40.sp,
                lineHeight = if (compact) 36.sp else 46.sp, color = SoftText)
        }
    }
}

@Composable
private fun RangeValue(km: Double?, modifier: Modifier = Modifier) {
    val unit = stringResource(R.string.cockpit_distance_unit)
    Column(modifier) {
        val knownRange = km?.takeIf { it.isFinite() && it >= 0.0 }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                knownRange?.let { CockpitFormat.distance(it).removeSuffix(" $unit") }
                    ?: stringResource(R.string.cockpit_unknown_value),
                Modifier.alignByBaseline(), fontSize = 39.sp, lineHeight = 46.sp,
                fontWeight = FontWeight.Light, color = NeonGreen, maxLines = 1, softWrap = false
            )
            if (knownRange != null) {
                Text(unit, Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.labelMedium, color = SoftText)
            }
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
```

## 3. `nativecockpit/src/main/java/com/evcockpit/presentation/sound/ActiveSoundManager.kt`

```kotlin
package com.evcockpit.presentation.sound

import android.content.Context
import android.content.res.Resources
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
            if (resourceId <= 0) return null
            // Resources may be stripped from a damaged install or an asset task
            // may fail to generate them. Audio must never crash the cockpit.
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
        } catch (error: Resources.NotFoundException) {
            Log.w("EYA-ActiveSound", "Optional engine sound resource not installed", error)
            runCatching { candidate?.release() }
            null
        } catch (error: Exception) {
            Log.w("EYA-ActiveSound", "Engine WAV unavailable; continuing silently", error)
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
            val now = System.currentTimeMillis()
            // Even if the ViewModel supplies bad/stale data, never play audio.
            val validSpeed = speedKmh?.takeIf { it.isFinite() && it in 0.0..300.0 }
            val fresh = speedAtMs != null && speedAtMs <= now + 2_000L &&
                now - speedAtMs in 0L..10_000L
            if (!foreground || !enabled || previewing || validSpeed == null || !fresh) {
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
        // Existing level is restored after TTS; no system stream is modified.
        val normalized = (volume / 1.5f).coerceIn(0f, 1f)
        val multiplier = if (ducked) 0.2f else 1f
        runCatching { idle?.volume = normalized * multiplier * 0.75f }
            .onFailure { Log.w("EYA-ActiveSound", "Idle volume unavailable", it) }
        runCatching { acceleration?.volume = normalized * multiplier }
            .onFailure { Log.w("EYA-ActiveSound", "Acceleration volume unavailable", it) }
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
            runCatching { idle?.release() }
            runCatching { acceleration?.release() }
            idle = null
            acceleration = null
            currentProfile = null
        }
    }
}
```

## 4. `nativecockpit/src/main/java/com/evcockpit/presentation/voice/AudioDuckingManager.kt`

```kotlin
package com.evcockpit.presentation.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * One audio focus owner for short navigation speech.
 * Android ducks cooperative media apps; no stream volume or DRM manipulation.
 */
class AudioDuckingManager(
    context: Context,
    private val onFocusLost: () -> Unit
) : AutoCloseable {
    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // A broken/disabled audio service must not crash the navigation screen.
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val listener: AudioManager.OnAudioFocusChangeListener =
        AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        ) {
            main.post {
                // Late callbacks must not resurrect/interrupt closed TTS.
                if (lease.shouldStopAfterLoss()) onFocusLost()
            }
        }
    }
    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    )
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener(listener, main)
        .build()

    private val lease: AudioFocusLease = AudioFocusLease(
        requestFocus = {
            manager?.requestAudioFocus(focusRequest) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        },
        abandonFocus = { manager?.abandonAudioFocusRequest(focusRequest); Unit }
    )

    fun acquire(): Boolean = lease.acquire()
    fun release() = lease.release()
    override fun close() = lease.close()
}
```

## 5. `nativecockpit/src/main/java/com/evcockpit/presentation/voice/AudioFocusLease.kt`

```kotlin
package com.evcockpit.presentation.voice

/**
 * Pure Kotlin lifecycle guard for a single short navigation utterance.
 * Android AudioFocus and third-party media behavior remain OS responsibilities.
 */
internal class AudioFocusLease(
    private val requestFocus: () -> Boolean,
    private val abandonFocus: () -> Unit
) {
    private var held = false
    private var closed = false

    @Synchronized
    fun acquire(): Boolean {
        if (closed) return false
        if (held) return true
        held = runCatching(requestFocus).getOrDefault(false)
        return held
    }

    /** An old queued focus event must never stop a released/closed utterance. */
    @Synchronized
    fun shouldStopAfterLoss(): Boolean = held && !closed

    @Synchronized
    fun release() {
        if (held) runCatching(abandonFocus)
        held = false
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        release()
    }
}
```

## 6. `nativecockpit/src/main/java/com/evcockpit/presentation/voice/AppAudioDucking.kt`

```kotlin
package com.evcockpit.presentation.voice

import androidx.compose.runtime.staticCompositionLocalOf
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-app speech arbitration. Does not modify the system audio stream or
 * other applications; Android AudioFocus handles cooperative external apps.
 */
@Singleton
class AppAudioDucking @Inject constructor() {
    private val activeSpeakers = mutableSetOf<String>()
    private val state = MutableStateFlow(false)
    val ducked: StateFlow<Boolean> = state.asStateFlow()

    @Synchronized fun begin(owner: String) {
        activeSpeakers.add(owner)
        state.value = activeSpeakers.isNotEmpty()
    }

    @Synchronized fun end(owner: String) {
        activeSpeakers.remove(owner)
        state.value = activeSpeakers.isNotEmpty()
    }
}

/** Shared by the phone radio without adding DI to a Composable. */
val LocalAppAudioDucking = staticCompositionLocalOf<AppAudioDucking?> { null }
```

## 7. `nativecockpit/src/main/java/com/evcockpit/presentation/voice/NativeVoiceGuide.kt`

```kotlin
package com.evcockpit.presentation.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Activity-scoped TTS: foregound-only, focus-safe, no stream-volume changes.
 * Media ducking is requested from Android, not implemented by changing
 * another app's volume. An unavailable en-US voice fails closed.
 */
class NativeVoiceGuide(context: Context, private val audioSignal: AppAudioDucking) {
    private val logTag = "EYA-Voice"

    companion object {
        private val GREETING_LOCALE = Locale.forLanguageTag("tr-TR")

        /** Voice has no guaranteed gender property: prefer explicit engine labels only. */
        fun availableTurkishVoices(engine: TextToSpeech): List<Voice> =
            runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet())
                .filter { v ->
                    v.locale.language.equals("tr", true) &&
                        v.locale.country.equals("TR", true) &&
                        !v.isNetworkConnectionRequired
                }
                .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })

        fun selectTurkishGreetingVoice(
            engine: TextToSpeech,
            preferredName: String? = null
        ): Boolean {
            if (engine.setLanguage(GREETING_LOCALE) < TextToSpeech.LANG_AVAILABLE) return false
            val installed = availableTurkishVoices(engine)
            val preferred = installed.firstOrNull { it.name == preferredName }
            val labelledFemale = installed.firstOrNull { v ->
                val n = v.name.lowercase(Locale.ROOT)
                listOf("female", "woman", "kadın", "kadin").any(n::contains)
            }
            // When no labelled female is installed use the system Turkish default.
            val selection = preferred ?: labelledFemale
            if (selection != null &&
                runCatching { engine.setVoice(selection) == TextToSpeech.SUCCESS }
                    .getOrDefault(false)
            ) return true
            return engine.setLanguage(GREETING_LOCALE) >= TextToSpeech.LANG_AVAILABLE
        }

    }

    enum class Status { LOADING, READY, TURKISH_VOICE_MISSING, INIT_FAILED, AUDIO_FOCUS_DENIED }

    var status: Status by mutableStateOf(Status.LOADING)
        private set
    var lastError: String? by mutableStateOf(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val ducking = AudioDuckingManager(context) { stop() }
    private val attrs: AudioAttributes = ducking.attributes
    private var activeUtterance: String? = null
    private var startedDuckOwner: String? = null
    private var onStarted: (() -> Unit)? = null
    private var onFailed: (() -> Unit)? = null
    private var sequence = 0L
    private var closed = false

    private var tts: TextToSpeech? = null
    init {
        tts = try {
            TextToSpeech(context.applicationContext) { result ->
        main.post {
                if (closed) return@post
                val engine = tts
                if (result != TextToSpeech.SUCCESS || engine == null) {
                    status = Status.INIT_FAILED
                    lastError = "Could not initialize the TTS engine"
                    Log.e(logTag, "TextToSpeech initialization failed: result=$result")
                    return@post
                }
                val locale = Locale("en", "US")
                val supported = TtsDebugLogger.languageAvailability(engine)
                val selected = if (supported >= TextToSpeech.LANG_AVAILABLE)
                    engine.setLanguage(locale) else TextToSpeech.LANG_NOT_SUPPORTED
                TtsDebugLogger.selectedLanguage(selected)
                if (selected < TextToSpeech.LANG_AVAILABLE) {
                    status = Status.TURKISH_VOICE_MISSING
                    lastError = "English TTS Data Missing"
                    Log.e(logTag, "en-US voice unavailable: supported=$supported selected=$selected")
                } else {
                    engine.setAudioAttributes(attrs)
                    engine.setSpeechRate(1.0f)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String) {
                            main.post {
                                if (!closed && activeUtterance == utteranceId) {
                                    startedDuckOwner?.let(audioSignal::end)
                                    startedDuckOwner = "navigation:" + utteranceId
                                    startedDuckOwner?.let(audioSignal::begin)
                                    Log.d(logTag, "Utterance started")
                                    TtsDebugLogger.event("utterance onStart")
                                    onStarted?.invoke()
                                    onStarted = null
                                    // After onStart, a future onError is observable
                                    // but must not mark this already-spoken stage retryable.
                                    onFailed = null
                                }
                            }
                        }
                        override fun onDone(utteranceId: String) { finish(utteranceId) }
                        override fun onError(utteranceId: String) {
                            synthesisError(utteranceId, "TTS speech failed")
                        }
                        override fun onError(utteranceId: String, errorCode: Int) {
                            TtsDebugLogger.utteranceError(errorCode)
                            synthesisError(utteranceId, "TTS speech error: $errorCode")
                        }
                        override fun onStop(utteranceId: String, interrupted: Boolean) {
                            finish(utteranceId)
                        }
                    })
                    status = Status.READY
                }
            }
            }
        } catch (error: RuntimeException) {
            status = Status.INIT_FAILED
            lastError = "Text-to-speech engine unavailable"
            Log.w(logTag, "TextToSpeech constructor failed; speech disabled", error)
            null
        }
    }

    /** Returns false rather than speaking without a valid audio-focus grant. */
    fun speak(
        text: String,
        volume: Float,
        onUtteranceStarted: (() -> Unit)? = null,
        onUtteranceFailed: (() -> Unit)? = null
    ): Boolean {
        if (closed || (status != Status.READY && status != Status.AUDIO_FOCUS_DENIED) ||
            text.isBlank() || text.length > 190)
            return false
        val engine = tts ?: return false
        stopCurrent(notifyFailure = false) // Replace old utterance without canceling the NEW cue.
        if (!ducking.acquire()) {
            status = Status.AUDIO_FOCUS_DENIED
            lastError = "Could not obtain audio focus"
            Log.w(logTag, "Navigation audio focus was not granted")
            return false
        }
        status = Status.READY
        lastError = null
        val id = "eya-voice-${++sequence}"
        activeUtterance = id
        onStarted = onUtteranceStarted
        onFailed = onUtteranceFailed
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,volume.coerceIn(.15f,1f))
        }
        val accepted = try {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.SUCCESS
        } catch (_: RuntimeException) {
            false
        }
        if (!accepted) {
            lastError = "Could not queue the TTS announcement"
            Log.e(logTag, "TextToSpeech.speak() rejected the utterance")
            stop()
            return false
        }
        return true
    }

    fun stop() = stopCurrent(notifyFailure = true)

    private fun stopCurrent(notifyFailure: Boolean) {
        runCatching { tts?.stop() }
        activeUtterance = null
        onStarted = null
        val fail = onFailed
        onFailed = null
        // A replacement maneuver may already be pending: its policy state
        // must not be cleared by the PREVIOUS utterance's callback.
        if (notifyFailure) fail?.invoke()
        releaseFocus()
    }

    // The engine can accept speak() and fail later. Surface that failure.
    private fun synthesisError(id: String, reason: String) {
        main.post {
            if (!closed && activeUtterance == id) {
                lastError = reason
                Log.e(logTag, "Utterance synthesis failed id=$id: $reason")
                activeUtterance = null
                onStarted = null
                val fail = onFailed
                onFailed = null
                fail?.invoke()
                releaseFocus()
            }
        }
    }

    private fun finish(id: String) {
        main.post {
            if (id == activeUtterance) {
                Log.d(logTag, "Utterance finished")
                TtsDebugLogger.event("utterance finished, audio focus released")
                activeUtterance = null
                onStarted = null
                val fail = onFailed
                onFailed = null
                fail?.invoke()
                releaseFocus()
            }
        }
    }

    private fun releaseFocus() {
        startedDuckOwner?.let(audioSignal::end)
        startedDuckOwner = null
        ducking.release()
    }

    fun close() {
        if (closed) return
        closed = true
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ducking.close()
    }
}
```

## 8. `nativecockpit/src/main/java/com/evcockpit/presentation/voice/WelcomeGreetingManager.kt`

```kotlin
package com.evcockpit.presentation.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.evcockpit.data.settings.UserSettings
import com.evcockpit.data.settings.UserSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One brief Turkish greeting after a real user-initiated OBD connection or
 * Android Auto Session creation. Android TTS / AudioFocus only: no sound assets.
 *
 * The singleton is shared across Activity and CarAppService within one process.
 * It is NOT a vehicle ignition signal; no greeting on ordinary Activity launch.
 */
@Singleton
class WelcomeGreetingManager @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: UserSettingsRepository,
    private val audioDucking: AppAudioDucking
) {
    private val appContext = context.applicationContext
    private val logTag = "EYA-Welcome"
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var welcomeSettings = UserSettings()
    private val sessionGate = WelcomeSessionGate()
    private val preferences = VoicePreference(appContext)
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        ) main.post { closeSession() }
    }
    private val focusRequest = AudioFocusRequest.Builder(
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    )
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener(focusListener, main)
        .build()

    private var engine: TextToSpeech? = null
    private var initializationPending = false
    private var focusGranted = false
    private var active = false
    private val initTimeout = Runnable {
        if (initializationPending) {
            Log.w(logTag, "Turkish TTS initialization timed out")
            closeSession()
        }
    }
    private val speechTimeout = Runnable {
        Log.w(logTag, "Welcome synthesis timed out")
        closeSession()
    }

    /**
     * Thread-safe and idempotent. Call only after the OBD phase is CONNECTED
     * or from a real CarAppService.onCreateSession callback.
     */
    fun launchWelcomeGreeting() {
        if (!sessionGate.claim()) return
        scope.launch {
            // DataStore is asynchronous: read the actual persisted user setting.
            val current = runCatching { settings.settings.first() }.getOrNull() ?: return@launch
            if (!current.isWelcomeGreetingEnabled) return@launch
            welcomeSettings = current
            startSession()
        }
    }

    private fun startSession() {
        if (active) return
        active = true
        initializationPending = true
        main.postDelayed(initTimeout, 8_000L)

        // This engine is separate from en-US navigation TTS and is short-lived.
        try {
            engine = TextToSpeech(appContext) { result ->
                main.post {
                    if (!active || !initializationPending) return@post
                    initializationPending = false
                    main.removeCallbacks(initTimeout)
                    val localEngine = engine
                    if (result != TextToSpeech.SUCCESS || localEngine == null ||
                        !NativeVoiceGuide.selectTurkishGreetingVoice(localEngine, welcomeSettings.ttsVoiceName)
                    ) {
                        Log.w(logTag, "Turkish text-to-speech voice unavailable")
                        closeSession()
                        return@post
                    }
                    localEngine.setAudioAttributes(audioAttributes)
                    localEngine.setSpeechRate(welcomeSettings.ttsSpeechRate)
                    localEngine.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String) {
                                if (utteranceId == UTTERANCE_ID) main.post {
                                    if (active) {
                                        audioDucking.begin("welcome")
                                        Log.d(logTag, "Welcome utterance started")
                                    }
                                }
                            }

                            override fun onDone(utteranceId: String) =
                                finishIfWelcome(utteranceId)

                            override fun onError(utteranceId: String) =
                                finishIfWelcome(utteranceId)

                            override fun onError(utteranceId: String, errorCode: Int) =
                                finishIfWelcome(utteranceId)

                            override fun onStop(
                                utteranceId: String, interrupted: Boolean
                            ) = finishIfWelcome(utteranceId)
                        }
                    )
                    // Let the voice service finish preparation / audio routing.
                    main.postDelayed({
                        if (active) speakGreeting()
                    }, 1_000L)
                }
            }
        } catch (error: RuntimeException) {
            Log.w(logTag, "Could not create TTS engine", error)
            closeSession()
        }
    }

    private fun speakGreeting() {
        val tts = engine ?: return closeSession()
        val granted = runCatching {
            audio.requestAudioFocus(focusRequest) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.getOrDefault(false)
        if (!granted) {
            Log.w(logTag, "Welcome audio focus denied")
            closeSession()
            return
        }
        focusGranted = true

        val params = Bundle().apply {
            putFloat(
                TextToSpeech.Engine.KEY_PARAM_VOLUME,
                preferences.volume.coerceIn(0.15f, 1.0f)
            )
        }
        // Exactly one TTS request, with its required stable utterance ID.
        val queued = runCatching {
            tts.speak(
                "Merhaba. EV Cockpit hazır. İyi yolculuklar.",
                TextToSpeech.QUEUE_FLUSH,
                params,
                UTTERANCE_ID
            ) == TextToSpeech.SUCCESS
        }.getOrDefault(false)
        if (queued) {
            main.postDelayed(speechTimeout, 15_000L)
        } else {
            Log.w(logTag, "TTS did not accept the greeting")
            closeSession()
        }
    }

    private fun finishIfWelcome(id: String) {
        if (id == UTTERANCE_ID) main.post { closeSession() }
    }

    private fun closeSession() {
        if (!active) return
        active = false
        audioDucking.end("welcome")
        initializationPending = false
        main.removeCallbacks(initTimeout)
        main.removeCallbacks(speechTimeout)

        val old = engine
        engine = null
        runCatching { old?.stop() }
        runCatching { old?.shutdown() }

        if (focusGranted) {
            focusGranted = false
            runCatching { audio.abandonAudioFocusRequest(focusRequest) }
        }
    }

    companion object {
        private const val UTTERANCE_ID = "welcome_greeting"
    }
}
```

## 9. `nativecockpit/src/main/java/com/evcockpit/presentation/media/RadioScreen.kt`

```kotlin
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
    val player: ExoPlayer? = remember(context) {
        // A media service failure should not prevent the screen from opening.
        runCatching {
            ExoPlayer.Builder(context).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true
                )
                setHandleAudioBecomingNoisy(true)
            }
        }.getOrNull()
    }
    LaunchedEffect(player, voiceDucked) {
        // Only the application-owned radio is lowered; restore the user level.
        player?.let { activePlayer ->
            runCatching { activePlayer.volume = if (voiceDucked) 0.2f else 1f }
        }
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
        val activePlayer = player
        if (activePlayer == null) {
            onDispose { }
        } else {
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
                if (event == Lifecycle.Event.ON_STOP) {
                    runCatching { activePlayer.pause() }
                }
            }
            runCatching { activePlayer.addListener(listener) }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                runCatching { activePlayer.removeListener(listener) }
                runCatching { activePlayer.release() }
            }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.radio_description), style = MaterialTheme.typography.bodySmall)
        if (player == null) {
            Text(stringResource(R.string.radio_playback_error))
        }
        currentName?.let { name ->
            Text(stringResource(R.string.radio_selected, name))
            if (buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (playbackFailed) Text(stringResource(R.string.radio_playback_error))
            Row {
                TextButton(onClick = {
                    player?.let { activePlayer ->
                        runCatching {
                            if (!playbackFailed && (playing || activePlayer.playWhenReady)) {
                                activePlayer.pause()
                            } else {
                                if (activePlayer.playerError != null) activePlayer.prepare()
                                playbackFailed = false
                                activePlayer.play()
                            }
                        }.onFailure { playbackFailed = true }
                    }
                }, enabled = player != null) {
                    Text(stringResource(if (playing || buffering) R.string.radio_pause else R.string.radio_play))
                }
                TextButton(onClick = {
                    runCatching { player?.stop() }
                    currentName = null
                    playbackFailed = false
                }, enabled = player != null) {
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
                    player?.let { activePlayer ->
                        runCatching {
                            currentName = station.name
                            playbackFailed = false
                            activePlayer.setMediaItem(MediaItem.fromUri(station.urlResolved))
                            activePlayer.prepare()
                            activePlayer.play()
                        }.onFailure { playbackFailed = true }
                    } ?: run { playbackFailed = true }
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
```

## 10. `nativecockpit/build.gradle.kts`

```kotlin
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
            // Debug variant for development; uses the same real GPS and OBD repositories.
            isMinifyEnabled = false
        }
        getByName("release") {
            isDebuggable = false
            // Production build: R8 code and resource shrinking enabled.
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
```

## 11. `nativecockpit/proguard-rules.pro`

```proguard
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

# Compatibility for old integrations that used a top-level obd package.
# The current implementation also has concrete data.obd and domain.usecase keeps.
-keep class com.evcockpit.obd.** { *; }

# Retain volatile state used by Kotlin coroutines under R8.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Real Bluetooth ELM327 state machine and Android Auto entrypoints.
-keep class com.evcockpit.domain.usecase.Elm327Session { *; }
-keep class com.evcockpit.data.repository.Elm327VehicleRepository { *; }
-keep class com.evcockpit.data.obd.** { *; }
-keep class com.evcockpit.car.** { *; }

# No -dontoptimize / -dontwarn. Missing classes remain build errors.
```

## 12. `.github/workflows/build-nativecockpit.yml`

```yaml
name: Native Cockpit · Kotlin Compose APK

on:
  workflow_dispatch:
  pull_request:
    branches:
      - feature/ev-cockpit
  push:
    branches:
      - feature/ev-cockpit

# Cancel obsolete builds when a newer commit is pushed to the same branch.
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

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
      - name: Validate audio, null-safety, and release certificate checks
        run: python3 scripts/check_final_release_hardening.py
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

      - name: Verify APK certificate matches owner's keystore
        shell: bash
        run: |
          set -euo pipefail
          apk="nativecockpit/build/outputs/apk/release/nativecockpit-release.apk"
          test -s "$apk"

          # Verify Android's signature formats; compare against the owner's JKS.
          "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose "$apk"
          "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs "$apk" > "$RUNNER_TEMP/apk-certs.txt"
          keytool -exportcert -keystore "$EYA_RELEASE_STORE_FILE" \
            -alias "$EYA_RELEASE_KEY_ALIAS" \
            -storepass:env EYA_RELEASE_STORE_PASSWORD \
            -file "$RUNNER_TEMP/owner-cert.der" >/dev/null

          python3 - <<'PY'
          import hashlib
          import os
          import re
          from pathlib import Path

          temp = Path(os.environ["RUNNER_TEMP"])
          actual = (temp / "apk-certs.txt").read_text(encoding="utf-8")
          match = re.search(
              r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", actual
          )
          if not match:
              raise SystemExit("Missing APK signing-certificate fingerprint")
          apk_sha = match.group(1).replace(":", "").lower()
          owner_sha = hashlib.sha256((temp / "owner-cert.der").read_bytes()).hexdigest()
          if apk_sha != owner_sha:
              raise SystemExit("APK signer does not match the existing owner keystore")
          print("APK signature verified and owner certificate matches")
          PY
          "$ANDROID_HOME/build-tools/36.0.0/aapt" dump badging "$apk" |
            grep -q "package: name='com.evcockpit'"
          cp "$apk" app-release.apk
          sha256sum app-release.apk > app-release.sha256
          ls -lh app-release.apk
          rm -f "$RUNNER_TEMP/owner-cert.der" "$RUNNER_TEMP/apk-certs.txt"

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
          rm -f "$RUNNER_TEMP/eya-release.jks" "$RUNNER_TEMP/eya-release.jks.enc" "$RUNNER_TEMP/owner-cert.der" "$RUNNER_TEMP/apk-certs.txt"
```

## 13. `scripts/check_final_release_hardening.py`

```python
#!/usr/bin/env python3
"""Regression checks for release signing, null-safe HUD, and in-app TTS ducking."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")

hud = read("nativecockpit/src/main/java/com/evcockpit/presentation/cockpit/CockpitScreen.kt")
sound = read("nativecockpit/src/main/java/com/evcockpit/presentation/sound/ActiveSoundManager.kt")
radio = read("nativecockpit/src/main/java/com/evcockpit/presentation/media/RadioScreen.kt")
voice = read("nativecockpit/src/main/java/com/evcockpit/presentation/voice/NativeVoiceGuide.kt")
welcome = read("nativecockpit/src/main/java/com/evcockpit/presentation/voice/WelcomeGreetingManager.kt")
app = read("nativecockpit/src/main/java/com/evcockpit/MainActivity.kt")
workflow = read(".github/workflows/build-nativecockpit.yml")
gradle = read("nativecockpit/build.gradle.kts")
rules = read("nativecockpit/proguard-rules.pro")

assert "if (validSoc != null)" in hud
assert "if (number != unknownValue)" in hud
assert "OBD Bağlantısı Bekleniyor" in hud
assert "vehicleRepository.telemetry" in app and "obd.connection" in app
assert "speed = if (connection.phase == ObdPhase.CONNECTED)" in app
assert "now - speedAtMs in 0L..10_000L" in sound
assert "openRawResource(resourceId)" in sound
assert "catch (error: Resources.NotFoundException)" in sound
assert "val knownRange" in hud
assert "catch (error: Exception)" in sound
assert "multiplier = if (ducked) 0.2f else 1f" in sound
assert "activePlayer.volume = if (voiceDucked) 0.2f else 1f" in radio
assert "runCatching { activePlayer.release() }" in radio
assert "runCatching { activePlayer.play()" not in radio  # playback is protected in a broader block
assert "startedDuckOwner?.let(audioSignal::begin)" in voice and "audioSignal::end" in voice
assert 'audioDucking.begin("welcome")' in welcome
assert 'audioDucking.end("welcome")' in welcome

assert "isMinifyEnabled = true" in gradle
assert "isShrinkResources = true" in gradle
for rule in (
    "-keep class dagger.hilt.** { *; }",
    "-keep class com.evcockpit.data.** { *; }",
    "-keep class org.maplibre.** { *; }",
    "-keep class androidx.media3.** { *; }",
    "-keep class com.evcockpit.obd.** { *; }",
    "-keep class com.evcockpit.data.obd.** { *; }",
):
    assert rule in rules, f"Missing compatibility rule: {rule}"

assert "-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }" in rules
assert "EYA_SIGNING_BUNDLE_BASE64: ${{ secrets.EYA_SIGNING_BUNDLE_BASE64 }}" in workflow
assert "APK signer does not match the existing owner keystore" in workflow
assert "apksigner\" verify --verbose" in workflow
assert "EV-Cockpit-Signed-Stable-Release" in workflow
print("OK: null-safe HUD, ducked players, R8 and signing identity verification")
```

