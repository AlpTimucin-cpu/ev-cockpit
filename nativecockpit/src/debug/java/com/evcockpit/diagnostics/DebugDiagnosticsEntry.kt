package com.evcockpit.diagnostics

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.evcockpit.BuildConfig
import com.evcockpit.car.CarWeatherRefreshGate
import com.evcockpit.domain.usecase.TrafficEvidencePolicy
import com.evcockpit.presentation.onboarding.PermissionSnapshot
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Exists exclusively in src/debug; no diagnostic UI exists in Release.
 * Shares one private text file through an explicit read-only URI grant.
 */
@Composable
fun DebugDiagnosticsEntry(hub: DiagnosticsHub) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Tanılama · DEBUG")
    }
    if (!open) return

    LaunchedEffect(Unit) { hub.event(DiagnosticEvent.OPENED) }
    val state by hub.snapshot.collectAsStateWithLifecycle()
    val report = diagnosticReport(context, state, hub)
    Dialog(
        onDismissRequest = { open = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(.96f).fillMaxHeight(.94f),
            color = Color(0xFF101A24),
            contentColor = Color.White
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text("Tanılama · TEST", style = MaterialTheme.typography.titleLarge)
                Text("Yalnız uygulama olayları; sistem logcat'i değil. " +
                    "Hassas veriler kaydedilmez, günlük uygulama kapanınca silinir.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        val clipboard = context.getSystemService(
                            Context.CLIPBOARD_SERVICE
                        ) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(
                            "EV Cockpit tanılama", report
                        ))
                        Toast.makeText(context, "Maskeli günlük kopyalandı",
                            Toast.LENGTH_SHORT).show()
                    }) { Text("Kopyala") }
                    OutlinedButton(onClick = {
                        try {
                            val directory = File(context.filesDir, "diagnostics")
                            check(directory.isDirectory || directory.mkdirs())
                            val file = File(directory, "ev-cockpit-debug.txt")
                            file.writeText(report, Charsets.UTF_8)
                            val uri = FileProvider.getUriForFile(
                                context, BuildConfig.APPLICATION_ID + ".diagnostics", file
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                clipData = ClipData.newUri(context.contentResolver,
                                    "EV Cockpit tanılama", uri)
                            }
                            context.startActivity(Intent.createChooser(
                                intent, "Maskeli tanılamayı paylaş"
                            ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (_: Exception) {
                            Toast.makeText(context, "Dosya paylaşılamadı; Kopyala'yı kullanın.",
                                Toast.LENGTH_LONG).show()
                        }
                    }) { Text("Paylaş (.txt)") }
                }
                OutlinedButton(onClick = { open = false },
                    modifier = Modifier.fillMaxWidth()) { Text("Kapat") }
                Text("Durum özeti", style = MaterialTheme.typography.titleMedium)
                Text(report.substringBefore("\n--- OLAY GÜNLÜĞÜ ---"),
                    style = MaterialTheme.typography.bodySmall)
                Text("Son " + state.entries.size + " / " + DiagnosticsHub.MAX_LINES +
                    " uygulama olayı", style = MaterialTheme.typography.titleMedium)
                Text(state.entries.asReversed().joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

internal fun diagnosticReport(
    context: Context, state: DiagnosticsSnapshot, hub: DiagnosticsHub
): String {
    fun permission(permission: String): String =
        if (ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) "verildi" else "verilmedi"
    val location = "hassas=" + permission(Manifest.permission.ACCESS_FINE_LOCATION) +
        ", yaklaşık=" + permission(Manifest.permission.ACCESS_COARSE_LOCATION)
    val notification = if (Build.VERSION.SDK_INT >= 33)
        permission(Manifest.permission.POST_NOTIFICATIONS)
    else "Android 13 öncesi: çalışma zamanı izni gerekmez"
    val bluetooth = if (Build.VERSION.SDK_INT >= 31)
        permission(Manifest.permission.BLUETOOTH_CONNECT)
    else permission(Manifest.permission.BLUETOOTH)
    fun stamp(epoch: Long?): String {
        if (epoch == null || epoch <= 0L) return "kullanılamıyor"
        return runCatching {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.forLanguageTag("tr-TR"))
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epoch))
        }.getOrDefault("kullanılamıyor")
    }
    val map = if (state.mapWidthPx == null || state.mapHeightPx == null ||
        state.mapDpi == null) "kullanılamıyor" else
        state.mapWidthPx.toString() + "×" + state.mapHeightPx +
            ", " + state.mapDpi + " dpi"
    val gps = if (state.gpsAtMillis == null || state.gpsAccuracyMeters == null)
        "kullanılamıyor" else stamp(state.gpsAtMillis) + " / " +
        String.format(Locale.forLanguageTag("tr-TR"), "%.1f m", state.gpsAccuracyMeters)
    val car = when (state.carScreenActive) {
        true -> "Car Screen etkin (fiziksel USB ayrı doğrulanmalı)"
        false -> "Car Screen etkin değil (USB durumu bilinmiyor)"
        null -> "kullanılamıyor"
    }
    return buildString {
        appendLine("Sürüm: " + BuildConfig.VERSION_NAME + " · " +
            BuildConfig.APPLICATION_ID + " · debug=" + BuildConfig.DEBUG)
        appendLine("İzinler: konum " + location + " · bildirim " + notification +
            " · Bluetooth " + bluetooth)
        val permissionIssues = PermissionSnapshot.read(context).missingCapabilities()
        appendLine("İzin etkileri: " + if (permissionIssues.isEmpty())
            "İzin kısıtı görünmüyor; sensör/araç bağlantısı doğrulanmadı."
        else permissionIssues.joinToString(" | "))
        appendLine("Foreground konum: " + if (state.locationFgsActive) "çalışıyor" else "çalışmıyor")
        appendLine("Foreground OBD: " + if (state.obdFgsActive) "çalışıyor" else "çalışmıyor")
        appendLine("Son gerçek GPS: " + gps)
        appendLine("Android Auto: " + car)
        appendLine("Harita Surface: " + map)
        appendLine("Harita insets (sol,üst,sağ,alt px): " +
            (state.mapInsetsPx ?: "kullanılamıyor"))
        appendLine("Son hava yenileme denemesi: " + stamp(state.weatherAttemptMillis))
        appendLine("Son başarılı hava verisi: " + stamp(state.weatherReceivedMillis))
        appendLine("Hava sonucu: " + state.weatherOutcome)
        appendLine("Trafik: " + state.trafficStatus)
        appendLine("TomTom son gerçek istek: " + stamp(state.trafficAttemptMillis))
        appendLine("TomTom son yanıt alınma: " + stamp(state.trafficReceivedMillis))
        appendLine("TomTom HTTP sunucu zamanı: " + stamp(state.trafficServerMillis) +
            " · ölçüm/model zamanı değildir")
        appendLine("TomTom olay sayısı (yakın rota): " +
            (state.trafficIncidentsCount?.toString() ?: "kullanılamıyor"))
        appendLine("TomTom akış raster karo sayısı: " +
            (state.trafficFlowTilesCount?.toString() ?: "kullanılamıyor"))
        appendLine("Trafikli ETA: kullanılamıyor · OSRM ETA: trafiksiz tahmin")
        appendLine("Hız sınırı: kullanılamıyor")
        appendLine("Hava güncel MODEL geçerliliği (UTC): " +
            (state.weatherCurrentModelUtc ?: "kullanılamıyor"))
        appendLine("Hava rota tahmin noktası (son başarılı istek): " +
            (state.weatherRoutePointCount?.toString() ?: "kullanılamıyor"))
        appendLine("Hava otomatik aralığı (Android Auto ön planda): " +
            CarWeatherRefreshGate.AUTOMATIC_REFRESH_MS / 60_000L + " dk")
        appendLine("Son EPDK veri zamanı (UTC): " +
            (state.chargeSnapshotUtc ?: "kullanılamıyor"))
        appendLine("EPDK: " + state.chargingStatus)
        appendLine("EPDK son indirme denemesi: " + stamp(state.chargeDownloadAttemptMillis))
        appendLine("EPDK son başarılı snapshot indirilişi: " +
            stamp(state.chargeDownloadSuccessMillis))
        appendLine("EPDK doğrulanmış coğrafi kayıt sayısı: " +
            (state.chargeNationalRowsCount?.toString() ?: "kullanılamıyor"))
        appendLine("EPDK halka açık ham kayıt sayısı: " +
            (state.chargePublicRowsCount?.toString() ?: "kullanılamıyor"))
        appendLine("EPDK kaynak: resmî EPDK REST kayıtlarından tarihli yayın snapshot'ı; " +
            "anlık doluluk ve tarife içermez")
        appendLine("OBD bağlantı: " + state.obdStatus)
        appendLine("Son izinli UDS ham yanıt öneki: " + state.obdRawMasked)
        appendLine("Android Auto yeniden bağlanma: " + state.carReattachments)
        appendLine("Not: ID.4 Pure SoC DID, adres ve çarpan doğrulanmadı.")
        appendLine()
        appendLine("--- OLAY GÜNLÜĞÜ ---")
        append(hub.exportText())
    }
}
