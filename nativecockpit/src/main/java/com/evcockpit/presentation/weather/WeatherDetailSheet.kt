package com.evcockpit.presentation.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.evcockpit.domain.model.RouteWeatherState
import com.evcockpit.domain.model.WeatherWaypoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Phone-only weather model. Does not show exact GPS or claim a station observation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherDetailSheet(
    state: RouteWeatherState,
    routeId: String?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val nowMs = System.currentTimeMillis()
    val formatter = DateTimeFormatter.ofPattern(
        "dd.MM HH:mm", Locale.forLanguageTag("tr-TR")
    )
    fun atLocalTime(epochMs: Long?): String {
        if (epochMs == null || epochMs <= 0L) return "kullanılamıyor"
        return runCatching {
            Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault()).format(formatter)
        }.getOrDefault("kullanılamıyor")
    }
    fun validAt(iso: String): String = runCatching {
        Instant.parse(iso).atZone(ZoneId.systemDefault()).format(formatter)
    }.getOrDefault("kullanılamıyor")
    fun summary(point: WeatherWaypoint): String = String.format(
        Locale.forLanguageTag("tr-TR"),
        "%s · %.0f °C · rüzgâr %.0f km/sa",
        point.summaryTr, point.temperatureC, point.windSpeedKmh
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Hava durumu · Open-Meteo", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onDismiss) { Text("Kapat") }
            }
            Text(
                "Veriler Open-Meteo MODEL/TAHMİN sonuçlarıdır; meteoroloji istasyonu " +
                    "ölçümü, canlı yol bilgisi veya resmî MGM uyarısı değildir.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = onRefresh) { Text("Elle yenile") }
            if (state.loading) Text("Hava modeli yükleniyor…")
            state.error?.let { Text("Durum: $it", style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            Text("Mevcut GPS konumu · güncel MODEL", fontWeight = FontWeight.Bold)
            val current = state.current
            if (current == null) {
                Text("Güncel model kullanılamıyor.")
            } else {
                Text(summary(current))
                Text(
                    "Model geçerlilik: ${validAt(current.observedAt)} · " +
                        "Alınma: ${atLocalTime(state.currentFetchedAtMillis)} · " +
                        "Yaş: ${state.currentAgeMinutes(nowMs)?.let { "$it dk önce" } ?: "bilinmiyor"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            HorizontalDivider()
            Text("Kalan rota · saatlik TAHMİN (en fazla 8 nokta)",
                fontWeight = FontWeight.Bold)
            val points = state.points
                .takeIf { routeId != null && state.routeId == routeId }
                .orEmpty().sortedBy { it.distanceFromStartKm }.take(8)
            if (points.isEmpty()) {
                Text(
                    if (routeId == null) "Rota seçilmedi; yalnız güncel konum modeli gösterilir."
                    else "Geçiş saatli rota tahmini kullanılamıyor."
                )
            } else {
                Text(
                    "Son alınan tahmin: ${atLocalTime(state.fetchedAtMillis)} · " +
                        "${state.ageMinutes(nowMs)?.let { "$it dk önce" } ?: "yaşı bilinmiyor"}",
                    style = MaterialTheme.typography.bodySmall
                )
                points.forEach { point ->
                    val arrival = state.destination == point
                    Text(
                        if (arrival) "Varış" else
                            String.format(Locale.forLanguageTag("tr-TR"),
                                "Rota %.0f km", point.distanceFromStartKm),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Tahmini geçiş: ${atLocalTime(point.forecastEtaEpochMs)} · " +
                            "Model saati: ${validAt(point.observedAt)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(summary(point))
                    point.precipitationProbabilityPercent?.let { probability ->
                        Text("Yağış olasılığı: %$probability",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
            Text(
                "Kaynak: © Open-Meteo (CC BY 4.0). " +
                    "OSRM geçiş süreleri canlı trafik içermez; otomatik yenileme " +
                    "30 dakikadan erken yapılmaz ve Doze nedeniyle gecikebilir.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
