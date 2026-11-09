package com.evcockpit.car

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.evcockpit.MainActivity
import com.evcockpit.diagnostics.DiagnosticsHub
import com.evcockpit.R
import com.evcockpit.domain.model.validRouteGeometry
import com.evcockpit.domain.repository.RouteRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * User-initiated location FGS, not a hidden background activity.
 *
 * Keeps a REAL Fused GPS subscription and notification while the projected
 * Android Auto host detaches. NavigationManager/Trip and speech remain owned
 * by the visible Car Screen and resume only with a NEW accurate real fix.
 *
 * Android 14+ may reject starting this service from a car-host-only process.
 * Such rejection is surfaced as PHONE_ACTION_REQUIRED; no illicit workaround.
 */
@AndroidEntryPoint
class NavigationLocationService : Service() {
    @Inject lateinit var routes: RouteRepository
    @Inject lateinit var continuity: CarNavigationContinuity
    @Inject lateinit var diagnostics: DiagnosticsHub

    private val ownerToken = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var trackJob: Job? = null
    private var tracker: CarGpsTracker? = null
    private var trackedRouteId: String? = null
    private var lastGpsRetryAtMs = 0L
    private var lastNotificationState: CarContinuityPhase? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "EV Cockpit · Navigasyon konumu",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            trackedRouteId?.let(continuity::endRoute)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val routeId = intent.getStringExtra(EXTRA_ROUTE_ID)?.takeIf { it.isNotBlank() }
        if (routeId == null || routes.currentRoute.value?.routeId != routeId ||
            routes.currentRoute.value?.routePoints?.let(::validRouteGeometry) != true
        ) {
            // A non-sticky restart or route change is never an authorized trip.
            routeId?.let(continuity::endRoute)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!locationAvailable()) {
            continuity.launchRestricted(routeId)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (trackedRouteId != null && trackedRouteId != routeId) {
            // A different explicitly started route replaces the old GPS job.
            trackJob?.cancel()
            trackJob = null
            tracker?.stop()
            tracker = null
            trackedRouteId = null
            lastNotificationState = null
        }

        try {
            val notification = createNotification(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else startForeground(NOTIFICATION_ID, notification)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Location FGS rejected by OS; no background GPS", error)
            continuity.launchRestricted(routeId)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!continuity.serviceStarted(routeId, ownerToken)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        diagnostics.foregroundService(location = true, active = true)
        if (trackJob != null) return START_NOT_STICKY
        trackedRouteId = routeId
        val gps = CarGpsTracker(applicationContext)
        tracker = gps
        lastGpsRetryAtMs = System.currentTimeMillis()
        gps.start()
        val ticker = flow {
            while (kotlin.coroutines.coroutineContext.isActive) {
                emit(System.currentTimeMillis())
                delay(1_000L)
            }
        }
        trackJob = scope.launch {
            combine(routes.currentRoute, gps.gpsFix, ticker) { route, fix, now ->
                Triple(route, fix, now)
            }.collect { (route, fix, now) ->
                if (route?.routeId != routeId || !validRouteGeometry(route.routePoints)) {
                    // A new route/alternative always requires a new explicit start.
                    continuity.endRoute(routeId)
                    stopSelf()
                    return@collect
                }
                val gpsEnabled = locationAvailable()
                val usable = fix?.takeIf { gpsEnabled && it.usable(now) &&
                    it.accuracyMeters <= 35f }
                continuity.serviceGps(routeId, ownerToken, usable, now)
                diagnostics.gps(usable, now)
                val phase = continuity.state.value.phase
                if (phase != lastNotificationState) {
                    lastNotificationState = phase
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID, createNotification(usable != null)
                    )
                }
                // Recovery never invents a fix. Re-subscribe at most every 15 s,
                // only while real location permission and device GPS remain on.
                if (usable == null && gpsEnabled && now - lastGpsRetryAtMs >= 15_000L) {
                    lastGpsRetryAtMs = now
                    gps.stop()
                    gps.start()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun locationAvailable(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val location = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return (fine || coarse) && location.isLocationEnabled
    }

    private fun createNotification(gpsReady: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, NavigationLocationService::class.java)
                .setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_eya_car)
            .setContentTitle("EV Cockpit · Navigasyon")
            .setContentText(if (gpsReady) "Gerçek GPS izleniyor · Android Auto bağlantısı beklenebilir"
                else "GPS kullanılamıyor · konum/izin kontrol edin")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Navigasyonu durdur", stop).build())
            .build()
    }

    override fun onDestroy() {
        trackJob?.cancel()
        trackJob = null
        tracker?.stop()
        tracker = null
        scope.cancel()
        continuity.serviceEnded(ownerToken)
        diagnostics.foregroundService(location = true, active = false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.evcockpit.action.NAV_LOCATION_START"
        const val ACTION_STOP = "com.evcockpit.action.NAV_LOCATION_STOP"
        const val EXTRA_ROUTE_ID = "routeId"
        private const val CHANNEL_ID = "ev_cockpit_navigation_location"
        private const val NOTIFICATION_ID = 2461
        private const val TAG = "CarNavigationFgs"
    }
}
