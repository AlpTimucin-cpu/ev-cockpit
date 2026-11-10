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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evcockpit.BuildConfig
import com.evcockpit.R
import com.evcockpit.domain.model.*
import com.evcockpit.domain.usecase.AutomaticChargePlan
import com.evcockpit.presentation.charging.ChargingUiState
import com.evcockpit.presentation.charging.RouteChargingUiState
import com.evcockpit.presentation.charging.RouteStopPreference
import com.evcockpit.presentation.map.MapLibreCockpit
import com.evcockpit.presentation.media.MediaHomeScreen
import com.evcockpit.presentation.media.rememberPhoneMediaAllowed
import com.evcockpit.presentation.navigation.NavigationRouteState
import com.evcockpit.presentation.navigation.RouteLoadStatus
import com.evcockpit.presentation.obd.ObdSettingsDialog
import com.evcockpit.presentation.route.RoutePlannerDialog
import com.evcockpit.presentation.route.RouteJourneySheet
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
    onRouteOriginSearch:()->Unit,
    onRouteDestinationSearch:()->Unit,
    onRouteOriginSelected:(RoutePlace)->Unit,
    onRouteDestinationSelected:(RoutePlace)->Unit,
    onRouteFromGps:()->Unit,
    onRoutePlan:()->Unit,
    onRouteAlternative:(Int)->Unit,
    onRouteClear:()->Unit,
    onAddChargingStop: (ChargingStation) -> Unit,
    onSetArrivalReserve: (Float) -> Unit,
    onSetStopPreference: (RouteStopPreference) -> Unit,
    onGpsClick: () -> Unit,
    gpsError: String? = null,
    chargingUi: ChargingUiState,
    automaticChargePlan: RouteChargingUiState,
    weatherUi: RouteWeatherState,
    onRefreshWeather: () -> Unit,
    onStationFilter: (StationFilter) -> Unit,
    onMapViewport: (GeoViewport) -> Unit,
    onRefreshStations: () -> Unit,
    voiceEnabled: Boolean,
    onVoiceToggle: () -> Unit,
    voiceVolume: Float,
    onVoiceVolumeChange: (Float) -> Unit,
    voiceStatus: NativeVoiceGuide.Status,
    voiceError: String?,
    onVoiceTest: () -> Unit,
    soundUnavailable: Boolean = false,
    onOpenSoundSettings: () -> Unit = {}
) {
    // Disconnects invalidate *all* live battery/vehicle readings immediately.
    // GPS and route state remain independent of the OBD adapter.
    val displayState = if (obdConnection.phase == ObdPhase.CONNECTED) state else state.copy(
        socPercent = null,
        speedKmh = null,
        voltageV = null,
        batteryTemperatureC = null,
        powerKw = null,
        estimatedRangeKm = null,
        socSourceLabel = null
    )
    val route = state.route ?: routeState.routeResult
    val nowMs = System.currentTimeMillis()
    val gpsStatus = DashboardStatusPolicy.gpsLabel(state.gpsFix, nowMs)
    val obdStatus = DashboardStatusPolicy.obdLabel(obdConnection.phase)
    val weatherAge = if (route?.routeId != null && weatherUi.routeId == route.routeId)
        weatherUi.ageMinutes(nowMs) else null
    val weatherStatus = DashboardStatusPolicy.weatherLabel(
        loading = weatherUi.loading && weatherUi.routeId == route?.routeId,
        hasData = weatherUi.routeId == route?.routeId && weatherUi.points.isNotEmpty(),
        ageMinutes = weatherAge
    )
    var routePlanner by remember { mutableStateOf(false) }
    var journeySheet by remember { mutableStateOf(false) }
    var charging by remember { mutableStateOf(false) }
    var selectedPage by rememberSaveable { mutableIntStateOf(0) } // 0 = Harita, 1 = Medya
    // User-controlled phone-only view. Never changes navigation, telemetry or car projection.
    var mapFocused by rememberSaveable { mutableStateOf(false) }
    // Request ID, not a fabricated GPS fix; map reacts only to the driver's tap.
    var recenterRequestId by rememberSaveable { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(false) }
    var engineeringOpen by remember { mutableStateOf(false) }
    var voiceSettings by remember { mutableStateOf(false) }
    var station by remember { mutableStateOf<ChargingStation?>(null) }
    var missingVoice by remember { mutableStateOf(false) }
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

    // A calculated real OSRM route opens its journey summary immediately.
    LaunchedEffect(route?.routeId) {
        if (route?.routeId != null && routePlanner) {
            routePlanner = false
            journeySheet = true
        }
    }

    if (journeySheet && route?.routeId != null) RouteJourneySheet(
        route = route,
        planner = routePlannerUi,
        charging = automaticChargePlan,
        realObdSocPercent = if (obdConnection.phase == ObdPhase.CONNECTED)
            displayState.socPercent else null,
        onDismiss = { journeySheet = false },
        onEditTargets = {
            journeySheet = false
            routePlanner = true
        },
        onSelectAlternative = onRouteAlternative,
        onSetReserve = onSetArrivalReserve,
        onSetPreference = onSetStopPreference,
        onOpenStation = {
            journeySheet = false
            station = it
        }
    )

    if (routePlanner) RoutePlannerDialog(
        state = routePlannerUi, onDismiss = { routePlanner = false },
        onOriginChange = onRouteOriginChange, onDestinationChange = onRouteDestinationChange,
        onOriginSearch = onRouteOriginSearch, onDestinationSearch = onRouteDestinationSearch,
        onOriginSelected = onRouteOriginSelected, onDestinationSelected = onRouteDestinationSelected,
        onGps = onRouteFromGps, onPlan = onRoutePlan,
        onChooseAlternative = onRouteAlternative, onClear = onRouteClear,
        onOpenRoutingSettings = {
            routePlanner = false
            onOpenSoundSettings()
        },
        measuredSocPercent = displayState.socPercent
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
    if (missingVoice) TtsPackageWarningDialog(onDismiss = { missingVoice = false })
    if (voiceSettings) AlertDialog(
        onDismissRequest = { voiceSettings = false },
        containerColor = Navy,
        title = { Text(stringResource(R.string.native_voice_guidance)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(when (voiceStatus) {
                    NativeVoiceGuide.Status.LOADING -> R.string.ux_tts_loading
                    NativeVoiceGuide.Status.READY -> R.string.ux_tts_ready
                    NativeVoiceGuide.Status.TURKISH_VOICE_MISSING -> R.string.ux_tts_missing
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
                    Text(stringResource(R.string.ux_tts_test))
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
        // Verified pricing is intentionally nullable until an authorized operator API
        // is connected. Never display a fabricated TL/kWh quote as "today's price".
        val quote by produceState(
            initialValue = com.evcockpit.data.pricing.StationChargeTariff.unavailable(selected.id),
            key1 = selected.id,
            key2 = selected.operator
        ) {
            value = com.evcockpit.data.pricing.VerifiedOperatorTariffRepository.quote(selected)
        }
        ModalBottomSheet(
            onDismissRequest = { station = null },
            containerColor = Navy,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(selected.name, style = MaterialTheme.typography.headlineSmall)
                Text(selected.operator, color = SoftText)
                Text(selected.address, color = SoftText,
                    style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(color = GlassLine)
                Text("ŞARJ SOKETLERİ", color = NeonGreen,
                    style = MaterialTheme.typography.labelMedium)
                selected.sockets.forEach { socket ->
                    Text(socket.kind + " · " + socket.type + " · " +
                        socket.powerRaw.ifBlank { "Güç belirtilmemiş" })
                }
                HorizontalDivider(color = GlassLine)
                Text("GÜNCEL FİYAT · TL/kWh", color = NeonGreen,
                    style = MaterialTheme.typography.labelMedium)
                val priceLabel = when {
                    quote.priceTlKwh != null -> "%.2f TL/kWh".format(quote.priceTlKwh)
                    quote.minPriceTlKwh != null && quote.maxPriceTlKwh != null ->
                        "%.2f–%.2f TL/kWh".format(
                            quote.minPriceTlKwh, quote.maxPriceTlKwh
                        )
                    else -> "Güncel fiyat doğrulanamadı"
                }
                Text(priceLabel)
                if (quote.minPriceTlKwh != null) {
                    Text("Yalnız operatör genel tarifesi · istasyon/soket fiyatı değil",
                        style = MaterialTheme.typography.bodySmall, color = SoftText)
                    Text("Tarife tarihi: " + quote.verifiedAtUtc.orEmpty(),
                        style = MaterialTheme.typography.labelSmall, color = SoftText)
                }
                Text(quote.campaignDescription
                    ?: "Kampanya/indirim bilgisi sağlanmıyor",
                    style = MaterialTheme.typography.bodySmall, color = SoftText)
                Text("Fiyat, soket, saat ve üyeliğe göre değişebilir. " +
                    "Operatörün güncel fiyatını doğrulamadan tahmin kullanılmaz.",
                    style = MaterialTheme.typography.bodySmall, color = SoftText)
                Text("EPDK kaynak tarihi: " + selected.sourceVerifiedAtUtc,
                    style = MaterialTheme.typography.labelSmall, color = SoftText)
                Button(
                    onClick = { onAddChargingStop(selected); station = null },
                    enabled = route?.routeId != null &&
                        routePlannerUi.startLocation != null &&
                        routePlannerUi.destinationSelected != null &&
                        !routePlannerUi.planning,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Burada Durak Ekle · Rotayı Yeniden Hesapla") }
                if (route?.routeId == null) Text(
                    "Durak eklemek için önce rota oluştur.",
                    style = MaterialTheme.typography.bodySmall, color = SoftText
                )
                TextButton(onClick = { station = null },
                    modifier = Modifier.fillMaxWidth()) { Text("Kapat") }
            }
        }
    }

    // Fixed bottom navigation. Media is a separate page, not a third HUD column.
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Ink)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val compactDashboard = CockpitLayoutPolicy.isCompact(maxWidth.value, maxHeight.value)
        Column(Modifier.fillMaxSize()) {
        if (selectedPage == 1) {
            MediaHomeScreen(
                phoneAllowed = phoneMediaAllowed,
                obdConnected = obdConnection.phase == ObdPhase.CONNECTED,
                freshVehicleSpeedKmh = state.speedKmh,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        } else {
            Row(
                Modifier.fillMaxWidth().weight(1f)
                    .padding(
                        horizontal = if (compactDashboard) 6.dp else 10.dp,
                        vertical = if (compactDashboard) 4.dp else 8.dp
                    ),
                horizontalArrangement = Arrangement.spacedBy(if (compactDashboard) 6.dp else 12.dp)
            ) {
                if (!mapFocused) {
                    TelemetryLeftPanel(
                        data = displayState,
                        connected = obdConnection.phase == ObdPhase.CONNECTED,
                        compact = compactDashboard,
                        obdStatus = obdStatus,
                        soundUnavailable = soundUnavailable,
                        onConnect = { settings = true; onObdRefresh() },
                        onEngineering = { engineeringOpen = true },
                        onSoundSettings = onOpenSoundSettings,
                        modifier = Modifier.weight(
                            if (compactDashboard) .33f else .36f
                        ).fillMaxHeight()
                    )
                }
                Box(
                    Modifier.weight(
                        CockpitLayoutPolicy.mapWeight(compactDashboard, mapFocused)
                    ).fillMaxHeight()
                        .background(Navy, RoundedCornerShape(14.dp))
                ) {
                    // The route is always visible on the driving page.
                    MapLibreCockpit(
                        gpsFix = state.gpsFix,
                        recenterRequestId = recenterRequestId,
                        routePoints = route?.routePoints.orEmpty(),
                        stations = chargingUi.allStations,
                        plannedStopIds = (automaticChargePlan.plan as? AutomaticChargePlan.Estimate)
                            ?.stops?.map { it.station.id }?.toSet().orEmpty(),
                        weatherPoints = weatherUi.points
                            .takeIf { weatherUi.routeId == route?.routeId }.orEmpty(),
                        speedProfile = route?.profileSpeeds.orEmpty(),
                        onViewportChanged = onMapViewport,
                        onStationTapped = { station = it },
                        modifier = Modifier.fillMaxSize()
                    )
                    IconButton(
                        onClick = { recenterRequestId++ },
                        enabled = state.gpsFix?.usable(nowMs) == true,
                        modifier = Modifier.align(Alignment.CenterEnd)
                            .padding(end = 8.dp)
                            .size(48.dp)
                            .background(Color(0xE50A1728), RoundedCornerShape(12.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.GpsFixed,
                            contentDescription = "Konumuma dön",
                            tint = if (state.gpsFix?.usable(nowMs) == true) NeonGreen else SoftText
                        )
                    }
                    NavigationGlassCard(
                        route = route,
                        loading = loadStatus is RouteLoadStatus.Loading,
                        compact = compactDashboard,
                        mapFocused = mapFocused,
                        onToggleFocus = { mapFocused = !mapFocused },
                        onOpen = {
                            if (route?.routeId == null) routePlanner = true
                            else journeySheet = true
                        },
                        modifier = Modifier.align(Alignment.TopCenter)
                            .fillMaxWidth(if (compactDashboard) .94f else .87f)
                            .padding(top = if (compactDashboard) 5.dp else 10.dp)
                    )
                    // A single reachable status strip avoids overlays fighting for map space
                    // on short landscape phones. Wider displays retain detailed status.
                    if (compactDashboard) {
                        Row(
                            // Keep MapLibre logo/attribution visible below this strip.
                            modifier = Modifier.align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(start = 6.dp, end = 6.dp, bottom = 36.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            AssistPill(gpsStatus, onGpsClick,
                                modifier = Modifier.weight(1f), compact = true)
                            AssistPill(if (voiceEnabled) "Ses açık" else "Ses kapalı",
                                onVoiceToggle, modifier = Modifier.weight(1f), compact = true)
                            if (route?.routeId != null) {
                                AssistPill(weatherStatus, onRefreshWeather,
                                    modifier = Modifier.weight(1f), compact = true)
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.align(Alignment.BottomStart)
                                .padding(start = 8.dp, bottom = 36.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (route?.routeId != null) {
                                AssistPill("$weatherStatus · Yenile", onRefreshWeather,
                                    modifier = Modifier.widthIn(max = 220.dp))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AssistPill(gpsStatus, onGpsClick)
                                AssistPill(if (voiceEnabled) "Ses açık" else "Ses kapalı",
                                    onVoiceToggle)
                            }
                            if (soundUnavailable) {
                                AssistPill("Motor sesi kullanılamıyor", onOpenSoundSettings,
                                    modifier = Modifier.widthIn(max = 220.dp))
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbar)
        CockpitDock(
            selectedPage = selectedPage,
            compact = compactDashboard,
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            onRoute = {
                if (selectedPage == 1) selectedPage = 0
                else if (route?.routeId != null) journeySheet = true
                else routePlanner = true
            },
            onCharging = { selectedPage = 0; charging = true },
            onMedia = { selectedPage = 1 },
            // Dock "Ayarlar" must open the actual vehicle/service settings,
            // not the unrelated Bluetooth OBD diagnostic dialog.
            onSettings = onOpenSoundSettings
        )
        }
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
private fun BatteryHud(state: CockpitUiState, compact: Boolean, showExample: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier, color = Navy.copy(alpha = .96f),
        shape = RoundedCornerShape(18.dp), shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelLarge,
                color = SoftText, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            if (compact) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BatteryPercent(state.socPercent, true, showExample, Modifier.weight(.58f))
                    RangeValue(state.estimatedRangeKm, Modifier.weight(.42f), showExample)
                }
            } else {
                BatteryPercent(state.socPercent, false, showExample)
                RangeValue(state.estimatedRangeKm, showExample = showExample)
            }
            // An illustration is never used as a live battery measurement.
            if (state.socPercent == null && showExample) Text(
                "Örnek %80 · gerçek BMS doluluğu değil",
                style = MaterialTheme.typography.labelSmall, color = SoftText
            )
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
private fun BatteryPercent(value: Double?, compact: Boolean, showExample: Boolean, modifier: Modifier = Modifier) {
    val percentSymbol = stringResource(R.string.cockpit_percent_symbol)
    val unknownValue = stringResource(R.string.cockpit_unknown_value)
    val number = value?.takeIf { it.isFinite() && it in 0.0..100.0 }
        ?.let { CockpitFormat.percent(it).removeSuffix(percentSymbol) }
        ?: if (showExample) "80" else unknownValue
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(number,
            fontSize = if (compact) 72.sp else 96.sp,
            lineHeight = if (compact) 80.sp else 104.sp,
            fontWeight = FontWeight.Light,
            color = if (value == null) Color.White.copy(alpha = .60f) else Color.White,
            maxLines = 1, softWrap = false)
        if (number != unknownValue) {
            Text(percentSymbol, Modifier.padding(bottom = if (compact) 8.dp else 12.dp),
                fontSize = if (compact) 30.sp else 40.sp,
                lineHeight = if (compact) 36.sp else 46.sp, color = SoftText)
        }
    }
}

@Composable
private fun RangeValue(km: Double?, modifier: Modifier = Modifier, showExample: Boolean) {
    val unit = stringResource(R.string.cockpit_distance_unit)
    Column(modifier) {
        val knownRange = km?.takeIf { it.isFinite() && it >= 0.0 }
        val displayRange = knownRange ?: if (showExample) 320.0 else null
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                displayRange?.let { CockpitFormat.distance(it).removeSuffix(" $unit") }
                    ?: stringResource(R.string.cockpit_unknown_value),
                Modifier.alignByBaseline(), fontSize = 39.sp, lineHeight = 46.sp,
                fontWeight = FontWeight.Light, color = NeonGreen, maxLines = 1, softWrap = false
            )
            if (knownRange != null) {
                Text(unit, Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.labelMedium, color = SoftText)
            }
        }
        Text(
            if (knownRange == null && showExample) "320 km · örnek model, canlı menzil değil"
            else stringResource(R.string.cockpit_estimated_range),
            style = MaterialTheme.typography.labelSmall, color = SoftText
        )
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
                    ?: if (loading) stringResource(R.string.native_calculating_route)
                       else "Rota çizmek için dokunun",
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
    selectedPage: Int,
    compact: Boolean,
    onRoute: () -> Unit,
    onCharging: () -> Unit,
    onMedia: () -> Unit,
    onSettings: () -> Unit
) {
    // Four primary destinations only. Diagnostics lives in TelemetryLeftPanel.
    NavigationBar(
        modifier = modifier.height(if (compact) 58.dp else 64.dp),
        containerColor = Navy,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0)
    ) {
        NavigationBarItem(
            selected = selectedPage == 0, onClick = onRoute,
            icon = { Icon(Icons.Default.Map, contentDescription = null) },
            label = { Text("Rota", fontSize = 11.sp) }
        )
        NavigationBarItem(
            selected = false, onClick = onCharging,
            icon = { Icon(Icons.Default.Bolt, contentDescription = null) },
            label = { Text("Şarj", fontSize = 11.sp) }
        )
        NavigationBarItem(
            selected = selectedPage == 1, onClick = onMedia,
            icon = { Icon(Icons.Default.SmartDisplay, contentDescription = null) },
            label = { Text("Medya", fontSize = 11.sp) }
        )
        NavigationBarItem(
            selected = false, onClick = onSettings,
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text("Ayarlar", fontSize = 11.sp) }
        )
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

/** Actionable status hint shown when the real hardware is unavailable. */
@Composable
private fun HardwareNotice(title: String, detail: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = Navy.copy(alpha = .96f),
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 5.dp
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
            Text(title, color = NeonGreen, style = MaterialTheme.typography.labelLarge)
            Text(detail, color = SoftText, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}


/** Live OBD results only. Experimental MEB PID scales stay labelled and
 * NEVER become validated BMS SoC or charging-plan inputs automatically.
 */
/**
 * Compact landscape summary. A confirmed BMS SoC is NOT interchangeable with
 * experimental community UDS scaling, and a route estimate is not live range.
 * All engineering/experimental values remain on the separate diagnostic sheet.
 */
@Composable
private fun TelemetryLeftPanel(
    data: CockpitUiState,
    connected: Boolean,
    compact: Boolean,
    obdStatus: String,
    soundUnavailable: Boolean,
    onConnect: () -> Unit,
    onEngineering: () -> Unit,
    onSoundSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.background(Ink)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (compact) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("SÜRÜŞ", color = NeonGreen,
                    style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Text(obdStatus,
                    color = if (connected) NeonGreen else SoftText,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CompactMetricTile("HIZ · OBD", CockpitFormat.speed(if (connected) data.speedKmh else null),
                    modifier = Modifier.weight(1f))
                CompactMetricTile("SoC · BMS", CockpitFormat.percent(if (connected) data.socPercent else null),
                    modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CompactMetricTile("GÜÇ · OBD", CockpitFormat.power(if (connected) data.powerKw else null),
                    modifier = Modifier.weight(1f))
                CompactMetricTile("MENZİL · TAHMİN",
                    CockpitFormat.distance(if (connected && data.socPercent != null)
                        data.estimatedRangeKm else null), modifier = Modifier.weight(1f))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = onEngineering, modifier = Modifier.weight(1f)) {
                    Text("Teknik", fontSize = 11.sp, maxLines = 1)
                }
                TextButton(
                    onClick = if (connected) onSoundSettings else onConnect,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (connected) "Ses" else "OBD bağlan",
                        fontSize = 11.sp, maxLines = 1)
                }
            }
            Text("— = doğrulanmış veri yok. Menzil model tahminidir.",
                style = MaterialTheme.typography.labelSmall, color = SoftText,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (soundUnavailable) {
                TextButton(onClick = onSoundSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Motor sesi kullanılamıyor · Ayarlar", fontSize = 10.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
        Text(
            "ARAÇ · SÜRÜŞ ÖZETİ",
            color = NeonGreen,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            if (connected) "ELM327 bağlı · ölçümler uygun olduğunda gösterilir"
            else obdStatus,
            color = SoftText,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2
        )
        SummaryMetricRow(
            title = "HIZ",
            value = CockpitFormat.speed(if (connected) data.speedKmh else null),
            provenance = "OBD PID 010D · gerçek ölçüm",
            prominent = true
        )
        SummaryMetricRow(
            title = "BATARYA DOLULUĞU",
            value = CockpitFormat.percent(if (connected) data.socPercent else null),
            provenance = "Yalnız doğrulanmış BMS SoC"
        )
        SummaryMetricRow(
            title = "BATARYA GÜCÜ",
            value = CockpitFormat.power(if (connected) data.powerKw else null),
            provenance = "OBD ölçümü · mevcutsa"
        )
        SummaryMetricRow(
            title = "TAHMİNİ MENZİL",
            value = CockpitFormat.distance(
                if (connected && data.socPercent != null) data.estimatedRangeKm else null
            ),
            provenance = "Model tahmini · canlı menzil değil"
        )
        OutlinedButton(
            onClick = onEngineering,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Analytics, contentDescription = null,
                modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Teknik ölçümler", maxLines = 1)
        }
        if (!connected) {
            OutlinedButton(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("OBD'ye bağlan")
            }
        }
        TextButton(
            onClick = onSoundSettings,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Ses ayarları", maxLines = 1)
        }
        Text(
            "SoH ve deneysel MEB PID değerleri ana ekranda canlı BMS " +
                "ölçümü olarak gösterilmez. Ayrıntılar Teknik ölçümler bölümünde.",
            color = SoftText,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 4
        )
        }
    }
}

@Composable
private fun CompactMetricTile(title: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Navy, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(title, color = SoftText, fontSize = 9.sp, lineHeight = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, color = if (value.startsWith("—")) SoftText else Color.White,
            fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/** Fixed label/value rows prevent long diagnostics from competing with driving data. */
@Composable
private fun SummaryMetricRow(
    title: String,
    value: String,
    provenance: String,
    prominent: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Navy)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    color = SoftText,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    value,
                    color = if (value.startsWith("—")) SoftText else Color.White,
                    fontSize = if (prominent) 23.sp else 19.sp,
                    lineHeight = if (prominent) 29.sp else 25.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false
                )
            }
            Text(
                provenance,
                color = SoftText,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun NavigationGlassCard(
    route: RouteSnapshot?,
    loading: Boolean,
    compact: Boolean,
    mapFocused: Boolean,
    onToggleFocus: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The focus toggle and route summary own independent touch targets. A
    // clickable Surface parent would surround the nested TextButton.
    Surface(
        modifier = modifier,
        color = Color(0xCF0A1728),
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 4.dp
    ) {
        Column(
            Modifier.padding(
                horizontal = if (compact) 8.dp else 14.dp,
                vertical = if (compact) 4.dp else 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "NAVİGASYON",
                    modifier = Modifier.weight(1f),
                    color = NeonGreen,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
                TextButton(
                    onClick = onToggleFocus,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        if (mapFocused) "Göstergeler" else "Haritayı büyüt",
                        color = NeonGreen,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onOpen),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    route?.nextTurnInstruction?.takeIf { it.isNotBlank() }
                        ?: if (loading) "Rota hesaplanıyor…" else "Rota çizmek için dokunun",
                    color = Color.White,
                    style = if (compact) MaterialTheme.typography.bodyMedium
                            else MaterialTheme.typography.titleSmall,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
                val arrival = route?.estimatedArrivalEpochMs?.let(CockpitFormat::eta)
                val left = route?.remainingKm?.let(CockpitFormat::distance)
                if (arrival != null || left != null) {
                    Text(
                        listOfNotNull(left, arrival?.let { "Varış $it" }).joinToString(" · "),
                        color = SoftText,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Surface(
        modifier = modifier.height(if (compact) 44.dp else 36.dp)
            .clickable(onClick = onClick),
        color = Color(0xE50A1728), shape = RoundedCornerShape(10.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
                .padding(horizontal = if (compact) 4.dp else 10.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text, color = SoftText,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

