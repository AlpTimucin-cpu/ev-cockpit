package com.evcockpit.presentation.cockpit

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.evcockpit.domain.model.EngineeringMetrics
import com.evcockpit.domain.model.EngineeringSnapshot
import com.evcockpit.domain.model.ObdPhase
import com.evcockpit.domain.model.TimedValue
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Separate live engineering page. No fabricated values or certification.
 * View only: sampling is owned by the singleton OBD repository/service.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TechnicalAnalysisSheet(
    snapshot: EngineeringSnapshot,
    metrics: EngineeringMetrics,
    obdPhase: ObdPhase,
    onDismiss: () -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(2_000)
        }
    }
    val hasFreshSession = connectedCheck(obdPhase, snapshot, now)
    val visibleSnapshot = if (hasFreshSession) snapshot else EngineeringSnapshot()
    val result = if (hasFreshSession) metrics else EngineeringMetrics()
    val connected = obdPhase == ObdPhase.CONNECTED
    val recent = snapshot.lastValidResponseAtMs?.let { now - it in 0..120_000L } == true

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Navy
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(.94f)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Canlı Teknik Hesaplamalar", style = MaterialTheme.typography.headlineSmall)
            Text(
                when {
                    !connected -> "OBD bağlı değil · bağlanınca salt-okunur ölçümler otomatik başlar."
                    !recent -> "OBD bağlı · henüz geçerli MEB ölçümü gelmedi veya veriler eski."
                    else -> "OBD bağlı · MEB aday ölçümleri alınıyor (ölçekler teyitsiz)."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (connected) {
                Text("Başarısız/eksik ardışık okuma: ${visibleSnapshot.unsuccessfulReads}",
                    style = MaterialTheme.typography.labelSmall)
            }
            Text(
                "Deneysel MEB DID çözümlemeleri doğrulanmış BMS SoC/SoH değildir. " +
                    "Bu sayfadaki hesaplar yalnızca teknik inceleme içindir.",
                style = MaterialTheme.typography.bodySmall
            )
            HorizontalDivider()
            Text("Anlık batarya", style = MaterialTheme.typography.titleLarge)
            Metric("SoC · deneysel DID", visibleSnapshot.socPercent.valueIfFresh(now), "%", 1)
            Metric("Paket gerilimi", visibleSnapshot.packVoltageV.valueIfFresh(now), "V", 2)
            Metric("Batarya akımı (+ şarj / − tüketim)", visibleSnapshot.packCurrentA.valueIfFresh(now), "A", 2)
            Metric("Gerilim × akım · deneysel", result.batteryPowerKw, "kW", 2)
            Metric("Batarya C-rate", result.absoluteCrate, "C", 2)
            Metric("OBD araç hızı", visibleSnapshot.speedKmh.valueIfFresh(now), "km/sa", 1)
            Metric("Anlık enerji / 100 km", result.instantaneousConsumptionKwh100Km, "kWh/100 km", 1)
            Metric("Dinamik şarj akım sınırı", visibleSnapshot.chargeCurrentLimitA.valueIfFresh(now), "A", 1)
            Text(
                "Güç = U×I/1000; C-rate = |P|/52. Anlık tüketim yalnızca OBD hızı 20 km/sa üzerindeyse gösterilir. " +
                    "Bu değer yolculuk ortalaması değildir; rejenerasyonda negatif olabilir.",
                style = MaterialTheme.typography.bodySmall
            )
            HorizontalDivider()
            Text("Hücre ve termal yönetim", style = MaterialTheme.typography.titleLarge)
            Metric("En yüksek hücre", visibleSnapshot.cellMaxV.valueIfFresh(now), "V", 4)
            Metric("En düşük hücre", visibleSnapshot.cellMinV.valueIfFresh(now), "V", 4)
            Metric("Hücre farkı", result.batteryCellSpreadMv, "mV", 2)
            Metric("En yüksek sensör", visibleSnapshot.temperatureMaxC.valueIfFresh(now), "°C", 2)
            Metric("En düşük sensör", visibleSnapshot.temperatureMinC.valueIfFresh(now), "°C", 2)
            Metric("Sıcaklık farkı", result.batteryTemperatureSpreadC, "°C", 2)
            Text("Hücre ve sıcaklık farkları eşleştirilmiş, zamanca yakın okumalardan hesaplanır.",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Batarya ömrü ve enerji sayaçları", style = MaterialTheme.typography.titleLarge)
            Metric("BMS kapasite parametresi", visibleSnapshot.capacityParameterKwh.valueIfFresh(now, 900_000L), "kWh", 2)
            Metric("Birikimli şarj enerjisi", visibleSnapshot.accumulatedChargedKwh.valueIfFresh(now, 900_000L), "kWh", 1)
            Metric("Birikimli deşarj enerjisi", visibleSnapshot.accumulatedDischargedKwh.valueIfFresh(now, 900_000L), "kWh", 1)
            Metric("Eşdeğer tam deşarj çevrimi", result.dischargeEquivalentFullCycles, "FEC", 1)
            Text("FEC = birikimli deşarj (kWh) / 52 kWh. Başlangıçtan beri sayaçların sıfırlanmadığı varsayılır.",
                style = MaterialTheme.typography.bodySmall)
            Text("SoH: doğrulanmadı. Kapasite parametresi / 52 oranı resmi SoH değildir; ODIS kapasite testi gerekir.",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Sürüş profiline göre tahmini menzil", style = MaterialTheme.typography.titleLarge)
            Metric("Yaz · 10,5 kWh/100 km", result.estimatedSummerRangeKm, "km", 0)
            Metric("Kış · 18 kWh/100 km", result.estimatedWinterRangeKm, "km", 0)
            Text(
                "Bu iki değer gerçek zamanlı tüketim ölçümü değildir. Senin mevsimsel tüketim ortalamalarından " +
                    "52 kWh fabrika net kapasitesi kullanılarak hesaplanan model tahminidir.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Veriler yalnızca teşhis amaçlıdır; hücre arızası, izolasyon güvenliği, " +
                    "SoH veya servis kararı için tek başına yeterli değildir. " +
                    "Bağlantı kesilince canlı değerler temizlenir.",
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Haritaya dön") }
            Spacer(Modifier.height(22.dp))
        }
    }
}

private fun connectedCheck(phase: ObdPhase, snapshot: EngineeringSnapshot, now: Long): Boolean =
    phase == ObdPhase.CONNECTED &&
        snapshot.lastValidResponseAtMs?.let { now - it in 0L..120_000L } == true

private fun TimedValue<Double>.valueIfFresh(now: Long, ttl: Long = 120_000L): Double? =
    current(now, ttl)

@Composable
private fun Metric(name: String, value: Double?, unit: String, precision: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value?.takeIf { it.isFinite() }?.let {
                String.format(Locale.forLanguageTag("tr-TR"), "%.${precision}f %s", it, unit)
            } ?: "Bekleniyor",
            style = MaterialTheme.typography.titleSmall
        )
    }
}
