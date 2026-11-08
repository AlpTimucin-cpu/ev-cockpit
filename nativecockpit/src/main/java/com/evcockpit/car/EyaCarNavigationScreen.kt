package com.evcockpit.car

import android.util.Log
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Alert
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.MessageInfo
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.model.RouteWeatherState
import com.evcockpit.domain.model.ChargingCatalog
import com.evcockpit.domain.model.RoadDetourCatalog
import com.evcockpit.domain.model.VehicleProfileConfig
import com.evcockpit.domain.model.VehicleTelemetry
import com.evcockpit.domain.usecase.DCCorridorCandidate
import com.evcockpit.data.settings.AppConfigRepository
import com.evcockpit.data.repository.OsrmRoadDetourRepository
import com.evcockpit.data.weather.OpenMeteoWeatherRepository
import com.evcockpit.domain.usecase.WeatherChangeAlertGate
import com.evcockpit.domain.model.RouteSnapshot
import com.evcockpit.domain.model.validRouteGeometry
import com.evcockpit.domain.repository.RouteRepository
import com.evcockpit.domain.repository.ChargingRepository
import com.evcockpit.domain.repository.VehicleRepository
import com.evcockpit.domain.usecase.ManeuverGuidancePolicy
import com.evcockpit.domain.usecase.RouteProgressTracker
import com.evcockpit.presentation.voice.NativeVoiceGuide
import com.evcockpit.presentation.voice.AppAudioDucking
import com.evcockpit.presentation.voice.VoicePreference
import com.evcockpit.presentation.voice.WelcomeGreetingManager
import kotlin.math.roundToInt

/** Real OSRM geometry + measured GPS only. Foreground Android Auto session, not a navigation FGS. */
class EyaCarNavigationScreen(
    context: CarContext,
    private val routes: RouteRepository,
    private val charging: ChargingRepository,
    private val vehicle: VehicleRepository,
    private val config: AppConfigRepository,
    private val detours: OsrmRoadDetourRepository,
    private val weather: OpenMeteoWeatherRepository,
    ducking: AppAudioDucking,
    private val welcome: WelcomeGreetingManager
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
    private val loadingGate = CarHostLoadingGate()
    private val matchPolicy = CarRouteMatchPolicy()
    private var routeMatch = CarRouteMatch.NO_VALID_FIX
    private var offRouteAnnouncementSent = false
    private val navigationSessionToken = Any()
    private var work: CoroutineScope? = null
    private var route: RouteSnapshot? = routes.currentRoute.value
    private var fix: GpsFix? = null
    private var guidance: CarGuidance? = null
    private var summary = CarRouteSummary.from(route, null, System.currentTimeMillis())
    private var activeRouteId: String? = null
    private var withoutGpsSinceMs: Long = 0L
    private var closed = false
    private var chargingVisible = false
    private var selectedChargingStationId: String? = null
    private var chargingLoadStarted = false
    private var chargingCatalog = charging.catalog.value
    private var chargingCandidates: List<DCCorridorCandidate> = emptyList()
    private var preparedChargingRouteId: String? = null
    private var roadDetours = RoadDetourCatalog()
    private var vehicleTelemetry = VehicleTelemetry()
    private var vehicleProfile = VehicleProfileConfig()
    private var chargingDisplay = CarChargeList()
    private var chargingModelJob: Job? = null
    private var detourVerificationJob: Job? = null
    private var detourVerificationKey: String? = null
    private var lastChargingFixTimestamp: Long? = null

    // Foreground-only ETA forecasts: one refresh per 30 minutes per route.
    private val weatherRefreshGate = CarWeatherRefreshGate()
    private var weatherAlertGate = WeatherChangeAlertGate()
    private var weatherRouteId: String? = null
    private var weatherState = RouteWeatherState()
    private var weatherJob: Job? = null
    private var weatherVisible = false
    private var weatherAlertUntilMs: Long = 0L
    private var weatherAlertMessage: String? = null
    private var pendingWeatherSpeech: String? = null
    private var weatherSpeechUntilMs: Long = 0L
    private var lastWeatherPageMinute: Long = -1L

    private companion object {
        const val WEATHER_ALERT_ID = 0xEA12
        const val WEATHER_ALERT_DURATION_MS = 10_000L
    }

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
                if (closed || work != null) return // avoid duplicate GPS/ticker collectors
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
                    combine(routes.currentRoute, charging.catalog) { current, catalog ->
                        current to catalog
                    }.collectLatest { (current, catalog) ->
                        val filtered = withContext(Dispatchers.Default) {
                            CarChargingListPolicy.candidates(current, catalog)
                        }
                        // Publish catalog/candidates atomically after the matching
                        // route's indexing work. Never render old-route chargers.
                        val changedEvidenceScope =
                            preparedChargingRouteId != current?.routeId ||
                                chargingCatalog.verifiedAtUtc != catalog.verifiedAtUtc
                        chargingCatalog = catalog
                        chargingCandidates = filtered
                        preparedChargingRouteId = current?.routeId
                        if (changedEvidenceScope) {
                            roadDetours = RoadDetourCatalog()
                            detourVerificationKey = null
                        }
                        refreshChargingModel()
                        requestDetourVerification()
                    }
                }
                scope.launch {
                    combine(tracker.gpsFix, vehicle.telemetry, config.configuration) {
                        measured, telemetry, settings -> Triple(measured, telemetry, settings)
                    }.collect { (_, telemetry, settings) ->
                        vehicleTelemetry = telemetry
                        vehicleProfile = settings.vehicle
                        if (chargingVisible) refreshChargingModel()
                    }
                }
                scope.launch {
                    combine(routes.currentRoute, tracker.gpsFix, ticker) { r, f, now ->
                        Triple(r, f?.takeIf { it.usable(now) }, now)
                    }.collect { (r, f, now) ->
                        route = r
                        fix = f
                        surface.update(r, f)
                        if (chargingVisible && lastChargingFixTimestamp != f?.recordedAtMillis) {
                            lastChargingFixTimestamp = f?.recordedAtMillis
                            refreshChargingModel()
                        }
                        val fresh = guidancePolicy.snapshot(r, f, now)
                        guidance = fresh
                        val previousMatch = routeMatch
                        routeMatch = matchPolicy.observe(r, f, now)
                        if (routeMatch != CarRouteMatch.CONFIRMED_OFF_ROUTE) {
                            offRouteAnnouncementSent = false
                        }
                        val newSummary = CarRouteSummary.from(r, f, now)
                        if (newSummary != summary || previousMatch != routeMatch) {
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
                chargingModelJob?.cancel(); chargingModelJob = null
                detourVerificationJob?.cancel(); detourVerificationJob = null
                detourVerificationKey = null
                weatherJob?.cancel(); weatherJob = null
                dismissWeatherAlert()
                weatherJob?.cancel(); weatherJob = null
                dismissWeatherAlert()
                work?.cancel(); work = null
                tracker.stop()
                surface.release()
                manager.clearNavigationManagerCallback()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                endNavigation()
                chargingModelJob?.cancel(); chargingModelJob = null
                detourVerificationJob?.cancel(); detourVerificationJob = null
                detourVerificationKey = null
                work?.cancel(); work = null
                tracker.stop()
                surface.release()
                if (!closed) { closed = true; voice.close() }
            }
        })
    }

    private fun beginNavigation() {
        if (activeRouteId != null) return
        val now = System.currentTimeMillis()
        val current = guidance?.takeIf {
            it.gpsRecordedAtMs <= now && now - it.gpsRecordedAtMs <= 5_000L
        } ?: return // no stale tap-to-start from a previously rendered host template
        if (fix?.usable(now) != true || route?.routeId != current.routeId) return
        try {
            manager.navigationStarted()
            // Priority: navigation voice pre-empts optional welcome greeting.
            welcome.stopForNavigation()
            activeRouteId = current.routeId
            CarNavigationOwnership.claim(navigationSessionToken)
            withoutGpsSinceMs = 0L
            gate.clear()
            loadingGate.clear()
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
        val wasOwner = CarNavigationOwnership.release(navigationSessionToken)
        voice.stop()
        speechPolicy.reset()
        gate.clear()
        loadingGate.clear()
        matchPolicy.reset()
        routeMatch = CarRouteMatch.NO_VALID_FIX
        offRouteAnnouncementSent = false
        if (wasActive) {
            // Old Screen must never end a newer Session's host navigation.
            if (wasOwner) {
                try { manager.navigationEnded() }
                catch (e: RuntimeException) { Log.w("EyaCarNavigation", "Host navigation end", e) }
            }
            invalidate()
        }
    }

    private fun updateActiveNavigation(
        currentRoute: RouteSnapshot?, currentFix: GpsFix?,
        current: CarGuidance?, now: Long
    ) {
        val started = activeRouteId ?: return
        if (!CarNavigationOwnership.owns(navigationSessionToken)) {
            endNavigation() // another Android Auto screen acquired the session
            return
        }
        if (currentRoute?.routeId != started || !validRouteGeometry(currentRoute.routePoints)) {
            endNavigation() // new alternative means a new explicit trip
            return
        }
        if (current == null || current.routeId != started ||
            routeMatch != CarRouteMatch.ON_ROUTE
        ) {
            // A recent accurate GPS fix OUTSIDE the route is not a GPS outage.
            // Do not end a trip because the user took a different road.
            val measured = currentFix?.takeIf {
                it.usable(now) && it.accuracyMeters <= 35f
            }
            if (measured == null) {
                if (withoutGpsSinceMs == 0L) withoutGpsSinceMs = now
            } else withoutGpsSinceMs = 0L

            if (loadingGate.shouldPublish(now)) {
                try {
                    // Never retain the last turn or ETA while route match is lost.
                    manager.updateTrip(CarTripFactory.loading())
                    loadingGate.onPublished()
                    gate.clear() // next valid maneuver must replace the loading Trip
                } catch (error: RuntimeException) {
                    loadingGate.onFailed(now)
                    Log.w("EyaCarNavigation", "Host loading trip rejected", error)
                }
            }
            if (routeMatch == CarRouteMatch.CONFIRMED_OFF_ROUTE) {
                if (!offRouteAnnouncementSent && voicePrefs.enabled &&
                    voice.status == NativeVoiceGuide.Status.READY) {
                    voice.stop()
                    offRouteAnnouncementSent = voice.speak(
                        "Rotadan çıktınız. Güvenli yerde telefondan rotayı yeniden planlayın. " +
                            "Şarj duraklarınızı kontrol edin.", voicePrefs.volume
                    )
                }
            } else {
                voice.stop()
            }
            if (withoutGpsSinceMs != 0L && now - withoutGpsSinceMs >= 15_000L) {
                endNavigation()
            }
            return
        }
        withoutGpsSinceMs = 0L
        if (gate.shouldPublish(current, now)) {
            try {
                manager.updateTrip(CarTripFactory.trip(current))
                gate.onPublished(current, now)
                loadingGate.clear()
                invalidate()
            } catch (e: RuntimeException) {
                gate.onPublishFailed(now)
                Log.w("EyaCarNavigation", "Trip update rejected; bounded retry", e)
            }
        }
        if (voicePrefs.enabled && currentFix != null &&
            voice.status == NativeVoiceGuide.Status.READY) {
            val enriched = progress.enrich(currentRoute, currentFix, now)
            val announcement = speechPolicy.nextAnnouncement(enriched, currentFix, now)
            if (announcement != null) {
                // Delayed TTS callbacks must never acknowledge a later maneuver.
                val cueToken = speechPolicy.pendingCueToken
                val queued = voice.speak(
                    announcement, voicePrefs.volume,
                    onUtteranceStarted = {
                        speechPolicy.confirmSpoken(
                            System.currentTimeMillis(), cueToken
                        )
                    },
                    onUtteranceFailed = {
                        speechPolicy.cancelPending(cueToken)
                    }
                )
                if (!queued) speechPolicy.cancelPending(cueToken)
            }
        } else if (!voicePrefs.enabled) voice.stop()
    }


    private fun resetWeatherForRoute(nextRouteId: String?) {
        if (weatherRouteId == nextRouteId) return
        weatherJob?.cancel()
        weatherJob = null
        weatherRouteId = nextRouteId
        weatherState = RouteWeatherState(routeId = nextRouteId)
        weatherRefreshGate.reset()
        weatherAlertGate = WeatherChangeAlertGate()
        pendingWeatherSpeech = null
        weatherSpeechUntilMs = 0L
        dismissWeatherAlert()
        if (weatherVisible) invalidate()
    }

    /**
     * Only the active Car Screen can request forecasts. A valid, actual GPS
     * projection and a real route are prerequisites. No background worker.
     */
    private fun maybeRefreshWeather(
        currentRoute: RouteSnapshot?,
        currentFix: GpsFix?,
        nowMs: Long,
        manual: Boolean = false
    ) {
        val scope = work ?: return
        val routeId = currentRoute?.routeId?.takeIf { it.isNotBlank() } ?: return
        val fix = currentFix?.takeIf {
            it.usable(nowMs) && it.accuracyMeters <= 35f
        } ?: return
        val projected = progress.enrich(currentRoute, fix, nowMs)
        val valid = projected?.validProgress() != null &&
            routeMatch == CarRouteMatch.ON_ROUTE
        if (!weatherRefreshGate.shouldRefresh(routeId, valid, nowMs, manual)) return
        if (weatherJob?.isActive == true) return
        weatherRefreshGate.markAttempt(routeId, nowMs)
        val matched = requireNotNull(projected)
        if (weatherState.points.isEmpty()) {
            weatherState = RouteWeatherState(routeId = routeId, loading = true)
            if (weatherVisible) invalidate()
        }
        weatherJob = scope.launch {
            val result = weather.routeWeather(matched)
            if (routes.currentRoute.value?.routeId != routeId || weatherRouteId != routeId)
                return@launch
            weatherState = result.fold(
                onSuccess = { it },
                onFailure = {
                    val message = "Open-Meteo yenilenemedi. Son tahmin korunuyor."
                    if (weatherState.points.isNotEmpty()) weatherState.copy(
                        loading = false, error = message
                    ) else RouteWeatherState(routeId = routeId, error = message)
                }
            )
            if (weatherVisible) invalidate()
        }
    }

    /** Notify only a newly increased severe forecast risk, never every tick. */
    private fun maybeAnnounceWeather(
        currentRoute: RouteSnapshot?,
        currentFix: GpsFix?,
        nowMs: Long
    ) {
        val started = activeRouteId ?: return
        if (chargingVisible || weatherVisible || !CarNavigationOwnership.owns(navigationSessionToken) ||
            routeMatch != CarRouteMatch.ON_ROUTE || weatherRouteId != started ||
            currentRoute?.routeId != started
        ) return
        val enriched = progress.enrich(currentRoute, currentFix, nowMs)
        val hazard = weatherAlertGate.increasedWarning(
            weatherState, nowMs, enriched?.validProgress()
        )
        if (hazard != null) {
            val message = CarWeatherPresentationPolicy.warningText(hazard)
            weatherAlertMessage = message
            weatherAlertUntilMs = nowMs + WEATHER_ALERT_DURATION_MS
            pendingWeatherSpeech = message
            weatherSpeechUntilMs = nowMs + 2L * 60_000L
            if (carContext.carAppApiLevel >= 5) {
                val alert = Alert.Builder(
                    WEATHER_ALERT_ID, CarText.create("Rota hava tahmini"),
                    WEATHER_ALERT_DURATION_MS
                ).setSubtitle(CarText.create(message.take(110))).build()
                try {
                    carContext.getCarService(AppManager::class.java).showAlert(alert)
                } catch (error: RuntimeException) {
                    Log.w("EyaCarNavigation", "Weather Alert host rejected", error)
                }
            } else {
                // Car API 1-4 cannot show Alert. OnGetTemplate uses MessageInfo
                // in a short-lived NavigationTemplate while the map remains live.
                invalidate()
            }
        }
        maybeSpeakPendingWeather(currentFix, nowMs)
        if (weatherAlertUntilMs != 0L && nowMs >= weatherAlertUntilMs) {
            weatherAlertUntilMs = 0L
            weatherAlertMessage = null
            if (carContext.carAppApiLevel < 5) invalidate()
        }
    }

    private fun maybeSpeakPendingWeather(fix: GpsFix?, nowMs: Long) {
        val phrase = pendingWeatherSpeech ?: return
        if (nowMs > weatherSpeechUntilMs) {
            pendingWeatherSpeech = null
            return
        }
        if (!voicePrefs.enabled || voice.status != NativeVoiceGuide.Status.READY ||
            fix?.usable(nowMs) != true || routeMatch != CarRouteMatch.ON_ROUTE ||
            (guidance?.nextMeters ?: 0.0) <= 650.0
        ) return // maneuver speech always has precedence; no audio interruption
        if (voice.speakIfIdle(phrase, voicePrefs.volume)) {
            pendingWeatherSpeech = null
        }
    }

    private fun dismissWeatherAlert() {
        weatherAlertMessage = null
        weatherAlertUntilMs = 0L
        if (carContext.carAppApiLevel >= 5) {
            runCatching {
                carContext.getCarService(AppManager::class.java)
                    .dismissAlert(WEATHER_ALERT_ID)
            }
        }
    }

    private fun showWeather() {
        if (route?.routeId.isNullOrBlank()) return
        chargingVisible = false
        selectedChargingStationId = null
        chargingModelJob?.cancel()
        detourVerificationJob?.cancel()
        detourVerificationJob = null
        detourVerificationKey = null
        surface.updateChargingStations(emptyList())
        weatherVisible = true
        maybeRefreshWeather(route, fix, System.currentTimeMillis())
        invalidate()
    }

    private fun hideWeather() {
        weatherVisible = false
        invalidate()
    }

    private fun weatherTemplate(): Template {
        val now = System.currentTimeMillis()
        val limit = if (carContext.carAppApiLevel >= 2) runCatching {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PANE)
                .coerceIn(1, 4)
        }.getOrDefault(2) else 2
        val content = CarWeatherPresentationPolicy.display(
            weatherState, route?.routeId, now, (limit - 1).coerceAtLeast(0)
        )
        val pane = Pane.Builder().addRow(
            Row.Builder().setTitle("Open-Meteo · saatlik TAHMİN")
                .addText(content.provenance.take(130)).build()
        )
        if (content.rows.isEmpty()) {
            if (limit >= 2) pane.addRow(
                Row.Builder().setTitle("Veri durumu")
                    .addText((content.message ?: "Tahmin kullanılamıyor").take(130)).build()
            )
        } else {
            content.rows.forEach { item ->
                pane.addRow(Row.Builder().setTitle(item.title.take(80))
                    .addText(item.description.take(150)).build())
            }
        }
        if (!route?.routeId.isNullOrBlank()) {
            pane.addAction(Action.Builder().setTitle("Rota şarjları")
                .setOnClickListener {
                    weatherVisible = false
                    showChargingList()
                }.build())
        }
        pane.addAction(Action.Builder().setTitle("Rotaya dön")
            .setOnClickListener { hideWeather() }.build())
        val template = PaneTemplate.Builder(pane.build())
        if (carContext.carAppApiLevel < 7) {
            @Suppress("DEPRECATION")
            return template.setTitle("Rota hava tahmini").build()
        }
        return navigationMapContent(template.setHeader(
            Header.Builder().setTitle("Rota hava tahmini").build()
        ).build())
    }

    private fun legacyWeatherWarningTemplate(message: String): Template {
        val close = Action.Builder().setTitle("Kapat")
            .setOnClickListener {
                weatherAlertUntilMs = 0L
                weatherAlertMessage = null
                invalidate()
            }.build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(close).build())
            .setNavigationInfo(MessageInfo.Builder(message.take(130)).build())
            .build()
    }

    private fun showChargingList() {
        if (route == null || !validRouteGeometry(route!!.routePoints)) return
        chargingVisible = true
        selectedChargingStationId = null
        if (!chargingLoadStarted || chargingCatalog.error != null) {
            chargingLoadStarted = true
            work?.launch { charging.refreshResult() }
        }
        refreshChargingModel()
        requestDetourVerification()
        invalidate()
    }

    /**
     * One request per unique route+catalog+candidate set. Do not multiply
     * network requests when GPS emits or the host rerenders a template.
     * The underlying verifier disallows the shared public OSRM demo.
     */
    private fun requestDetourVerification() {
        val current = route ?: return
        if (!chargingVisible || chargingCatalog.loading || chargingCatalog.staleCache ||
            chargingCandidates.isEmpty() || current.routeId.isNullOrBlank() ||
            preparedChargingRouteId != current.routeId
        ) return
        val key = current.routeId + "|" + chargingCatalog.verifiedAtUtc + "|" +
            chargingCandidates.joinToString(",") { it.station.id }
        if (detourVerificationKey == key) return
        detourVerificationKey = key
        detourVerificationJob?.cancel()
        val currentCandidates = chargingCandidates
        detourVerificationJob = work?.launch {
            val verified = detours.inspect(current, currentCandidates)
            if (route?.routeId != current.routeId || !chargingVisible) return@launch
            roadDetours = verified
            refreshChargingModel()
        }
    }

    private fun hideChargingList() {
        chargingVisible = false
        selectedChargingStationId = null
        chargingModelJob?.cancel()
        detourVerificationJob?.cancel()
        detourVerificationJob = null
        detourVerificationKey = null
        surface.updateChargingStations(emptyList())
        invalidate()
    }

    private fun refreshChargingModel() {
        if (!chargingVisible) return
        val scope = work ?: return
        chargingModelJob?.cancel()
        val r = route
        val source = chargingCatalog
        val candidates = chargingCandidates.takeIf {
            preparedChargingRouteId == r?.routeId
        } ?: emptyList()
        val currentFix = fix
        val telemetry = vehicleTelemetry
        val profile = vehicleProfile
        val roads = roadDetours
        val now = System.currentTimeMillis()
        val cap = chargingHostLimit()
        chargingModelJob = scope.launch {
            val generated = withContext(Dispatchers.Default) {
                CarChargingListPolicy.build(
                    r, source, candidates, currentFix, telemetry, profile, roads,
                    now, cap
                )
            }
            // A cancelled calculation cannot publish a model for an older route.
            if (routes.currentRoute.value?.routeId != r?.routeId) return@launch
            chargingDisplay = generated
            surface.updateChargingStations(generated.items.map { it.station })
            if (selectedChargingStationId != null &&
                generated.items.none { it.station.id == selectedChargingStationId }
            ) selectedChargingStationId = null
            invalidate()
        }
    }

    private fun chargingHostLimit(): Int {
        if (carContext.carAppApiLevel < 2) return 6
        val kind = if (carContext.carAppApiLevel >= 7)
            ConstraintManager.CONTENT_LIMIT_TYPE_LIST
        else ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST
        return runCatching {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(kind).coerceIn(0, 12)
        }.getOrDefault(0) // Fail closed on host IPC failures.
    }

    private fun navigationMapContent(content: Template): Template {
        if (carContext.carAppApiLevel < 7) return content
        return MapWithContentTemplate.Builder()
            .setContentTemplate(content)
            .build()
    }

    private fun chargingPane(text: String, backToList: Boolean = false): Template {
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle("Şarj bilgisi")
                .addText(text.take(180)).build())
            .addAction(Action.Builder().setTitle("Geri")
                .setOnClickListener {
                    if (backToList) {
                        selectedChargingStationId = null
                        invalidate()
                    } else hideChargingList()
                }.build())
        val content = PaneTemplate.Builder(pane.build())
        if (carContext.carAppApiLevel < 7) {
            @Suppress("DEPRECATION")
            return content.setTitle("Rota şarjları").build()
        }
        return navigationMapContent(content.setHeader(
            Header.Builder().setTitle("Rota şarjları").build()
        ).build())
    }

    private fun chargerTemplate(): Template {
        val selected = chargingDisplay.items.firstOrNull {
            it.station.id == selectedChargingStationId
        }
        if (selected != null) {
            val pane = Pane.Builder()
                .addRow(Row.Builder()
                    .setTitle(selected.station.name.trim().replace("\n", " ").take(75))
                    .addText(CarChargingListPolicy.summary(selected))
                    .addText(chargingDisplay.catalogLabel.take(100))
                    .build())
                .addRow(Row.Builder().setTitle("Fiyat ve batarya")
                    .addText(CarChargingListPolicy.detail(selected).take(150))
                    .build())
                .addAction(Action.Builder().setTitle("Listeye dön")
                    .setOnClickListener {
                        selectedChargingStationId = null
                        invalidate()
                    }.build())
            val detailTemplate = PaneTemplate.Builder(pane.build())
            if (carContext.carAppApiLevel < 7) {
                @Suppress("DEPRECATION")
                return detailTemplate.setTitle("EPDK istasyon").build()
            }
            return navigationMapContent(detailTemplate.setHeader(
                Header.Builder().setTitle("EPDK istasyon").build()
            ).build())
        }
        val items = chargingDisplay.items
        if (items.isEmpty()) return chargingPane(
            chargingDisplay.message ?: "İstasyon bilgisi kullanılamıyor."
        )
        // Legacy navigation-place lists require an actual current-location
        // DistanceSpan; never manufacture distance when GPS is absent.
        if (carContext.carAppApiLevel < 7 &&
            items.any { it.directDistanceMeters == null }
        ) return chargingPane(
            "Hassas GPS kullanılamıyor. Eski Android Auto hostu mesafe istiyor."
        )
        val builder = ItemList.Builder()
        for (item in items.take(chargingHostLimit())) {
            val base = CarChargingListPolicy.summary(item)
            val firstLine = if (item.directDistanceMeters != null) {
                val label = base + " · Kuş uçuşu: "
                val decorated = SpannableString(label + "mesafe")
                decorated.setSpan(
                    DistanceSpan.create(Distance.create(
                        item.directDistanceMeters, Distance.UNIT_METERS
                    )),
                    label.length, decorated.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                decorated
            } else SpannableString(base + " · GPS kullanılamıyor")
            val row = Row.Builder()
                .setTitle(item.station.name.trim().replace("\n", " ").take(75))
                .addText(firstLine)
                .addText(CarChargingListPolicy.detail(item).take(140))
                .setBrowsable(true)
                .setOnClickListener {
                    selectedChargingStationId = item.station.id
                    invalidate()
                }.build()
            builder.addItem(row)
        }
        val itemList = builder.build()
        val goBack = ActionStrip.Builder().addAction(
            Action.Builder().setTitle("Rotaya dön")
                .setOnClickListener { hideChargingList() }.build()
        ).build()
        if (carContext.carAppApiLevel < 7) {
            @Suppress("DEPRECATION")
            return PlaceListNavigationTemplate.Builder()
                .setTitle("EPDK rota DC/CCS")
                .setItemList(itemList)
                .setActionStrip(goBack)
                .build()
        }
        return MapWithContentTemplate.Builder()
            .setContentTemplate(
                ListTemplate.Builder()
                    .setSingleList(itemList)
                    .setHeader(Header.Builder()
                        .setTitle("EPDK rota DC/CCS").build())
                    .build()
            )
            .setActionStrip(goBack)
            .build()
    }

    override fun onGetTemplate(): Template {
        if (chargingVisible) return chargerTemplate()
        val active = activeRouteId != null &&
            CarNavigationOwnership.owns(navigationSessionToken)
        val now = System.currentTimeMillis()
        val current = guidance
        val canBegin = !active && current != null &&
            current.gpsRecordedAtMs <= now &&
            now - current.gpsRecordedAtMs <= 5_000L &&
            fix?.usable(now) == true &&
            route?.routeId == current.routeId &&
            routeMatch == CarRouteMatch.ON_ROUTE

        // Car App API 1 has no ConstraintManager. Query the actual host's pane
        // limit; the physical screen diagonal cannot determine safe row count.
        val maxRows = if (carContext.carAppApiLevel >= 2) {
            runCatching {
                carContext.getCarService(ConstraintManager::class.java)
                    .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PANE)
                    .coerceIn(1, 4)
            }.getOrDefault(2)
        } else 2

        val detail = when {
            !active -> if (canBegin) "Rota ve GPS doğrulandı. Başlatabilirsiniz."
                else "Telefondan rota seçin ve hassas GPS izni verin."
            routeMatch == CarRouteMatch.CONFIRMED_OFF_ROUTE ->
                "Rotadan çıkıldı. Telefonda yeni rota planlayın; şarj duraklarını kontrol edin."
            routeMatch == CarRouteMatch.VERIFYING ->
                "Konum güzergâhla eşleşmiyor. Doğrulanıyor."
            routeMatch == CarRouteMatch.NO_VALID_FIX ->
                "Güncel ve hassas GPS bekleniyor. Yönlendirme duraklatıldı."
            else -> {
                val g = current?.takeIf { it.routeId == activeRouteId }
                if (g?.nextCue != null && g.nextMeters != null) {
                    g.nextCue + " · " + g.nextMeters.roundToInt() + " m"
                } else "Güncel manevra bilgisi bekleniyor."
            }
        }
        // Stable row titles help the host recognize a content refresh.
        val rows = listOf(
            Row.Builder().setTitle("Rota")
                .addText(summary.title.take(90))
                .addText(summary.detail.take(130)).build(),
            Row.Builder().setTitle("Yönlendirme").addText(detail.take(160)).build(),
            Row.Builder().setTitle("GPS")
                .addText(summary.locationStatus.take(110)).build()
        )
        val pane = Pane.Builder()
        rows.take(maxRows).forEach { pane.addRow(it) }

        // Pane rows must not contain an OnClickListener in Car App 1.7.
        // Host-rendered Pane Actions are the legal start/stop controls.
        if (active) {
            pane.addAction(Action.Builder().setTitle("Navigasyonu bitir")
                .setOnClickListener { endNavigation() }.build())
        } else if (canBegin) {
            pane.addAction(Action.Builder().setTitle("Navigasyonu başlat")
                .setOnClickListener { beginNavigation() }.build())
        }
        if (route?.routeId != null && route?.routePoints?.let(::validRouteGeometry) == true) {
            pane.addAction(Action.Builder().setTitle("Rota şarjları")
                .setOnClickListener { showChargingList() }.build())
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
