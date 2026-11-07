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
            try {
                manager.updateTrip(CarTripFactory.trip(current))
                gate.onPublished(current, now)
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
            pane.addRow(Row.Builder().setTitle("Sıradaki manevra")
                .addText(if (step != null && meters != null) {
                    "$step · ${meters.roundToInt()} m"
                } else "Güncel GPS ve dönüş bilgisi bekleniyor").build())
            pane.addRow(Row.Builder().setTitle("Navigasyonu bitir")
                .setOnClickListener { endNavigation() }.build())
        } else if (guidance?.let { current ->
            val now = System.currentTimeMillis()
            current.gpsRecordedAtMs <= now &&
                now - current.gpsRecordedAtMs <= 5_000L &&
                fix?.usable(now) == true &&
                route?.routeId == current.routeId
        } == true) {
            pane.addRow(Row.Builder().setTitle("Navigasyonu başlat")
                .setOnClickListener { beginNavigation() }.build())
        } else {
            pane.addRow(Row.Builder().setTitle("Başlamak için")
                .addText("Telefondan rota seçin ve konuma izin verin").build())
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
