package com.evcockpit.presentation.settings

import com.evcockpit.BuildConfig
import com.evcockpit.diagnostics.DiagnosticsHub
import com.evcockpit.diagnostics.DebugDiagnosticsEntry
import com.evcockpit.car.CarContinuityPhase
import com.evcockpit.car.CarContinuityState
import com.evcockpit.domain.usecase.TrafficEvidencePolicy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.evcockpit.data.settings.UserSettingsRepository
import com.evcockpit.domain.model.ZipId4PureReference
import com.evcockpit.domain.model.UserDrivingScenarios
import com.evcockpit.presentation.sound.ActiveSoundManager
import java.util.Locale

/** Separate Material3 panel; the original Tesla-style CockpitScreen is untouched. */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    activeSound: ActiveSoundManager,
    diagnosticsHub: DiagnosticsHub,
    carNavigationState: CarContinuityState,
    onRetryCarNavigation: () -> Unit,
    onStopCarNavigation: () -> Unit,
    onClose: () -> Unit,
    onOpenPermissions: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val voices by viewModel.voiceOptions.collectAsStateWithLifecycle()
    val configuration by viewModel.appConfiguration.collectAsStateWithLifecycle()
    val configurationError by viewModel.configError.collectAsStateWithLifecycle()
    val trafficAccess by viewModel.trafficAccess.collectAsStateWithLifecycle()
    val trafficResult by viewModel.trafficResult.collectAsStateWithLifecycle()
    var newTomTomKey by remember { mutableStateOf("") }
    var batteryEntry by remember(configuration.vehicle) {
        mutableStateOf(configuration.vehicle.usableBatteryKwh?.toString().orEmpty())
    }
    var dcEntry by remember(configuration.vehicle) {
        mutableStateOf(configuration.vehicle.peakDcKw?.toString().orEmpty())
    }
    var consumptionEntry by remember(configuration.vehicle) {
        mutableStateOf(configuration.vehicle.consumptionKwhPer100Km?.toString().orEmpty())
    }
    var reserveEntry by remember(configuration.vehicle) {
        mutableStateOf(configuration.vehicle.arrivalReservePercent?.toString().orEmpty())
    }
    // ZIP candidate data is never saved or marked verified by the import button.
    var zipDraftStaged by remember(configuration.vehicle) { mutableStateOf(false) }
    var osrmEntry by remember(configuration.endpoints) {
        mutableStateOf(configuration.endpoints.osrmBaseUrl.orEmpty())
    }
    var geocodingEntry by remember(configuration.endpoints) {
        mutableStateOf(configuration.endpoints.geocodingBaseUrl)
    }
    var weatherEntry by remember(configuration.endpoints) {
        mutableStateOf(configuration.endpoints.weatherBaseUrl)
    }
    var voicesExpanded by remember { mutableStateOf(false) }
    var profilesExpanded by remember { mutableStateOf(false) }
    var zipLabExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadInstalledTurkishVoices() }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(.94f).fillMaxHeight(.88f),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xF20A1522),
            contentColor = Color.White,
            shadowElevation = 16.dp
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Text("Araç ve Servis Ayarları", style = MaterialTheme.typography.titleLarge)
                Text("TomTom Orbis · " + trafficAccess.statusTr,
                    style = MaterialTheme.typography.bodySmall)
                Text("Trafik verisi için mevcut GPS/rota konumu TomTom'a gönderilir. " +
                    "Yalnız kendi yeni anahtarınızı girin; eski/sızmış anahtarı kullanmayın. " +
                    "Anahtar cihazda Android Keystore AES-GCM ile şifrelenir; " +
                    "kodu, raporları ve GitHub'ı etkilemez.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = newTomTomKey,
                    onValueChange = { newTomTomKey = it.take(256) },
                    label = { Text("Yeni TomTom API anahtarı (gizli)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = {
                    viewModel.saveTomTomKey(newTomTomKey)
                    newTomTomKey = ""
                }, enabled = newTomTomKey.length in 8..256) {
                    Text("Anahtarı cihazda şifreleyerek kaydet")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = trafficAccess.enabled,
                        enabled = trafficAccess.keyAvailable,
                        onCheckedChange = viewModel::setTrafficEnabled
                    )
                    Text("Rota konumunu TomTom ile paylaş; gerçek trafik al",
                        modifier = Modifier.padding(start = 10.dp))
                }
                OutlinedButton(onClick = viewModel::clearTomTomKey,
                    enabled = trafficAccess.keyAvailable) {
                    Text("TomTom anahtarını sil ve trafiği kapat")
                }
                trafficResult?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Text("OSRM ETA trafiksiz tahmindir; TomTom trafikli ETA ve doğrulanmış " +
                    "hız limiti mevcut değilse 'kullanılamıyor'.",
                    style = MaterialTheme.typography.bodySmall)
                if (BuildConfig.DEBUG) {
                    DebugDiagnosticsEntry(diagnosticsHub)
                }
                OutlinedButton(
                    onClick = onOpenPermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Konum, Bluetooth ve bildirim izinlerini kontrol et")
                }
                HorizontalDivider()
                Text("Android Auto · Arka plan GPS", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Yalnız sizin başlattığınız Android Auto navigasyonu için konum foreground " +
                        "servisi kullanılır. Bildirimden istediğiniz zaman kapatabilirsiniz. " +
                        "Uygulama tamamen öldürülürse oturum kendiliğinden yeniden açılmaz.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    when (carNavigationState.phase) {
                        CarContinuityPhase.OFF -> "Etkin arka plan navigasyonu yok."
                        CarContinuityPhase.STARTING -> "Konum hizmeti başlatılıyor."
                        CarContinuityPhase.ACTIVE -> "Konum hizmeti etkin; gerçek GPS güncel."
                        CarContinuityPhase.GPS_UNAVAILABLE ->
                            "Konum hizmeti açık; GPS geçici olarak kullanılamıyor."
                        CarContinuityPhase.PHONE_ACTION_REQUIRED ->
                            "Android Auto konum servisini arka plandan başlatmayı engelledi. " +
                                "Bu ekrandaki düğmeyle telefon görünürken yeniden deneyin."
                    }, style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFAEC4D6)
                )
                if (carNavigationState.phase == CarContinuityPhase.PHONE_ACTION_REQUIRED) {
                    Button(onClick = onRetryCarNavigation, modifier = Modifier.fillMaxWidth()) {
                        Text("Telefon üzerinden arka plan GPS'i başlat")
                    }
                }
                if (carNavigationState.phase != CarContinuityPhase.OFF) {
                    OutlinedButton(
                        onClick = onStopCarNavigation,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Arka plan navigasyonunu durdur") }
                }
                HorizontalDivider()
                if (BuildConfig.DEBUG) {
                    SettingToggle(
                        "TEST: OSRM deneme rotasını aç",
                        configuration.endpoints.debugDemoRoutingEnabled,
                        viewModel::setDebugDemoRoutingEnabled
                    )
                    Text(
                        "Rota çizmek için BMS/araç profili onayı gerekmez. " +
                            "OSRM denemesi geçici ve sınırlıdır; navigasyon " +
                            "güvenliği için üretimde özel servis gerekir.",
                        color = Color(0xFFFFCA80),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    "VW ID.4 Pure için 52 kWh net batarya bilgisi kullanıcı beyanıdır. " +
                        "DC tepe gücü doğrulanmadı. Bunlar otomatik ölçüm değildir; " +
                        "değerleri kendiniz kontrol edip kaydetmelisiniz.",
                    color = Color(0xFFAEC4D6),
                    style = MaterialTheme.typography.bodySmall
                )
                HorizontalDivider()
                Text(
                    "Yüklenen ZIP · ID.4 Pure referans bilgileri",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "ZIP prototipi: ${ZipId4PureReference.DRIVE_POWER_KW} kW / " +
                        "${ZipId4PureReference.DRIVE_POWER_PS} PS, net batarya " +
                        "${ZipId4PureReference.CANDIDATE_NET_BATTERY_KWH.toInt()} kWh, " +
                        "DC tepe ${ZipId4PureReference.CANDIDATE_PEAK_DC_KW.toInt()} kW, " +
                        "AC ${ZipId4PureReference.CANDIDATE_AC_KW.toInt()} kW, " +
                        "WLTP ${ZipId4PureReference.CLAIMED_WLTP_MIN_KM}–" +
                        "${ZipId4PureReference.CLAIMED_WLTP_MAX_KM} km (prototip iddiası).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFAEC4D6)
                )
                Text(
                    "Bu veriler üretici sertifikası veya araçtan okunan BMS ölçümü değildir. " +
                        "ZIP derlenmemiştir. 145 kW DC, 11 kW AC ve WLTP değerlerini " +
                        "aracına özel olarak doğrula. Mevcut ayarlar otomatik değiştirilmez.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val draft = ZipId4PureReference.stagedDraft(configuration.vehicle)
                        batteryEntry = draft.usableBatteryKwh?.toString().orEmpty()
                        dcEntry = draft.peakDcKw?.toString().orEmpty()
                        zipDraftStaged = true
                    }
                ) { Text("ZIP'deki 52 kWh / 145 kW değerlerini taslağa al") }
                if (zipDraftStaged) {
                    Text(
                        "ZIP taslağı yalnızca giriş alanlarına aktarıldı, kaydedilmedi. " +
                            "Tüketim ve varış rezervini ayrıca belirleyip doğrulama " +
                            "düğmesine basman gerekir.",
                        color = Color(0xFFFFCA80),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                HorizontalDivider()
                OutlinedTextField(
                    value = batteryEntry, onValueChange = { batteryEntry = it },
                    label = { Text("Kullanılabilir batarya (kWh) — teyit gerekli") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = dcEntry, onValueChange = { dcEntry = it },
                    label = { Text("DC tepe şarj (kW) — bilinmiyorsa boş") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = consumptionEntry, onValueChange = { consumptionEntry = it },
                    label = { Text("Referans tüketim (kWh/100 km) — senaryo") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Text(
                    "Kişisel sürüş tüketimi senaryosu — gerçek BMS ölçümü değil",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            consumptionEntry = UserDrivingScenarios.summer
                                .midpointKwhPer100Km.toString()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Yaz · 10,5") }
                    OutlinedButton(
                        onClick = {
                            consumptionEntry = UserDrivingScenarios.winter
                                .midpointKwhPer100Km.toString()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Kış · 18,0") }
                }
                OutlinedTextField(
                    value = reserveEntry, onValueChange = { reserveEntry = it },
                    label = { Text("Varış rezervi (%) — kullanıcı tercihi") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Button(
                    onClick = {
                        viewModel.saveVehicleConfig(
                            batteryEntry, dcEntry, consumptionEntry, reserveEntry
                        )
                    }, modifier = Modifier.fillMaxWidth()
                ) { Text("Araç parametrelerini kullanıcı olarak onayla") }
                Text(
                    when {
                        zipDraftStaged -> "ZIP taslağı kaydedilmedi; önce gözden geçirip onayla."
                        configuration.vehicle.userConfirmed ->
                            "Kullanıcı teyitli senaryo parametreleri. CAN/BMS ölçümü değildir."
                        else -> "Araç parametreleri teyit edilmedi — enerji hesabı kullanılamıyor."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                HorizontalDivider()
                OutlinedButton(
                    onClick = { zipLabExpanded = !zipLabExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (zipLabExpanded) "ZIP deney laboratuvarını kapat"
                        else "ZIP'deki riskli özellikleri çevrimdışı incele"
                    )
                }
                if (zipLabExpanded) ZipPrototypeLabPanel()
                HorizontalDivider()
                Text("Ağ hizmetleri (HTTPS)", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = osrmEntry, onValueChange = { osrmEntry = it },
                    label = { Text("Kendi OSRM sunucunuzun kök adresi") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Text(
                    "Özel HTTPS OSRM adresi varsa rota bununla hesaplanır. " +
                        "Rota çizmek için BMS bağlantısı veya araç batarya onayı gerekmez.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (BuildConfig.DEBUG) {
                    Text(
                        "Yalnızca TEST APK'da açık kullanıcı tercihiyle çalışır. " +
                            "Bu ortak demo servisi yoğun olabilir, rota geçici olarak " +
                            "çalışmayabilir ve gerçek sürüşte güvenilir navigasyon " +
                            "hizmeti olarak kullanılmamalıdır. " +
                            "En fazla 15 saniyede bir rota isteği yapılır; " +
                            "otomatik şarj durağı sapma sorguları devre dışıdır. " +
                            "Üretim sürümünde bu seçenek etkisizdir.",
                        color = Color(0xFFFFCA80),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        if (configuration.endpoints.debugDemoRoutingEnabled)
                            "Deneme OSRM tercihi açık. Rota ekranına dönüp yeniden hesapla."
                        else "Özel OSRM adresin yoksa TEST yönlendirmesini burada açabilirsin.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedTextField(
                    value = geocodingEntry, onValueChange = { geocodingEntry = it },
                    label = { Text("Adres arama (Nominatim/Photon) HTTPS adresi") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = weatherEntry, onValueChange = { weatherEntry = it },
                    label = { Text("Open-Meteo HTTPS servis kök adresi") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedButton(
                    onClick = { viewModel.saveServiceUrls(osrmEntry, geocodingEntry, weatherEntry) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Hizmet adreslerini kaydet") }
                configurationError?.let { issue ->
                    Text(issue, color = Color(0xFFFFC4C4), style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()

                Text("Ses ve Görünüm Ayarları", style = MaterialTheme.typography.titleLarge)
                HorizontalDivider()

                Text("Sesli Asistan", style = MaterialTheme.typography.titleMedium)
                SettingToggle(
                    "Araca binerken karşıla", settings.isWelcomeGreetingEnabled,
                    viewModel::setWelcome
                )

                Text("Türkçe karşılama sesi", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = { voicesExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val current = voices.firstOrNull { it.name == settings.ttsVoiceName }
                    Text(current?.label ?: "Sistem varsayılan Türkçe sesi")
                }
                DropdownMenu(
                    expanded = voicesExpanded,
                    onDismissRequest = { voicesExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Sistem Türkçe sesi") },
                        onClick = {
                            viewModel.setVoice(null)
                            voicesExpanded = false
                        }
                    )
                    voices.forEach { v ->
                        DropdownMenuItem(
                            text = { Text(v.label) },
                            onClick = {
                                viewModel.setVoice(v.name)
                                voicesExpanded = false
                            }
                        )
                    }
                }
                Text(
                    "Android ses motorları cinsiyet garantisi vermez. Kadın/Erkek etiketi yalnız " +
                        "ses dosyasının adında mevcutsa gösterilir. Bu seçim Türkçe karşılamaya uygulanır.",
                    color = Color(0xFFAEC4D6),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Konuşma hızı: " + String.format(Locale.US, "%.2fx", settings.ttsSpeechRate),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = settings.ttsSpeechRate,
                    onValueChange = viewModel::setRate,
                    valueRange = .7f..1.4f,
                    steps = 6
                )

                HorizontalDivider()
                Text("Active Sound · İç mekân", style = MaterialTheme.typography.titleMedium)
                SettingToggle(
                    "Yapay motor sesi", settings.isEngineSoundEnabled,
                    viewModel::setEngineEnabled
                )

                Text("Motor sesi profili", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = { profilesExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(settings.engineSoundProfile) }
                DropdownMenu(
                    expanded = profilesExpanded,
                    onDismissRequest = { profilesExpanded = false }
                ) {
                    UserSettingsRepository.ENGINE_PROFILES.forEach { profile ->
                        DropdownMenuItem(
                            text = { Text(profile) },
                            onClick = {
                                viewModel.setEngineProfile(profile)
                                profilesExpanded = false
                            }
                        )
                    }
                }
                Text("Motor sesi seviyesi: " +
                    (settings.engineVolume / 1.5f * 150).toInt() + "%",
                    style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = settings.engineVolume,
                    onValueChange = viewModel::setEngineVolume,
                    valueRange = 0f..1.5f,
                    steps = 14
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { activeSound.preview(settings) }
                ) { Text("Sesi Dinle · 3 saniye") }
                Text(
                    "%150 arayüz ayarı Media3 içinde güvenli 0–1 aralığına ölçeklenir. " +
                        "Bu özellik aracın dış AVAS sisteminin yerine geçmez; " +
                        "OBD hızı yoksa motor sesi otomatik durur.",
                    color = Color(0xFFAEC4D6),
                    style = MaterialTheme.typography.bodySmall
                )

                HorizontalDivider()
                SettingToggle(
                    "Daha karanlık gece teması",
                    settings.isNightModeEnabled,
                    viewModel::setNight
                )
                Spacer(Modifier.height(2.dp))
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Kapat") }
            }
        }
    }
}

@Composable
private fun SettingToggle(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
