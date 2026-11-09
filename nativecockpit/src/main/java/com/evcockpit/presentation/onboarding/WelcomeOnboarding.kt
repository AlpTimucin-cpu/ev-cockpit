package com.evcockpit.presentation.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.evcockpit.R

private val Night = Color(0xFF081320)
private val Card = Color(0xFF102338)
private val Neon = Color(0xFF39FF88)
private val Muted = Color(0xFFB6C6D2)

private enum class PermissionStep { LOCATION, BLUETOOTH, NOTIFICATIONS }

private fun permitted(context: Context, step: PermissionStep): Boolean =
    when (step) {
        PermissionStep.LOCATION ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        PermissionStep.BLUETOOTH ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        PermissionStep.NOTIFICATIONS ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

@Composable
fun WelcomeOnboarding(onFinish: () -> Unit) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val steps = remember { PermissionStep.entries }

    val locationRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            permissionDenied = false
            page++
        } else permissionDenied = true
    }
    val bluetoothRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) { permissionDenied = false; page++ }
        else permissionDenied = true
    }
    val notificationRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) { permissionDenied = false; page++ }
        else permissionDenied = true
    }

    val current = steps.getOrNull(page)
    val title = when (current) {
        PermissionStep.LOCATION -> R.string.ux_location_title
        PermissionStep.BLUETOOTH -> R.string.ux_bluetooth_title
        PermissionStep.NOTIFICATIONS -> R.string.ux_notification_title
        null -> R.string.ux_ready_title
    }
    val detail = when (current) {
        PermissionStep.LOCATION -> R.string.ux_location_reason
        PermissionStep.BLUETOOTH -> R.string.ux_bluetooth_reason
        PermissionStep.NOTIFICATIONS -> R.string.ux_notification_reason
        null -> R.string.ux_ready_reason
    }
    val stepIcon = when (current) {
        PermissionStep.LOCATION -> Icons.Default.LocationOn
        PermissionStep.BLUETOOTH -> Icons.Default.Bluetooth
        PermissionStep.NOTIFICATIONS -> Icons.Default.Notifications
        null -> Icons.Default.LocationOn
    }
    Column(
        Modifier.fillMaxSize().background(Night).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Icon(painterResource(R.drawable.ic_launcher_foreground), null,
            modifier = Modifier.size(100.dp), tint = Color.Unspecified)
        Text(stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge, color = Color.White)
        Text(stringResource(R.string.ux_welcome_description), color = Muted,
            style = MaterialTheme.typography.bodyMedium)
        CircularProgressIndicator(
            modifier = Modifier.size(36.dp), color = Neon,
            strokeWidth = 3.dp
        )
        LinearProgressIndicator(
            progress = { (page.coerceIn(0, steps.size).toFloat() / steps.size) },
            modifier = Modifier.fillMaxWidth(), color = Neon,
            trackColor = Card
        )
        Surface(
            color = Card, shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(stepIcon, null, tint = Neon, modifier = Modifier.size(34.dp))
                Text(stringResource(title), color = Color.White,
                    style = MaterialTheme.typography.titleLarge)
                Text(stringResource(detail), color = Muted)
                if (permissionDenied) {
                    Text(stringResource(R.string.ux_permission_denied), color = Neon,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (current != null && permitted(context, current)) {
                    Text(stringResource(R.string.ux_already_granted),
                        color = Neon, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (current == null) {
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ux_open_cockpit))
            }
        } else {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    permissionDenied = false
                    if (permitted(context, current)) {
                        page++
                    } else when (current) {
                        PermissionStep.LOCATION -> locationRequest.launch(arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ))
                        PermissionStep.BLUETOOTH ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                bluetoothRequest.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            } else page++
                        PermissionStep.NOTIFICATIONS ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else page++
                    }
                }
            ) {
                Text(stringResource(
                    if (permitted(context, current)) R.string.ux_continue
                    else R.string.ux_allow_permission
                ))
            }
            OutlinedButton(
                onClick = { permissionDenied = false; page++ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.ux_skip_permission))
            }
        }
        TextButton(onClick = onFinish) {
            Text(stringResource(R.string.ux_finish_later), color = Muted)
        }
    }
}
