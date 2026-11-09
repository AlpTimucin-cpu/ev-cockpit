package com.evcockpit.presentation.obd

import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.evcockpit.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.evcockpit.domain.model.ObdConnectionState
import com.evcockpit.domain.model.ObdPhase
import com.evcockpit.domain.model.MebResponseStatus
import com.evcockpit.domain.usecase.ObdDiagnosticSafetyPolicy
import com.evcockpit.domain.usecase.ZipPrototypeLabPolicy
import java.util.Locale

@Composable
fun ObdSettingsDialog(
    state: ObdConnectionState,
    onRefresh: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onMebDiagnostic: (String) -> Unit,
    onClearDiagnostic: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val manualDiagnosticReady = ObdDiagnosticSafetyPolicy.canRequest(
        state, System.currentTimeMillis()
    )
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.90f).imePadding(),
            shape=MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.native_obd_connection_and_meb_diagnostics),style=MaterialTheme.typography.titleLarge)
                Text("Bluetooth Classic (SPP) OBD adaptörünü Android'de eşleştir. " +
                    "OBD cihazı sokete takılı ve araç açık olmalı. BLE adaptörler ayrı GATT profili gerektirir.",
                    style=MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("1 · Bluetooth ayarlarını aç ve OBD'yi eşleştir") }
                Text("Durum: ${phaseLabel(state.phase)}")
                if (state.phase != ObdPhase.CONNECTED) {
                    Text("OBD Bağlantısı Bekleniyor", style=MaterialTheme.typography.bodyMedium)
                }
                state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                OutlinedButton(onClick=onRefresh, modifier=Modifier.fillMaxWidth()) {
                    Text("2 · Eşleşmiş cihazları yenile / izin iste")
                }
                if (state.devices.isEmpty()) Text(
                    "Eşleşmiş cihaz bulunamadı. OBD adaptörünü Bluetooth ayarlarından " +
                    "eşleştirip tekrar 'Yenile'ye basın."
                )
                state.devices.forEach { device ->
                    OutlinedButton(onClick={onConnect(device.address)},
                        enabled=state.phase != ObdPhase.CONNECTED && state.phase != ObdPhase.CONNECTING,
                        modifier=Modifier.fillMaxWidth()) {
                        Column {
                            Text("3 · Bağlan: ${device.name}")
                            Text(device.address, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (state.phase == ObdPhase.CONNECTING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Bluetooth soketi ve ELM327 el sıkışması deneniyor. " +
                        "Diğer OBD uygulamalarının bağlantısını kapatın.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (state.phase == ObdPhase.CONNECTED) {
                    Text(if (state.lastPollSucceeded)
                        "ELM327 bağlı · araçtan 010D hız yanıtı alındı"
                        else "ELM327 bağlı · 010D hız yanıtı henüz yok / ECU desteklemiyor")
                    HorizontalDivider()
                    Text(stringResource(R.string.native_experimental_meb_ecu_readings),style=MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.native_test_only_while_parked_and_in_your_own_vehicle_queries_use_read_only_u) +
                        "MEB adresleri ve ölçekleri bu araç üzerinde doğrulanmadı. Ham cevaplar SoC/SoH değildir. " +
                        "Önce aracı güvenli biçimde P konumuna alın; ölçülen 0 km/sa, P konumunun kanıtı değildir.",
                        style=MaterialTheme.typography.bodySmall)
                    if (!manualDiagnosticReady) {
                        Text(
                            "Ham teşhis sorgusu kilitli: son 3 saniyede alınmış gerçek " +
                                "SAE PID 010D hız ölçümü 0–0,5 km/sa olmalıdır.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedButton(onClick={ { onMebDiagnostic("22028C") } },
                        enabled=manualDiagnosticReady,modifier=Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.native_read_bms_state_of_charge_22028c))
                    }
                    OutlinedButton(onClick={ { onMebDiagnostic("221E0E") } },
                        enabled=manualDiagnosticReady,modifier=Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.native_maximum_battery_temperature_221e0e))
                    }
                    OutlinedButton(onClick={ { onMebDiagnostic("221E3D") } },
                        enabled=manualDiagnosticReady,modifier=Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.native_battery_current_raw_response_only_221e3d))
                    }
                    if (state.diagnosticBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.diagnosticResult?.let { item ->
                        var zipHypothesisVisible by remember(item.request, item.receivedAtMillis) {
                            mutableStateOf(false)
                        }
                        Text("Son sorgu: ${item.request} · ${diagnosticLabel(item.status)}",
                            style=MaterialTheme.typography.bodyMedium)
                        if (item.status == MebResponseStatus.VALID_RAW) {
                            item.candidateBmsSocPercent?.let { percent ->
                                Text(String.format(Locale.forLanguageTag("en-US"),
                                    "Candidate BMS SoC: %.1f %% (compare with the vehicle display)",percent))
                            }
                            item.candidateMaxBatteryTempC?.let { temp ->
                                Text(String.format(Locale.forLanguageTag("en-US"),
                                    "Candidate maximum battery temperature: %.2f °C",temp))
                            }
                            item.payloadHex?.let { payload ->
                                Text("Raw payload: $payload",
                                    style=MaterialTheme.typography.bodySmall)
                                // Experimental interpretation is opt-in and remains
                                // a text-only hypothesis, NEVER vehicle telemetry.
                                if (item.request == "22028C" &&
                                    item.responder == "17FE007B"
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            zipHypothesisVisible = !zipHypothesisVisible
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            if (zipHypothesisVisible)
                                                "ZIP 028C hipotezini gizle"
                                            else "ZIP 028C aday hesabını göster · deneysel"
                                        )
                                    }
                                    if (zipHypothesisVisible) {
                                        val candidate = ZipPrototypeLabPolicy
                                            .unverified028cFromTypedPayload(payload)
                                        Text(
                                            candidate?.let {
                                                String.format(Locale.US,
                                                    "ZIP ADAYI: %.1f %% · DOĞRULANMAMIŞ", it)
                                            } ?: "ZIP hipotezi hesaplanamadı; veri reddedildi.",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            "Aday 028C formülü bu ID.4 için doğrulanmadı. " +
                                                "SoC, SoH, rota/şarj planı ve gösterge değerleri " +
                                                "bu sonuçtan güncellenmez.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(stringResource(R.string.native_no_validated_ecu_response_received_data_was_not_used),
                                style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick=onClearDiagnostic,modifier=Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.native_clear_diagnostic_result))
                    }
                    Button(onClick=onDisconnect,modifier=Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.native_disconnect_obd))
                    }
                }
                Text(stringResource(R.string.native_bms_soc_diagnostic_candidate_dashboard_soc_and_soh_are_different_quant) +
                     "Technical battery readings are shown separately under Hesaplar; they are not official SoH results.",
                     style=MaterialTheme.typography.bodySmall)
                TextButton(onClick=onDismiss,modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.native_back_to_map)) }
            }
        }
    }
}

private fun phaseLabel(phase: ObdPhase): String = when (phase) {
    ObdPhase.DISCONNECTED -> "Bağlı değil"
    ObdPhase.CONNECTING -> "Bluetooth / ELM327 bağlantısı kuruluyor"
    ObdPhase.CONNECTED -> "ELM327 bağlandı"
    ObdPhase.ERROR -> "Bağlantı hatası"
}

private fun diagnosticLabel(status: MebResponseStatus): String = when (status) {
    MebResponseStatus.VALID_RAW -> "Valid raw response"
    MebResponseStatus.NO_DATA -> "No data"
    MebResponseStatus.NEGATIVE -> "ECU rejected request"
    MebResponseStatus.WRONG_ECU -> "Unexpected ECU"
    MebResponseStatus.WRONG_IDENTIFIER -> "Unexpected data identifier"
    MebResponseStatus.INCOMPLETE -> "Incomplete response"
    MebResponseStatus.MALFORMED -> "Invalid response"
    MebResponseStatus.ADAPTER_ERROR -> "Adapter error"
}
