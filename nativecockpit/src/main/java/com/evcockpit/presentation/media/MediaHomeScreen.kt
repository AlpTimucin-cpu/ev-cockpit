package com.evcockpit.presentation.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val PanelDark = Color(0xFF0B1725)
private val Accent = Color(0xFF50E4AB)
private val Muted = Color(0xFFB2C5D3)

/**
 * Separate landscape media page, based on the large-category-card navigation
 * in the Mola reference video. No launcher Intent or companion APK required.
 */
@Composable
fun MediaHomeScreen(
    phoneAllowed: Boolean,
    obdConnected: Boolean,
    freshVehicleSpeedKmh: Double?,
    modifier: Modifier = Modifier
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<MediaSite?>(null) }
    val context = LocalContext.current
    var musicLaunchError by remember { mutableStateOf<String?>(null) }
    // This acknowledgement is not an authoritative vehicle Park measurement.
    var userSaysParked by rememberSaveable { mutableStateOf(false) }
    val videoAllowed = PhoneVideoSafetyPolicy.allow(
        phoneNotProjected = phoneAllowed,
        userConfirmedParked = userSaysParked,
        obdConnected = obdConnected,
        freshVehicleSpeedKmh = freshVehicleSpeedKmh
    )

    LaunchedEffect(phoneAllowed, obdConnected, freshVehicleSpeedKmh) {
        if (!PhoneVideoSafetyPolicy.allow(phoneAllowed, true, obdConnected,
                freshVehicleSpeedKmh)) selected = null
    }

    Column(
        modifier.fillMaxSize().background(Color(0xFF07111D))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("MEDYA MERKEZİ", style = MaterialTheme.typography.titleLarge,
                    color = Color.White)
                Text("EV Cockpit · Dahili oynatıcı", color = Muted,
                    style = MaterialTheme.typography.labelSmall)
            }
            Surface(color = PanelDark, shape = RoundedCornerShape(12.dp)) {
                Text(
                    if (selected == null) "Oynatılan içerik yok" else selected!!.title,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }
        }
        TabRow(
            selectedTabIndex = tab,
            containerColor = PanelDark,
            contentColor = Accent,
            divider = {}
        ) {
            listOf("Video", "Radyo", "Müzik").forEachIndexed { index, name ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index; selected = null },
                    text = { Text(name) }
                )
            }
        }
        // Remaining height is owned by the active tab, never by nested panels.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
            0 -> {
                if (selected == null) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Video servisleri", color = Color.White,
                            style = MaterialTheme.typography.titleMedium)
                        Text(
                            "WebView oynatması özellikle DRM kullanan servislerde " +
                                "desteklenmeyebilir. Video yalnızca park hâlinde açılmalıdır.",
                            color = Muted, style = MaterialTheme.typography.bodySmall
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = userSaysParked,
                                onCheckedChange = { userSaysParked = it }
                            )
                            Text("Aracın park halinde olduğunu onaylıyorum",
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (!PhoneVideoSafetyPolicy.allow(phoneAllowed, true, obdConnected,
                                freshVehicleSpeedKmh)) {
                            Text(
                                "Android Auto bağlıysa, güncel OBD hızı yoksa veya araç hareketliyse " +
                                    "video kilitlidir. Durmak Park konumu doğrulaması değildir.",
                                color = Color(0xFFFFC582),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        MediaSite.entries.forEach { service ->
                            val description = when (service) {
                                MediaSite.YOUTUBE -> "Video ve kanal araması · WebView"
                                MediaSite.NETFLIX -> "Netflix web kataloğu · DRM kısıtlı olabilir"
                                MediaSite.DISNEY -> "Disney+ web kataloğu · DRM kısıtlı olabilir"
                                MediaSite.PRIME -> "Prime Video web kataloğu · DRM kısıtlı olabilir"
                            }
                            Card(
                                modifier = Modifier.fillMaxWidth()
                                    .heightIn(min = 83.dp),
                                enabled = videoAllowed,
                                onClick = { selected = service },
                                colors = CardDefaults.cardColors(
                                    containerColor = PanelDark,
                                    disabledContainerColor = PanelDark.copy(alpha = .5f)
                                ),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(
                                                    when (service) {
                                                        MediaSite.YOUTUBE -> Color(0xFF642435)
                                                        MediaSite.NETFLIX -> Color(0xFF50212B)
                                                        MediaSite.DISNEY -> Color(0xFF142D65)
                                                        MediaSite.PRIME -> Color(0xFF16445C)
                                                    },
                                                    PanelDark
                                                )
                                            )
                                        )
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(service.title, color = Color.White,
                                            style = MaterialTheme.typography.titleMedium)
                                        Text(description, color = Muted, maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("›", color = Accent,
                                        style = MaterialTheme.typography.headlineMedium)
                                }
                            }
                        }
                    }
                } else {
                    val service = selected!!
                    // In-app WebView for ALL four services; no external launcher.
                    MediaWebViewScreen(
                        service = service,
                        playbackAllowed = videoAllowed,
                        onClose = { selected = null }
                    )
                }
            }
            1 -> Box(Modifier.fillMaxSize()) {
                RadioScreen(compact = true)
            }
            2 -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Resmî müzik uygulamaları",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Text(
                    "ZIP referansındaki Spotify / YouTube Music kısayolları. " +
                        "Yalnızca telefonda yüklü olan resmî uygulama açılır; " +
                        "abonelik ve oynatma o uygulamanın sorumluluğundadır.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OfficialMusicApp.entries.forEach { app ->
                        OutlinedButton(
                            onClick = {
                                musicLaunchError = when (
                                    MediaLauncher.launchOfficialMusicApp(context, app)
                                ) {
                                    is MediaLaunchState.Launched -> null
                                    MediaLaunchState.NotInstalled ->
                                        "${app.title} yüklü değil. Resmî uygulamayı yükleyin."
                                    MediaLaunchState.Blocked ->
                                        "${app.title} Android tarafından başlatılamadı."
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(app.title) }
                    }
                }
                musicLaunchError?.let { message ->
                    Text(
                        message,
                        color = Color(0xFFFFC582),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                HorizontalDivider(color = Color(0xFF344252))
                InternalMediaPlayer(
                    modifier = Modifier.fillMaxWidth(),
                    allowVideo = false
                )
            }
            }
        }
    }
}
