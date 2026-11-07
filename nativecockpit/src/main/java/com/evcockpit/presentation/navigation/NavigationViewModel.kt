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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
import com.evcockpit.data.weather.OpenMeteoWeatherRepository
import com.evcockpit.data.settings.AppConfigRepository
import com.evcockpit.data.settings.UserSettingsRepository
import com.evcockpit.domain.model.*
import com.evcockpit.domain.repository.LocationRepository
import com.evcockpit.domain.repository.RouteRepository
import com.evcockpit.domain.repository.VehicleRepository
import com.evcockpit.domain.usecase.NavigationSpeechBridge
import com.evcockpit.domain.usecase.RouteProgressTracker
import com.evcockpit.domain.usecase.RouteDeviationPolicy
import com.evcockpit.domain.usecase.WeatherChangeAlertGate
import com.evcockpit.domain.usecase.BaselineEnergyResult
import com.evcockpit.domain.usecase.RemainingRouteEnergyPolicy
import com.evcockpit.domain.usecase.VerifiedBatterySocPolicy
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
    private val weatherRepository: OpenMeteoWeatherRepository,
    private val appConfigRepository: AppConfigRepository,
    private val userSettings: UserSettingsRepository,
    audioDucking: AppAudioDucking
) : ViewModel() {
    private val prefs = VoicePreference(context)
    private val guide = NativeVoiceGuide(context, audioDucking)
    private val speechBridge = NavigationSpeechBridge()
    private val progress = RouteProgressTracker()
    private val deviation = RouteDeviationPolicy()
    private val _foreground = MutableStateFlow(false)
    private val _weatherAlerts = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val weatherAlerts = _weatherAlerts.asSharedFlow()
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
    // UI-owned selected charging stops; actual routing remains in RouteRepository.
    private val chargingStops = mutableListOf<ChargingStation>()

    /** Tick only expires previously measured values, never invents a new GPS fix. */
    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000L)
        }
    }
    private val cockpit = combine(
        sources, routes.currentRoute, ticker, appConfigRepository.configuration
    ) { source, route, now, configuration ->
        val usableGps = source.gps?.takeIf { it.usable(now) }
        val lastGpsAt = source.gps?.recordedAtMillis
            ?.takeIf { it <= now && it >= gpsWaitSinceMs } ?: gpsWaitSinceMs
        val telemetry = source.telemetry
        val baseRoute = progress.enrich(route, usableGps, now)
        val batteryReading = VerifiedBatterySocPolicy.read(telemetry, now)
        val energy = if (route?.routeId != null) RemainingRouteEnergyPolicy.estimate(
            baseRoute, telemetry, configuration.vehicle, now
        ) else null
        val visualRoute = if (energy is BaselineEnergyResult.Estimate) {
            baseRoute?.copy(
                estimatedArrivalSocPercent = energy.arrivalSocPercent
                    .takeIf { it.isFinite() && it in 0.0..100.0 }
            )
        } else baseRoute?.copy(estimatedArrivalSocPercent = null)
        CockpitUiState(
            socPercent = batteryReading?.percent,
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
            ) CoreIssue.LOCATION_MISSING.messageTr else null,
            route = visualRoute,
            gpsFix = usableGps
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
            userSettings.settings.collect { settings ->
                guide.configureNavigationVoice(
                    settings.ttsVoiceName, settings.ttsSpeechRate
                )
            }
        }
        viewModelScope.launch {
            snapshotFlow { guide.status to guide.lastError }.collect { (status, error) ->
                _voice.update { it.copy(status = status, error = error) }
            }
        }
        // Spoken forecast is a model-based ETA warning, NOT an observation.
        // The route-scoped gate speaks only when severity rises, never every
        // refresh; navigation maneuvers and Android Auto retain precedence.
        viewModelScope.launch {
            combine(
                routes.currentRoute,
                _foreground,
                CarNavigationOwnership.navigating,
                _voice
            ) { route, foreground, carNavigating, voice ->
                if (foreground && !carNavigating && voice.enabled) route?.routeId else null
            }.distinctUntilChanged().collectLatest { activeId ->
                if (activeId == null) return@collectLatest
                val changeGate = WeatherChangeAlertGate()
                while (true) {
                    val now = System.currentTimeMillis()
                    val forecast = weatherRepository.latestForecast.value
                    val route = uiState.value.cockpit.route
                    val setting = _voice.value
                    val risk = if (forecast.routeId == activeId &&
                        route?.routeId == activeId
                    ) changeGate.increasedWarning(
                        forecast, now, route.validProgress()
                    ) else null
                    if (risk != null && route != null && _foreground.value &&
                        (route.nextTurnMeters == null || route.nextTurnMeters > 650.0) &&
                        setting.enabled && setting.status in setOf(
                            NativeVoiceGuide.Status.READY,
                            NativeVoiceGuide.Status.AUDIO_FOCUS_DENIED
                        )
                    ) {
                        val message = "Rota üzerindeki saatlik hava tahmininde " +
                            "${risk.summaryTr} hava bekleniyor. Yol koşullarına dikkat."
                        if (guide.speakIfIdle(message, setting.volume)) {
                            _weatherAlerts.tryEmit(message)
                        }
                    }
                    delay(60_000L)
                }
            }
        }
        // Only foreground phone guidance may initiate an automatic re-route.
        // Five new, accurate GPS measurements must exceed the 150 m boundary.
        viewModelScope.launch {
            combine(
                routes.currentRoute, locationRepository.gpsFix,
                _foreground, CarNavigationOwnership.navigating
            ) { route, fix, foreground, carNavigating ->
                DeviationInputs(route, fix, foreground && !carNavigating)
            }.collect { state ->
                if (state.route == null) deviation.reset()
                else if (state.allowed && !_planner.value.planning &&
                    deviation.observe(state.route, state.fix, System.currentTimeMillis())
                ) {
                    state.fix?.let { handleDeviation(state.route, it) }
                }
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

    private data class DeviationInputs(
        val route: RouteSnapshot?, val fix: GpsFix?, val allowed: Boolean
    )

    private fun handleDeviation(route: RouteSnapshot, fix: GpsFix) {
        if (routes.currentRoute.value?.routeId != route.routeId ||
            !fix.usable(System.currentTimeMillis()) || fix.accuracyMeters > 35f
        ) return
        val target = _planner.value.destinationSelected
        if (target == null) {
            _planner.update {
                it.copy(error = "Rotadan çıkıldı. Hedef eksik; yeni rota için hedef seçin.")
            }
            return
        }
        // Preserve user-selected charging-stop intent. Never silently skip or
        // route back through a completed stop. Ask for manual reconciliation.
        if (chargingStops.isNotEmpty()) {
            _planner.update {
                it.copy(error = "Rotadan çıkıldı. Şarj duraklarını kontrol edip rotayı yeniden başlatın.")
            }
            if (_voice.value.enabled) guide.speakIfIdle(
                "Rotadan çıktınız. Şarj duraklarınızı kontrol edin.", _voice.value.volume
            )
            return
        }
        planningJob?.cancel()
        planningJob = viewModelScope.launch {
            _planner.update { it.copy(planning = true, error = null) }
            if (_voice.value.enabled) guide.speakIfIdle(
                "Rotadan çıktınız. Rota yeniden hesaplanıyor.", _voice.value.volume
            )
            try {
                when (val result = routes.calculateVia(fix.coordinate, target, emptyList())) {
                    is RouteCalculationResult.Success -> {
                        _planner.update {
                            it.copy(
                                planning = false, error = null, originFromGps = true,
                                originSelected = null, gpsOrigin = fix.coordinate,
                                originText = "Geçerli GPS konumu"
                            )
                        }
                    }
                    is RouteCalculationResult.Failure -> {
                        _planner.update {
                            it.copy(planning = false,
                                error = "Rota yeniden hesaplanamadı: " + result.message.take(120))
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                _planner.update {
                    it.copy(planning = false,
                        error = "Yeni rota alınamadı. Bağlantınızı kontrol edin.")
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
            reportGpsError("GPS konumu alınamadı. Konum ve hassasiyet ayarlarını kontrol edin.")
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
        } else _gpsError.value = "GPS verisi yeterince güncel veya hassas değil."

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
            guide.speak("EV Cockpit. Türkçe ses testi.", voice.volume)
        }
    }

    fun editOrigin(value: String) {
        val text = value.take(100)
        originJob?.cancel(); gpsJob?.cancel(); planningJob?.cancel()
        routes.clear() // never show an obsolete route for a newly edited origin
        _planner.update { it.copy(originText = text, originSelected = null,
            originFromGps = false, gpsOrigin = null, locatingGps = false,
            originMatches = emptyList(), error = null, planning = false) }
        // Public Nominatim explicitly forbids search-as-you-type requests.
    }
    fun editDestination(value: String) {
        val text = value.take(100)
        destinationJob?.cancel(); planningJob?.cancel()
        routes.clear()
        _planner.update { it.copy(destinationText = text, destinationSelected = null,
            destinationMatches = emptyList(), planning = false, error = null) }
        // No search request until the user taps the Search button.
    }
    fun requestOriginSearch() {
        originJob?.cancel()
        originJob = search(_planner.value.originText, true)
    }

    fun requestDestinationSearch() {
        destinationJob?.cancel()
        destinationJob = search(_planner.value.destinationText, false)
    }

    private fun search(value: String, origin: Boolean): Job = viewModelScope.launch {
        if (value.trim().length < 3) {
            _planner.update { if (origin) it.copy(searchingOrigin = false)
                else it.copy(searchingDestination = false) }
            return@launch
        }
        // NominatimGeocoder serializes explicit requests with a 1 request/s limit.
        _planner.update { if (origin) it.copy(searchingOrigin = true)
            else it.copy(searchingDestination = true) }
        try {
            val found = routes.searchResult(value).getOrThrow()
            val error = if (found.isEmpty()) "Adres bulunamadı. Daha ayrıntılı bir konum girin." else null
            _planner.update { if (origin) it.copy(originMatches = found, searchingOrigin = false, error = error)
                else it.copy(destinationMatches = found, searchingDestination = false, error = error) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            val message = when (error) {
                is UnknownHostException -> "İnternet bağlantısı yok"
                is SocketTimeoutException -> "Adres arama servisi zaman aşımına uğradı"
                is IOException -> error.message ?: "Adres servisine ulaşılamadı"
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
                        originText = "Geçerli GPS konumum", error = null
                    ) else state.copy(locatingGps = false, originFromGps = false,
                        error = result.exceptionOrNull()?.message ?: "GPS konumu alınamadı")
                }
            } catch (cancel: CancellationException) { throw cancel }
        }
    }
    fun locationPermissionDenied() {
        _gpsError.value = "Konum izni verilmedi."
        gpsJob?.cancel()
        _planner.update { it.copy(locatingGps = false, originFromGps = false,
            gpsOrigin = null, error = "Konum izni verilmedi; başlangıç noktasını elle seçebilirsiniz") }
    }
    fun plan() {
        if (!_planner.value.canCalculate) return
        chargingStops.clear()
        planningJob?.cancel()
        planningJob = viewModelScope.launch {
            val state = _planner.value
            val start = state.startLocation
            val target = state.destinationSelected
            if (start == null || target == null) {
                _planner.update { it.copy(error = "Başlangıç ve varış seçilmedi") }
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
                                    "İnternet bağlantısı yok"
                                else -> "Rota hesaplanamadı (OSRM): " + result.message.take(350)
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
    /** Recalculates through an actual EPDK station coordinate, not a drawn straight line.
     * Existing route is retained when the OSRM request fails.
     */
    fun addChargingStop(station: ChargingStation) {
        val planner = _planner.value
        val origin = planner.startLocation
        val target = planner.destinationSelected
        if (routes.currentRoute.value == null || origin == null || target == null) {
            _planner.update {
                it.copy(error = "Önce başlangıç ve varış içeren bir rota çiz.")
            }
            return
        }
        if (!station.coordinate.valid() || chargingStops.any { it.id == station.id }) {
            _planner.update { it.copy(error = "Bu şarj durağı zaten eklenmiş veya koordinatı geçersiz.") }
            return
        }
        if (chargingStops.size >= 5) {
            _planner.update { it.copy(error = "En fazla beş şarj durağı ekleyebilirsin.") }
            return
        }
        val candidates = chargingStops.toList() + station
        planningJob?.cancel()
        planningJob = viewModelScope.launch {
            _planner.update { it.copy(planning = true, error = null) }
            try {
                when (val result = routes.calculateVia(
                    origin, target, candidates.map { it.coordinate }
                )) {
                    is RouteCalculationResult.Success -> {
                        chargingStops.clear()
                        chargingStops.addAll(candidates)
                        speechBridge.reset()
                        _planner.update { it.copy(planning = false, error = null) }
                    }
                    is RouteCalculationResult.Failure -> {
                        _planner.update { it.copy(planning = false,
                            error = "Durak eklenemedi: ${result.message.take(100)}") }
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _planner.update { it.copy(planning = false,
                    error = "Durak hesaplanamadı: ${error.message?.take(90) ?: "bağlantı hatası"}") }
            }
        }
    }

    fun selectAlternative(index: Int) { routes.choose(index); speechBridge.reset() }
    fun clearRoute() {
        chargingStops.clear()
        originJob?.cancel(); destinationJob?.cancel(); gpsJob?.cancel(); planningJob?.cancel()
        routes.clear(); deviation.reset(); speechBridge.reset(); guide.stop(); _planner.value = RoutePlannerUiState()
    }
    override fun onCleared() {
        guide.close()
        locationRepository.stop()
        super.onCleared()
    }
}
