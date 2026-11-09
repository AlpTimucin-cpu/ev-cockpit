package com.evcockpit.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.evcockpit.domain.model.ZipId4PureReference
import com.evcockpit.domain.usecase.ZipPrototypeLabPolicy
import java.util.Locale

/**
 * Strictly local user research panel. All fields are Compose-memory-only,
 * never persisted. No vehicle repository, GPS, WebView, or media calls.
 */
@Composable
internal fun ZipPrototypeLabPanel() {
    var acknowledged by remember { mutableStateOf(false) }
    var socText by remember { mutableStateOf("80") }
    var consumptionText by remember { mutableStateOf("18.5") }
    var typedUdsPayload by remember { mutableStateOf("62028C007D") }
    var typedElmCommand by remember { mutableStateOf("22028C") }
    var speedForDemo by remember { mutableStateOf(0f) }
    var passengerExample by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Text("ZIP deney laboratuvarı · YALNIZCA SİMÜLASYON",
            style = MaterialTheme.typography.titleMedium)
        Text(
            "Bu alan, eski prototipteki riskli hesapları karşılaştırmak içindir. " +
                "Araca bağlanmaz; gerçek OBD/GPS değerlerini değiştirmez. " +
                "SoC, hız ve yolcu örnekleri bellekte kalır. " +
                "BMS/menzil değerleri doğrulanmış değildir.",
            color = Color(0xFFFFCA80),
            style = MaterialTheme.typography.bodySmall
        )
        Row(Modifier.fillMaxWidth()) {
            Checkbox(
                checked = acknowledged,
                onCheckedChange = {
                    acknowledged = it
                    if (!it) passengerExample = false
                }
            )
            Text(
                "Bu ekranın gerçek araç ölçümü olmadığını anlıyorum.",
                modifier = Modifier.padding(top = 11.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (acknowledged) {
            HorizontalDivider()
            Text("Eski ZIP'in varsayılan %80 SoC modeli (gerçek batarya değil)")
            OutlinedTextField(
                value = socText,
                onValueChange = { socText = it.take(8) },
                label = { Text("Yalnızca örnek başlangıç SoC (%)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = consumptionText,
                onValueChange = { consumptionText = it.take(8) },
                label = { Text("Örnek tüketim (kWh/100 km)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            val soc = socText.replace(',', '.').toDoubleOrNull()
            val consumption = consumptionText.replace(',', '.').toDoubleOrNull()
            val km = if (soc == null || consumption == null) null else
                ZipPrototypeLabPolicy.demoRangeKm(
                    soc, consumption, ZipId4PureReference.CANDIDATE_NET_BATTERY_KWH
                )
            Text(
                km?.let {
                    String.format(Locale.US, "ZIP DEMO / TAHMİN: %.0f km", it)
                } ?: "ZIP DEMO hesaplanamadı · sayı aralıklarını denetle",
                color = Color(0xFFFFCA80)
            )
            HorizontalDivider()
            Text("Doğrulanmamış 028C aday bayt ölçeği (çevrimdışı)")
            OutlinedTextField(
                value = typedUdsPayload,
                onValueChange = { typedUdsPayload = it.take(64) },
                label = { Text("Örnek UDS payload HEX (62 02 8C ... )") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            val candidate = ZipPrototypeLabPolicy
                .unverified028cFromTypedPayload(typedUdsPayload)
            Text(
                candidate?.let {
                    String.format(Locale.US, "DOĞRULANMAMIŞ 028C ADAYI: %.1f %%", it)
                } ?: "028C adayı hesaplanamadı · ham veri doğrulanmadı",
                color = Color(0xFFFFCA80),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Eski ZIP, ikinci veri baytını 2,5'e bölüyor. " +
                    "Bu dönüşüm ID.4 MEB üzerinde doğrulanmadı. " +
                    "Araç ekranı SoC'sine, rota enerjisine veya SoH'a yazılmaz.",
                style = MaterialTheme.typography.bodySmall
            )
            HorizontalDivider()
            Text("Eski komut filtresinin güvenlik açığı (komut GÖNDERİLMEZ)")
            OutlinedTextField(
                value = typedElmCommand,
                onValueChange = { typedElmCommand = it.take(60) },
                label = { Text("Metin/ELM komut örneği") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                if (ZipPrototypeLabPolicy.prototypeWouldAcceptUnsafeCommand(
                        typedElmCommand
                    )) "ZIP filtresi ilk iki karaktere göre kabul ederdi · GÜVENSİZ"
                else "ZIP filtresi metni reddederdi",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFCA80)
            )
            Text(
                "Gerçek bağlantıda FAZ 9'un kesin allowlist'i ve read-only " +
                    "sorgu güvenliği geçerlidir; bu kutu bir terminal değildir.",
                style = MaterialTheme.typography.bodySmall
            )
            HorizontalDivider()
            Text("Eski 'Yolcu modu' davranışı: yalnızca senaryo karşılaştırması")
            Text(
                "Test hızı: ${speedForDemo.toInt()} km/sa · CANLI GPS/OBD DEĞİL",
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = speedForDemo,
                onValueChange = { speedForDemo = it },
                valueRange = 0f..150f
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(
                    checked = passengerExample,
                    onCheckedChange = { passengerExample = it }
                )
                Text("Prototipte yolcu anahtarı örneği",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
            val prototypeWouldPlay = ZipPrototypeLabPolicy.prototypeWouldPermitVideo(
                speedForDemo.toDouble(), passengerExample
            )
            Text(
                if (prototypeWouldPlay)
                    "Eski ZIP bu senaryoda video açardı. Gerçek video kilidi AÇILMADI."
                else "Eski ZIP bu senaryoda videoyu kilitlerdi.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFCA80)
            )
            Text(
                "Hareket hâlinde sürücünün video izlemesi tehlikelidir. " +
                    "EV Cockpit'in PhoneVideoSafetyPolicy ve Android Auto sınırları " +
                    "değiştirilmedi; bu simülasyon oynatıcı başlatamaz.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    socText = "80"
                    consumptionText = "18.5"
                    typedUdsPayload = "62028C007D"
                    typedElmCommand = "22028C"
                    speedForDemo = 0f
                    passengerExample = false
                }
            ) { Text("Örnekleri sıfırla") }
        }
    }
}
