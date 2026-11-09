package com.evcockpit

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.content.pm.ActivityInfo
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.evcockpit.data.settings.UserSettings
import com.evcockpit.data.settings.UserSettingsRepository
import com.evcockpit.domain.model.ObdPhase
import com.evcockpit.domain.repository.ObdConnectionController
import com.evcockpit.domain.repository.VehicleRepository
import com.evcockpit.domain.repository.RouteRepository
import com.evcockpit.domain.repository.ChargingRepository
import com.evcockpit.domain.repository.LocationRepository
import com.evcockpit.diagnostics.DiagnosticsHub
import com.evcockpit.BuildConfig
import com.evcockpit.domain.model.validRouteGeometry
import com.evcockpit.car.CarNavigationContinuity
import com.evcockpit.car.CarContinuityPhase
import com.evcockpit.car.NavigationLocationService
import com.evcockpit.presentation.charging.ChargingViewModel
import com.evcockpit.presentation.charging.RouteChargePlanViewModel
import com.evcockpit.presentation.cockpit.CockpitScreen
import com.evcockpit.presentation.cockpit.CockpitTheme
import com.evcockpit.presentation.media.enterOwnVideoPipWhenParked
import com.evcockpit.presentation.navigation.NavigationViewModel
import com.evcockpit.presentation.settings.SettingsScreen
import com.evcockpit.presentation.settings.SettingsViewModel
import com.evcockpit.presentation.sound.ActiveSoundManager
import com.evcockpit.presentation.voice.AppAudioDucking
import com.evcockpit.presentation.voice.LocalAppAudioDucking
import com.evcockpit.presentation.voice.NativeVoiceGuide
import com.evcockpit.presentation.voice.TtsPackageWarningDialog
import com.evcockpit.presentation.voice.WelcomeGreetingManager
import com.evcockpit.presentation.weather.RouteWeatherViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * The only launcher Activity of the nativecockpit APK (applicationId com.evcockpit).
 * The mediahub APK (com.evcockpit.mediahub) is a separate application;
 * replacing this class will not change that companion application's launcher.
 *
 * Always renders the modern MapLibre + Compose CockpitScreen. Runtime permissions
 * remain explicit, requested from the appropriate UI actions, never faked.
 */
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
    @Inject lateinit var welcome: WelcomeGreetingManager
    @Inject lateinit var routes: RouteRepository
    @Inject lateinit var continuity: CarNavigationContinuity
    @Inject lateinit var diagnosticsHub: DiagnosticsHub
    @Inject lateinit var chargingRepository: ChargingRepository
    @Inject lateinit var locationRepository: LocationRepository

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
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            obd.refreshPaired()
        }
    }

    private fun connectObd(address: String) {
        try {
            obd.connect(address)
            if (obd.connection.value.phase != ObdPhase.CONNECTING &&
                obd.connection.value.phase != ObdPhase.CONNECTED
            ) return
            // Keep the user-initiated, read-only OBD session alive with notification.
            ContextCompat.startForegroundService(
                this, Intent(this, ObdForegroundService::class.java)
            )
        } catch (error: Exception) {
            Log.e(TAG, "Could not start the OBD foreground service", error)
            obd.disconnect()
            Toast.makeText(
                this,
                "OBD servisi başlatılamadı. Bağlantı kapatıldı.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Retry the location FGS ONLY from a visible, user-tapped phone screen. */
    private fun retryCarNavigationTracking() {
        val pending = continuity.state.value
        val routeId = pending.routeId
        if (pending.phase != CarContinuityPhase.PHONE_ACTION_REQUIRED ||
            routeId.isNullOrBlank() || routes.currentRoute.value?.routeId != routeId ||
            routes.currentRoute.value?.routePoints?.let(::validRouteGeometry) != true
        ) {
            Toast.makeText(this, "Onaylı Android Auto rotası bulunamadı.",
                Toast.LENGTH_LONG).show()
            return
        }
        val allowed = listOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val location = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!allowed || !location.isLocationEnabled) {
            Toast.makeText(this, "Önce konum iznini ve cihaz GPS'ini açın.",
                Toast.LENGTH_LONG).show()
            return
        }
        if (!continuity.retryFromPhone(routeId)) return
        try {
            ContextCompat.startForegroundService(
                this, Intent(this, NavigationLocationService::class.java)
                    .setAction(NavigationLocationService.ACTION_START)
                    .putExtra(NavigationLocationService.EXTRA_ROUTE_ID, routeId)
            )
        } catch (error: RuntimeException) {
            continuity.launchRestricted(routeId)
            Log.w(TAG, "User-initiated location FGS denied", error)
            Toast.makeText(this, "Android konum servisini başlatmayı engelledi.",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun stopCarNavigationTracking() {
        val routeId = continuity.state.value.routeId
        continuity.endRoute(routeId)
        stopService(Intent(this, NavigationLocationService::class.java))
    }

    private fun disconnectObd() {
        obd.disconnect()
        stopService(Intent(this, ObdForegroundService::class.java))
    }

    /** A future app-owned video player only; never bypass projected-host safety. */
    fun enterParkedOwnedVideoPip(
        verifiedParked: Boolean,
        ownVideoPlaying: Boolean
    ): Boolean = enterOwnVideoPipWhenParked(verifiedParked, ownVideoPlaying)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Landscape on phones; Android may override orientation on large-screen devices.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        Log.i(TAG, "Launching modern Compose CockpitScreen, package=$packageName")

        if (BuildConfig.DEBUG) {
            lifecycleScope.launch {
                combine(
                    locationRepository.gpsFix,
                    chargingRepository.catalog,
                    obd.connection
                ) { gps, catalog, connection -> Triple(gps, catalog, connection) }
                    .collect { (gps, catalog, connection) ->
                        diagnosticsHub.gps(gps)
                        diagnosticsHub.charging(catalog)
                        diagnosticsHub.obd(connection)
                    }
            }
        }

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
            val locationManager = remember(context) {
                context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            }
            var pendingRouteRequest by remember { mutableStateOf(false) }
            var currentToken by remember { mutableStateOf<CancellationTokenSource?>(null) }

            DisposableEffect(fused) {
                onDispose { currentToken?.cancel() }
            }

            @Suppress("MissingPermission")
            fun fetchRealLocation(forRoute: Boolean) {
                if (!locationManager.isLocationEnabled) {
                    navigation.reportGpsError("GPS kapalı. Telefonun konum ayarlarını açın.")
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
                    .setPriority(
                        if (fine) Priority.PRIORITY_HIGH_ACCURACY
                        else Priority.PRIORITY_BALANCED_POWER_ACCURACY
                    )
                    .setMaxUpdateAgeMillis(3_000L)
                    .setDurationMillis(15_000L)
                    .build()
                currentToken?.cancel()
                val token = CancellationTokenSource()
                currentToken = token
                try {
                    fused.getCurrentLocation(request, token.token)
                        .addOnSuccessListener { location ->
                            if (location == null) {
                                navigation.reportGpsError("GPS konumu bulunamadı. Açık havada tekrar deneyin.")
                            } else {
                                navigation.acceptLastLocation(location)
                            }
                        }
                        .addOnFailureListener { error ->
                            navigation.reportGpsError(
                                "GPS alınamadı: ${error.message ?: "konum hatası"}"
                            )
                        }
                } catch (error: SecurityException) {
                    navigation.reportGpsError("Konum izni yok. Telefon ayarlarını kontrol edin.")
                }
            }

            val gpsPermissions = arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            val requestLocationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { granted ->
                val accepted = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                if (accepted) fetchRealLocation(pendingRouteRequest)
                else if (pendingRouteRequest) navigation.locationPermissionDenied()
                else navigation.reportGpsError("Konum iznine izin verilmedi.")
                pendingRouteRequest = false
            }
            val requestGps: (Boolean) -> Unit = { fromRoute ->
                val allowed = gpsPermissions.any {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
                if (allowed) fetchRealLocation(fromRoute)
                else {
                    pendingRouteRequest = fromRoute
                    requestLocationPermission.launch(gpsPermissions)
                }
            }

            // Audible weather cues also give a short visible confirmation.
            LaunchedEffect(navigation) {
                navigation.weatherAlerts.collect { message ->
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
            val soundUnavailable by activeSound.audioUnavailable.collectAsStateWithLifecycle()
            val welcomeWarning by welcome.warning.collectAsStateWithLifecycle()
            val carNavigationState by continuity.state.collectAsStateWithLifecycle()
            var audioSettingsOpen by remember { mutableStateOf(false) }
            var ttsWarningDismissed by rememberSaveable { mutableStateOf(false) }

            CompositionLocalProvider(LocalAppAudioDucking provides audioDucking) {
                CockpitTheme(nightMode = soundSettings.isNightModeEnabled) {
                    Box(Modifier.fillMaxSize()) {
                        // The first rendered screen is ALWAYS the modern Compose cockpit.
                        // The old CockpitDashboard belongs to a different app package.
                        CockpitScreen(
                            state = ui.cockpit,
                            routeState = ui.routeState,
                            loadStatus = ui.loadStatus,
                            routePlannerUi = ui.planner,
                            onRouteOriginChange = navigation::editOrigin,
                            onRouteDestinationChange = navigation::editDestination,
                            onRouteOriginSearch = navigation::requestOriginSearch,
                            onRouteDestinationSearch = navigation::requestDestinationSearch,
                            onRouteOriginSelected = navigation::selectOrigin,
                            onRouteDestinationSelected = navigation::selectDestination,
                            onRouteFromGps = { requestGps(true) },
                            onRoutePlan = navigation::plan,
                            onRouteAlternative = navigation::selectAlternative,
                            onRouteClear = navigation::clearRoute,
                            onAddChargingStop = navigation::addChargingStop,
                            onSetArrivalReserve = chargePlan::setArrivalReservePercent,
                            onSetStopPreference = chargePlan::setStopPreference,
                            onGpsClick = { requestGps(false) },
                            gpsError = gpsError,
                            chargingUi = stations,
                            automaticChargePlan = automaticChargePlan,
                            weatherUi = routeWeather,
                            onRefreshWeather = weather::refresh,
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
                            onVoiceTest = navigation::testVoice,
                            soundUnavailable = soundUnavailable,
                            onOpenSoundSettings = { audioSettingsOpen = true }
                        )
                        // Settings entry moved into the telemetry column to avoid HUD overlap.
                        // Show a dismissible warning when either Turkish TTS path is missing.
                        if (!ttsWarningDismissed &&
                            (welcomeWarning != null ||
                                ui.voice.status == NativeVoiceGuide.Status.TURKISH_VOICE_MISSING)
                        ) {
                            TtsPackageWarningDialog(onDismiss = {
                                welcome.dismissWarning()
                                ttsWarningDismissed = true
                            })
                        }
                        if (audioSettingsOpen) {
                            SettingsScreen(
                                viewModel = settingsViewModel,
                                activeSound = activeSound,
                                diagnosticsHub = diagnosticsHub,
                                carNavigationState = carNavigationState,
                                onRetryCarNavigation = ::retryCarNavigationTracking,
                                onStopCarNavigation = ::stopCarNavigationTracking,
                                onClose = { audioSettingsOpen = false }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activeSound.onResume()
        navigation.setForeground(true)
        val hasGpsPermission = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (hasGpsPermission) navigation.startGps()
    }

    override fun onPause() {
        activeSound.onPause()
        navigation.setForeground(false)
        super.onPause()
    }

    companion object { private const val TAG = "EV-Cockpit-Main" }
}