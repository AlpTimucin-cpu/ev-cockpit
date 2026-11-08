package com.evcockpit.car

import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.constraints.ConstraintManager
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
import com.evcockpit.presentation.voice.WelcomeGreetingManager
import kotlin.math.roundToInt

/** Real OSRM geometry + measured GPS only. Foreground Android Auto session, not a navigation FGS. */
class EyaCarNavigationScreen(
    context: CarContext,
    private val routes: RouteRepository,
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
                    combine(routes.currentRoute, tracker.gpsFix, ticker) { r, f, now ->
                        Triple(r, f?.takeIf { it.usable(now) }, now)
                    }.collect { (r, f, now) ->
                        route = r
                        fix = f
                        surface.update(r, f)
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

    override fun onGetTemplate(): Template {
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
        rows.take(maxRows).forEach(pane::addRow)

        // Pane rows must not contain an OnClickListener in Car App 1.7.
        // Host-rendered Pane Actions are the legal start/stop controls.
        if (active) {
            pane.addAction(Action.Builder().setTitle("Navigasyonu bitir")
                .setOnClickListener { endNavigation() }.build())
        } else if (canBegin) {
            pane.addAction(Action.Builder().setTitle("Navigasyonu başlat")
                .setOnClickListener { beginNavigation() }.build())
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
