package com.evcockpit.presentation.route

import androidx.compose.ui.res.stringResource
import com.evcockpit.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.evcockpit.domain.model.RoutePlace

/** Route and charging planner: one IME inset, one scroll container, fixed close button.
 * State and callback contracts remain unchanged.
 */
@Composable
fun RoutePlannerDialog(
    state: RoutePlannerUiState,
    onDismiss: () -> Unit,
    onOriginChange: (String) -> Unit,
    onDestinationChange: (String) -> Unit,
    onOriginSearch: () -> Unit,
    onDestinationSearch: () -> Unit,
    onOriginSelected: (RoutePlace) -> Unit,
    onDestinationSelected: (RoutePlace) -> Unit,
    onGps: () -> Unit,
    onPlan: () -> Unit,
    onChooseAlternative: (Int) -> Unit,
    onClear: () -> Unit,
    onOpenRoutingSettings: () -> Unit,
    measuredSocPercent: Double? = null
) {
    val scrollState = rememberScrollState()
    val muted = Color(0xFFB4BEC4)
    val accent = Color(0xFF67AFFF)
    val success = Color(0xFF78DF9D)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedPlaceholderColor = muted,
        unfocusedPlaceholderColor = muted,
        focusedBorderColor = accent,
        unfocusedBorderColor = Color(0xFF658194),
        cursorColor = accent
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // Full-window scrim; no IME inset on this outer container.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .statusBarsPadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .fillMaxHeight(0.82f),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = Color(0xFF111315),
                    tonalElevation = 4.dp,
                    shadowElevation = 18.dp
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // This header is outside the scroll area, so X never moves.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 18.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Hedefler",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White
                            )
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close dialog",
                                    tint = Color.White
                                )
                            }
                        }
                        HorizontalDivider(color = Color(0xFF353B40))

                        // Every form field, suggestion, alternative and action can scroll.
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .imePadding()
                                .verticalScroll(scrollState)
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "Başlangıç ve varış için gerçek OSRM rotası hesaplanır. " +
                                    "Batarya şarjı yalnızca doğrulanmış OBD ölçümünden okunur.",
                                style = MaterialTheme.typography.bodySmall,
                                color = muted
                            )

                            // Manual heading replaces the TextField's floating label.
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = stringResource(R.string.native_from),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White
                                )
                                OutlinedTextField(
                                    value = state.originText,
                                    onValueChange = onOriginChange,
                                    placeholder = { Text(stringResource(R.string.native_origin_address_or_city)) },
                                    singleLine = true,
                                    colors = fieldColors,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedButton(
                                    onClick = onOriginSearch,
                                    enabled = state.originText.trim().length >= 3 &&
                                        !state.searchingOrigin,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                ) { Text("Başlangıç adresini ara") }
                                TextButton(
                                    onClick = onGps,
                                    enabled = !state.locatingGps,
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text(
                                        if (state.locatingGps) stringResource(R.string.native_getting_gps_location)
                                        else stringResource(R.string.native_start_from_my_current_gps_location)
                                    )
                                }
                                if (state.locatingGps) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                                if (state.startLocation != null) {
                                    Text(stringResource(R.string.native_origin_selected), color = success)
                                }
                                if (state.searchingOrigin) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                                Suggestions(state.originMatches, onOriginSelected)
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = stringResource(R.string.native_to),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White
                                )
                                OutlinedTextField(
                                    value = state.destinationText,
                                    onValueChange = onDestinationChange,
                                    placeholder = { Text(stringResource(R.string.native_destination_address_or_city)) },
                                    singleLine = true,
                                    colors = fieldColors,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedButton(
                                    onClick = onDestinationSearch,
                                    enabled = state.destinationText.trim().length >= 3 &&
                                        !state.searchingDestination,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                ) { Text("Varış adresini ara") }
                                if (state.destinationLocation != null) {
                                    Text(stringResource(R.string.native_destination_selected), color = success)
                                }
                                if (state.searchingDestination) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                                Suggestions(state.destinationMatches, onDestinationSelected)
                            }

                            // Vehicle card follows the supplied ABRP reference.
                            // It is read-only: no fake 100% departure slider.
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFF303336),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(
                                    Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "Volkswagen ID.4 Pure",
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        "Başlangıç batarya seviyesi · CANLI OBD",
                                        color = muted,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    val measured = measuredSocPercent
                                        ?.takeIf { it.isFinite() && it in 0.0..100.0 }
                                    if (measured != null) {
                                        Text(
                                            String.format(
                                                java.util.Locale.forLanguageTag("tr-TR"),
                                                "%%%.1f", measured
                                            ),
                                            color = success,
                                            style = MaterialTheme.typography.titleLarge
                                        )
                                        LinearProgressIndicator(
                                            progress = { (measured / 100.0).toFloat() },
                                            modifier = Modifier.fillMaxWidth(),
                                            color = success
                                        )
                                    } else {
                                        Text(
                                            "OBD bağlantısı ve doğrulanmış BMS SoC bekleniyor",
                                            color = muted,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }

                            state.error?.let { error ->
                                Text(
                                    text = error,
                                    color = Color(0xFFFFB17C),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (error.contains("Rota servisi", ignoreCase = true) ||
                                    error.contains("OSRM", ignoreCase = true)
                                ) {
                                    OutlinedButton(
                                        onClick = onOpenRoutingSettings,
                                        modifier = Modifier.fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                    ) {
                                        Text("Rota hizmeti ayarlarını aç")
                                    }
                                }
                            }

                            if (state.options.isNotEmpty()) {
                                HorizontalDivider(color = Color(0xFF27465B))
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        text = stringResource(R.string.native_osrm_alternatives) +
                                            state.options.size + " validated)",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color.White
                                    )
                                    state.options.forEachIndexed { index, item ->
                                        val selected = state.selectedIndex == index
                                        OutlinedButton(
                                            onClick = { onChooseAlternative(index) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 52.dp)
                                        ) {
                                            Text(
                                                (if (selected) "✓ " else "") +
                                                    "Route " + (index + 1) + " · " +
                                                    "%.1f km · %d min".format(
                                                        item.distanceKm,
                                                        item.durationMinutesWithoutTraffic
                                                    )
                                            )
                                        }
                                    }
                                    Text(
                                        text = stringResource(R.string.native_estimates_exclude_traffic_arrival_soc_is_not_calculated) +
                                            "without GPS progress.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = muted
                                    )
                                }
                            }

                            HorizontalDivider(color = Color(0xFF27465B))

                            // Keep all actions reachable with an open keyboard.
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (state.planning) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                                Button(
                                    onClick = onPlan,
                                    enabled = state.canCalculate,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 52.dp)
                                ) {
                                    Text(stringResource(R.string.native_calculate_actual_road_route))
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = onClear,
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                    ) { Text(stringResource(R.string.native_clear_route)) }
                                    TextButton(
                                        onClick = onDismiss,
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                    ) { Text(stringResource(R.string.native_back_to_map)) }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
        }
    }
}

@Composable
private fun Suggestions(items: List<RoutePlace>, onSelect: (RoutePlace) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { place ->
            Surface(
                color = Color(0xFF303336),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onSelect(place) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = place.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
            }
        }
    }
}
