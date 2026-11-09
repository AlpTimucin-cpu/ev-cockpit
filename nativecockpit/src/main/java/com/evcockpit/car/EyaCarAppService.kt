package com.evcockpit.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.evcockpit.BuildConfig
import com.evcockpit.diagnostics.DiagnosticsHub
import com.evcockpit.domain.repository.RouteRepository
import com.evcockpit.domain.repository.ChargingRepository
import com.evcockpit.domain.repository.VehicleRepository
import com.evcockpit.data.settings.AppConfigRepository
import com.evcockpit.data.repository.OsrmRoadDetourRepository
import com.evcockpit.data.weather.OpenMeteoWeatherRepository
import com.evcockpit.presentation.voice.WelcomeGreetingManager
import com.evcockpit.presentation.voice.AppAudioDucking

/** Android Auto Car App Library entry point. Does NOT start an Activity or play video. */
@AndroidEntryPoint
class EyaCarAppService : CarAppService() {
    @Inject lateinit var routes: RouteRepository
    @Inject lateinit var charging: ChargingRepository
    @Inject lateinit var vehicle: VehicleRepository
    @Inject lateinit var config: AppConfigRepository
    @Inject lateinit var detours: OsrmRoadDetourRepository
    @Inject lateinit var weather: OpenMeteoWeatherRepository
    @Inject lateinit var continuity: CarNavigationContinuity
    @Inject lateinit var diagnostics: DiagnosticsHub
    @Inject lateinit var welcome: WelcomeGreetingManager
    @Inject lateinit var ducking: AppAudioDucking

    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(this)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(): Session {
        // Shared singleton gate prevents duplicates if OBD connects as well.
        welcome.launchWelcomeGreeting()
        return EyaCarSession(routes, charging, vehicle, config, detours, weather, continuity, diagnostics, ducking, welcome)
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        welcome.launchWelcomeGreeting()
        return EyaCarSession(routes, charging, vehicle, config, detours, weather, continuity, diagnostics, ducking, welcome)
    }
}

private class EyaCarSession(
    private val routes: RouteRepository,
    private val charging: ChargingRepository,
    private val vehicle: VehicleRepository,
    private val config: AppConfigRepository,
    private val detours: OsrmRoadDetourRepository,
    private val weather: OpenMeteoWeatherRepository,
    private val continuity: CarNavigationContinuity,
    private val diagnostics: DiagnosticsHub,
    private val ducking: AppAudioDucking,
    private val welcome: WelcomeGreetingManager
) : Session() {
    override fun onCreateScreen(intent: Intent): Screen =
        EyaCarNavigationScreen(carContext, routes, charging, vehicle, config, detours, weather, continuity, diagnostics, ducking, welcome)
}
