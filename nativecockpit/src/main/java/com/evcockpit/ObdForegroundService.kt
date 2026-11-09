package com.evcockpit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.evcockpit.domain.model.ObdPhase
import com.evcockpit.diagnostics.DiagnosticsHub
import com.evcockpit.domain.repository.ObdConnectionController
import com.evcockpit.presentation.voice.WelcomeGreetingManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** User-initiated foreground Bluetooth service; no silent auto-reconnect. */
@AndroidEntryPoint
class ObdForegroundService : Service() {
    @Inject lateinit var obd: ObdConnectionController
    @Inject lateinit var welcome: WelcomeGreetingManager
    @Inject lateinit var diagnostics: DiagnosticsHub
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connectionMonitor: Job? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL, "EV Cockpit OBD bağlantısı", NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            obd.disconnect()
            welcome.onObdDisconnected()
            stopSelf()
            return START_NOT_STICKY
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ObdForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_eya_car)
            .setContentTitle("EV Cockpit")
            .setContentText("Bluetooth OBD bağlantısı kuruluyor / izleniyor")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Bağlantıyı kes", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else startForeground(NOTIFICATION_ID, notification)
        diagnostics.foregroundService(location = false, active = true)

        connectionMonitor?.cancel()
        connectionMonitor = scope.launch {
            var wasConnected = false
            var hadConnectionAttempt = false
            obd.connection.collect { state ->
                if (state.phase == ObdPhase.CONNECTING || state.phase == ObdPhase.CONNECTED) {
                    hadConnectionAttempt = true
                }
                val connected = state.phase == ObdPhase.CONNECTED
                if (connected && !wasConnected) {
                    // Genuine ELM327 session. Not triggered by launching an Activity.
                    welcome.launchObdGreeting()
                }
                if (wasConnected && !connected) welcome.onObdDisconnected()
                wasConnected = connected
                // A failed adapter can move to ERROR before this collector starts.
                // Do not leave a zombie foreground service behind.
                if (state.phase == ObdPhase.ERROR ||
                    (hadConnectionAttempt && state.phase == ObdPhase.DISCONNECTED)
                ) {
                    welcome.onObdDisconnected()
                    // Only stop this request, never a newer user retry.
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        connectionMonitor?.cancel()
        scope.cancel()
        diagnostics.foregroundService(location = false, active = false)
        welcome.onObdDisconnected()
        // Preserve the real connection error so the user can read it after
        // the foreground notification disappears.
        if (obd.connection.value.phase != ObdPhase.ERROR) obd.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.evcockpit.action.STOP_OBD"
        private const val CHANNEL = "ev_cockpit_obd_service"
        private const val NOTIFICATION_ID = 2460
    }
}