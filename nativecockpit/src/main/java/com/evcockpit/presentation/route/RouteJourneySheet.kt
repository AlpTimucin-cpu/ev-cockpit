package com.evcockpit.presentation.route

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evcockpit.domain.model.ChargingStation
import com.evcockpit.domain.model.RouteSnapshot
import com.evcockpit.domain.usecase.AutomaticChargePlan
import com.evcockpit.presentation.charging.RouteChargingUiState
import com.evcockpit.presentation.charging.RouteStopPreference
import java.util.Locale

private val SheetBlack = Color(0xFF111315)
private val SheetCard = Color(0xFF303336)
private val SheetSubtle = Color(0xFFB9C1C4)
private val SheetBlue = Color(0xFF65ACFF)
private val SheetMint = Color(0xFF73DC92)
private val SheetWarn = Color(0xFFFFCA80)

private fun kilometers(km: Double?): String =
    km?.takeIf { it.isFinite() && it >= 0.0 }
        ?.let { String.format(Locale.forLanguageTag("tr-TR"), "%.0f km", it) }
        ?: "Hesaplanamadı"

private fun minutes(value: Double?): String {
    val min = value?.takeIf { it.isFinite() && it >= 0.0 }?.toLong()
        ?: return "Hesaplanamadı"
    return if (min < 60) "$min dakika"
    else "${min / 60} saat ${min % 60} dakika"
}

private fun decimal(value: Double?): String =
    value?.takeIf { it.isFinite() && it >= 0.0 }
        ?.let { String.format(Locale.forLanguageTag("tr-TR"), "%.1f", it) }
        ?: "—"

/**
 * ABRP-style presentation based on the supplied screenshots.
 * All route metrics are sourced from OSRM; battery data must be fresh OBD.
 * Charge durations, occupancy, traffic and TL costs are not invented.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteJourneySheet(
    route: RouteSnapshot,
    planner: RoutePlannerUiState,
    charging: RouteChargingUiState,
    realObdSocPercent: Double?,
    onDismiss: () -> Unit,
    onEditTargets: () -> Unit,
    onSelectAlternative: (Int) -> Unit,
    onSetReserve: (Float) -> Unit,
    onSetPreference: (RouteStopPreference) -> Unit,
    onOpenStation: (ChargingStation) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var section by remember { mutableIntStateOf(0) }
    val plan = charging.plan
    val routeMatches = charging.routeId == route.routeId
    // Keep slider movement local; persist once, after the gesture finishes.
    var reserveDraft by remember { mutableFloatStateOf(
        charging.arrivalReservePercent.coerceIn(0f, 50f)
    ) }
    LaunchedEffect(charging.arrivalReservePercent) {
        reserveDraft = charging.arrivalReservePercent.coerceIn(0f, 50f)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetBlack,
        contentColor = Color.White,
        dragHandle = {
            Box(
                Modifier.padding(vertical = 9.dp)
                    .size(width = 40.dp, height = 5.dp)
                    .background(Color.Gray, RoundedCornerShape(9.dp))
            )
        }
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    route.destination?.takeIf { it.isNotBlank() }
                        ?: planner.destinationText.ifBlank { "Rota planı" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) { Text("Harita", color = SheetBlue) }
            }

            // One destination / settings / itinerary navigation, not nested dialogs.
            TabRow(
                selectedTabIndex = section,
                containerColor = SheetBlack,
                contentColor = SheetBlue
            ) {
                listOf("Plan", "Seçenekler", "Duraklar").forEachIndexed { i, name ->
                    Tab(
                        selected = i == section,
                        onClick = { section = i },
                        text = { Text(name, fontSize = 12.sp) }
                    )
                }
            }

            when (section) {
                0 -> {
                    JourneyCard {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    minutes(route.osrmDurationSeconds?.div(60.0)),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.headlineSmall
                                )
                                Text("OSRM tahmini · canlı trafik içermez",
                                    color = SheetSubtle,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Text(kilometers(route.totalKm),
                                color = SheetMint,
                                fontWeight = FontWeight.Bold)
                        }
                        HorizontalDivider(color = Color.DarkGray)
                        val stopCount = (plan as? AutomaticChargePlan.Estimate)?.stops?.size
                        Text(
                            if (routeMatches && stopCount != null)
                                "$stopCount önerilen DC durak · yalnızca tahmin"
                            else "Şarj durakları: ölçüm / kalibrasyon bekleniyor",
                            color = SheetSubtle
                        )
                        Text("Maliyet, gerçek istasyon doluluğu ve şarj süresi doğrulanmadı.",
                            color = SheetSubtle,
                            style = MaterialTheme.typography.labelSmall)
                    }

                    JourneyCard {
                        Text("HEDEFLER", color = SheetBlue,
                            style = MaterialTheme.typography.labelMedium)
                        Text("Başlangıç: " + planner.originText.ifBlank { "Güncel GPS" })
                        Text("Varış: " + planner.destinationText.ifBlank {
                            route.destination ?: "Varış seçilmedi"
                        })
                        Text(
                            "Araç SoC: " + (
                                realObdSocPercent
                                    ?.takeIf { it.isFinite() && it in 0.0..100.0 }
                                    ?.let { "${decimal(it)}% · gerçek OBD" }
                                    ?: "OBD doğrulaması bekleniyor"
                            ),
                            color = SheetMint
                        )
                        TextButton(onClick = onEditTargets) {
                            Text("Hedefleri düzenle", color = SheetBlue)
                        }
                    }

                    if (planner.options.size > 1) {
                        Text("ROTA ALTERNATİFLERİ", fontWeight = FontWeight.SemiBold)
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            planner.options.forEachIndexed { index, option ->
                                val selected = planner.selectedIndex == index
                                Surface(
                                    modifier = Modifier.widthIn(min = 150.dp)
                                        .clickable { onSelectAlternative(index) },
                                    color = if (selected) Color(0xFF244A78) else SheetCard,
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(
                                            if (selected) "✓ Rota ${index + 1}"
                                            else "Rota ${index + 1}",
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(kilometers(option.distanceKm), color = SheetSubtle)
                                        Text(
                                            minutes(option.durationMinutesWithoutTraffic.toDouble()),
                                            color = SheetSubtle
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Button(
                        onClick = { section = 2 },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SheetBlue)
                    ) { Text("Şarj duraklarını incele", color = Color.Black) }
                }

                1 -> {
                    Text("Şarj duraklamaları", fontWeight = FontWeight.SemiBold)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        RouteStopPreference.entries.forEach { preference ->
                            FilterChip(
                                selected = preference == charging.stopPreference,
                                onClick = { onSetPreference(preference) },
                                label = { Text(preference.titleTr, fontSize = 12.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Text(
                        "Bu seçimler sırasıyla %80, %90 ve %100 model şarj hedefi " +
                            "kullanır; en az durak veya en kısa süreyi garanti etmez.",
                        color = SheetSubtle, style = MaterialTheme.typography.bodySmall
                    )
                    JourneyCard {
                        Text("Varışta batarya rezervi", fontWeight = FontWeight.SemiBold)
                        Text("${reserveDraft.toInt()}%", fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineSmall)
                        Slider(
                            value = reserveDraft,
                            onValueChange = { reserveDraft = it },
                            onValueChangeFinished = { onSetReserve(reserveDraft) },
                            valueRange = 0f..50f,
                            steps = 49
                        )
                        Text(
                            "Bu değer kullanıcı tercihidir, araçtan ölçülen SoC değildir.",
                            color = SheetSubtle,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    JourneyCard {
                        Text("Rota tercihleri", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Ücretli yol, feribot ve yol kaçınma kuralları için " +
                                "doğrulanmış yönlendirme desteği henüz yok.",
                            color = SheetWarn,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "OSRM mesafe/süre bilgisi canlı trafik değildir. " +
                                "Şarj maliyeti ve istasyon müsaitliği onaylanmamıştır.",
                            color = SheetSubtle,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                else -> {
                    JourneyCard {
                        Text("BAŞLANGIÇ", color = SheetMint,
                            style = MaterialTheme.typography.labelMedium)
                        Text(planner.originText.ifBlank { "Güncel GPS konumu" },
                            fontWeight = FontWeight.SemiBold)
                        Text(
                            realObdSocPercent
                                ?.takeIf { it.isFinite() && it in 0.0..100.0 }
                                ?.let { "${decimal(it)}% · gerçek OBD ölçümü" }
                                ?: "Başlangıç SoC: OBD bekleniyor",
                            color = SheetSubtle
                        )
                    }
                    if (routeMatches && charging.roadDetours.routeId == route.routeId) {
                        val roads = charging.roadDetours
                        JourneyCard {
                            Text("KARAYOLU SAPMASI · OSRM", color = SheetBlue,
                                style = MaterialTheme.typography.labelMedium)
                            Text(
                                when {
                                    roads.loading -> "DC/CCS durakları gerçek karayolu üzerinde kontrol ediliyor…"
                                    roads.checked.isNotEmpty() ->
                                        "${roads.checked.size} istasyon için sapma hesabı alındı"
                                    else -> "Doğrulanmış karayolu sapması henüz yok"
                                },
                                color = SheetSubtle
                            )
                            roads.message?.let {
                                Text(it, color = SheetWarn,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "Kuş uçuşu koridor yalnızca aday seçer. OSRM ile gidiş-" +
                                    "dönüş sapması doğrulanmayan istasyon için 0 km varsayılmaz.",
                                color = SheetSubtle,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        roads.checked.forEach { checked ->
                            val station = roads.candidates.firstOrNull {
                                it.id == checked.stationId
                            }
                            if (station != null) JourneyCard {
                                Text(station.name, fontWeight = FontWeight.SemiBold)
                                Text("Ek karayolu: ${decimal(checked.additionalKm)} km" +
                                    " · Başlangıçtan istasyona: " +
                                    kilometers(checked.roadDistanceToStationKm),
                                    color = SheetMint)
                                checked.additionalMinutes?.let {
                                    Text("Ek OSRM süre tahmini: ${decimal(it)} dk · trafik içermez",
                                        color = SheetSubtle,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { onOpenStation(station) }) {
                                    Text("Detaylar ve rotaya ekle", color = SheetBlue)
                                }
                            }
                        }
                    }
                    if (routeMatches && plan is AutomaticChargePlan.Estimate) {
                        if (plan.stops.isEmpty()) Text(
                            "Model bu rota için şarj durağı önermiyor.",
                            color = SheetMint
                        )
                        plan.stops.forEachIndexed { i, stop ->
                            JourneyCard {
                                Text("⚡ ŞARJ DURAĞI ${i + 1}",
                                    color = SheetMint,
                                    style = MaterialTheme.typography.labelMedium)
                                Text(stop.station.name, fontWeight = FontWeight.Bold)
                                Text(stop.station.operator + " · " + stop.station.address,
                                    color = SheetSubtle,
                                    style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "Rota üzerinde yaklaşık ${kilometers(stop.distanceFromStartKm)}",
                                    color = SheetSubtle
                                )
                                Text(
                                    "Varış SoC: ${decimal(stop.estimatedArrivalSocPercent)}% (model)" +
                                        "   ·   Önerilen enerji: ${decimal(stop.suggestedEnergyKwh)} kWh",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                stop.verifiedAdditionalRoadKm?.let {
                                    Text("OSRM kontrolü · ek yol: ${decimal(it)} km",
                                        color = SheetMint,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                stop.verifiedPowerKw?.let {
                                    Text("EPDK belirtilen güç: ${decimal(it)} kW",
                                        color = SheetSubtle,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { onOpenStation(stop.station) }) {
                                    Text("İstasyon detayları", color = SheetBlue)
                                }
                            }
                        }
                    } else {
                        JourneyCard {
                            Text("Şarj planı henüz hesaplanamıyor", color = SheetWarn)
                            Text(
                                (plan as? AutomaticChargePlan.Unavailable)?.reason
                                    ?: "Rota, gerçek BMS SoC veya kalibrasyon bekleniyor.",
                                color = SheetSubtle
                            )
                        }
                    }
                    JourneyCard {
                        Text("VARIŞ", color = SheetMint,
                            style = MaterialTheme.typography.labelMedium)
                        Text(
                            planner.destinationText.ifBlank { route.destination ?: "Hedef" },
                            fontWeight = FontWeight.Bold
                        )
                        Text(kilometers(route.totalKm) + " · " +
                            minutes(route.osrmDurationSeconds?.div(60.0)))
                        Text(
                            route.estimatedArrivalSocPercent
                                ?.takeIf { it.isFinite() && it in 0.0..100.0 }
                                ?.let { "Tahmini varış SoC: ${decimal(it)}% · model" }
                                ?: "Varış SoC: doğrulanmış tahmin yok",
                            color = SheetSubtle
                        )
                    }
                    Text(
                        "İstasyon sapmaları OSRM güzergâhı üzerinden kontrol edilir. " +
                            "Çoklu durak rotası, gerçek doluluk, fiyat, erişim ve şarj süresi garanti edilmez.",
                        style = MaterialTheme.typography.labelSmall, color = SheetSubtle
                    )
                }
            }
            Spacer(Modifier.height(15.dp))
        }
    }
}

@Composable
private fun JourneyCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SheetCard,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            content = content
        )
    }
}
