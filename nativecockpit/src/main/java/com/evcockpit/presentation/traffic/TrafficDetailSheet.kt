package com.evcockpit.presentation.traffic

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
import com.evcockpit.data.traffic.TomTomTrafficState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Phone-only detail of provider-returned incidents near the verified route. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrafficDetailSheet(
    state: TomTomTrafficState,
    routeId: String?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val now = System.currentTimeMillis()
    val fresh = state.freshFor(routeId, now)
    val time = DateTimeFormatter.ofPattern(
        "dd.MM.yyyy HH:mm", Locale.forLanguageTag("tr-TR")
    )
    fun stamp(value: Long?): String = if (value == null || value <= 0L)
        "kullanılamıyor" else runCatching {
        Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(time)
    }.getOrDefault("kullanılamıyor")
    val names = mapOf(
        "accident" to "Kaza", "roadWorks" to "Yol çalışması",
        "roadClosed" to "Yol kapalı", "laneClosed" to "Şerit kapalı",
        "jam" to "Yoğunluk", "fog" to "Sis", "rain" to "Yağmur",
        "ice" to "Buzlanma", "wind" to "Kuvvetli rüzgâr",
        "dangerousConditions" to "Tehlikeli koşullar",
        "flooding" to "Su baskını",
        "brokenDownVehicle" to "Arızalı araç"
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 610.dp)
            .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("TomTom Traffic · rota yakını", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onDismiss) { Text("Kapat") }
            }
            Text(state.label(routeId, now), fontWeight = FontWeight.SemiBold)
            Text(
                "TomTom sunucu yanıtı: ${stamp(if (fresh) state.serverAtMillis else null)}. " +
                    "Bu tarih trafik sensörü/modelinin ölçüm saati değildir.",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Kullanım: © TomTom. Rota ve mevcut konum, yalnız Ayarlar'da " +
                "izin verdiğinizde TomTom'a iletilir.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = onRefresh) { Text("Elle yenile (en erken 60 saniye)") }
            if (!fresh) {
                Text(
                    if (state.loading) "TomTom sorgusu deneniyor…"
                    else "Trafik: kullanılamıyor. Ayarlar'da anahtar, açık izin, " +
                        "hassas GPS ve gerçek OSRM rotasını doğrulayın. " +
                        "Kota veya bağlantı sorunu varsa Debug Tanılama'ya bakın."
                )
            } else {
                Text("Trafik akışı: ${state.flowTiles.size} doğrulanmış raster karo; " +
                    "sadece örneklenen rota kesitleri.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Yakın rotada ${state.incidents.size} TomTom olayı. " +
                    "Yakın olması aynı şerit/yön veya etkilenme kanıtı değildir.",
                    style = MaterialTheme.typography.bodySmall)
                state.incidents.take(40).forEach { incident ->
                    HorizontalDivider()
                    Text(names[incident.category] ?: "Trafik olayı",
                        fontWeight = FontWeight.Bold)
                    Text(incident.description.ifBlank { "Açıklama mevcut değil." })
                    Text("Olayın son bildirim zamanı: ${stamp(incident.reportedAtMillis)}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
            Text("Trafikli ETA: kullanılamıyor (ayrı Orbis Routing v3 rota " +
                "eşleşmesi doğrulanmadı). Hız sınırı: kullanılamıyor. " +
                "OSRM ETA: trafiksiz tahmin.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
