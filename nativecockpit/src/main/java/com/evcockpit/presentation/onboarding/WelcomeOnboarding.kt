package com.evcockpit.presentation.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private val Night = Color(0xFF081320)
private val Card = Color(0xFF102338)
private val Neon = Color(0xFF39FF88)
private val Muted = Color(0xFFB6C6D2)
private enum class PermissionStep { LOCATION, BLUETOOTH, NOTIFICATIONS }

/**
 * Phone-only, user-initiated permission wizard. No permission request is made
 * merely by rendering this screen. Skipping is allowed; limits stay visible.
 */
@Composable
fun WelcomeOnboarding(onFinish: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val steps = remember { PermissionStep.entries }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val snapshot = remember(context, revision) { PermissionSnapshot.read(context) }
    val current = steps.getOrNull(page)
    fun advance() {
        denied = false
        page = (page + 1).coerceAtMost(steps.size)
        revision++
    }
    val locationRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        revision++
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) advance()
        else denied = true // Approximate-only is not silently treated as precise.
    }
    val bluetoothRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        revision++
        if (granted) advance() else denied = true
    }
    val notificationRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        revision++
        if (granted) advance() else denied = true
    }
    val ready = when (current) {
        PermissionStep.LOCATION -> snapshot.preciseLocationGranted
        PermissionStep.BLUETOOTH -> snapshot.bluetoothConnectGranted
        PermissionStep.NOTIFICATIONS -> snapshot.notificationPermissionGranted
        null -> true
    }
    val title = when (current) {
        PermissionStep.LOCATION -> "1/3 · Konum izni"
        PermissionStep.BLUETOOTH -> "2/3 · Bluetooth izni"
        PermissionStep.NOTIFICATIONS -> "3/3 · Bildirim izni"
        null -> "İzinlerin mevcut durumu"
    }
    val detail = when (current) {
        PermissionStep.LOCATION ->
            "Rota ve araç işaretçisi için 'Uygulamayı kullanırken' ve 'Hassas' " +
                "seçeneklerini seçin. Yalnız yaklaşık konum yeterli değildir."
        PermissionStep.BLUETOOTH ->
            "Yalnızca sizin eşleştirdiğiniz ELM327 adaptörüne bağlanmak için " +
                "Yakındaki cihazlar erişimine izin verin."
        PermissionStep.NOTIFICATIONS ->
            "Navigasyon/OBD durum bildirimlerinin görünmesi için izin verin. " +
                "Bildirim izni verilmemesi bir foreground servisini tek başına durdurmaz."
        null -> "Eksik izinler varsa uygulamanın hangi özelliklerinin sınırlı " +
            "kalacağını aşağıda görebilirsiniz."
    }

    Column(
        Modifier.fillMaxSize().background(Night)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("EV Cockpit · İzin kurulum sihirbazı",
            style = MaterialTheme.typography.titleLarge, color = Color.White)
        Surface(
            color = Card, shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, color = Neon, style = MaterialTheme.typography.titleMedium)
                Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
                if (current != null) {
                    Text(
                        if (ready) "Bu adım için izin mevcut."
                        else if (denied) "İzin henüz verilmedi; tekrar deneyin veya Ayarlar'ı açın."
                        else "İzin bekleniyor.",
                        color = if (ready) Neon else Muted,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
        Text("Eksik izinler / ne çalışmaz?",
            color = Color.White, style = MaterialTheme.typography.titleSmall)
        val issues = snapshot.missingCapabilities()
        if (issues.isEmpty()) {
            Text("İzin kısıtı görünmüyor. GPS sinyali ve araç bağlantısı ayrıca doğrulanmalıdır.",
                color = Neon, style = MaterialTheme.typography.bodySmall)
        } else {
            issues.forEach { issue ->
                Text("• $issue", color = Muted, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth())
            }
        }
        HorizontalDivider(color = Color(0x3349FF88))
        if (current == null) {
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                Text("Kokpiti aç")
            }
        } else {
            Button(
                onClick = {
                    denied = false
                    if (ready) {
                        advance()
                    } else when (current) {
                        PermissionStep.LOCATION -> locationRequest.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION)
                        )
                        PermissionStep.BLUETOOTH ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                bluetoothRequest.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            else advance()
                        PermissionStep.NOTIFICATIONS ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else advance()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (ready) "Sonraki adım" else "İzin iste") }
            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + context.packageName))
                    runCatching { context.startActivity(intent) }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Uygulama izinlerini Ayarlar'da aç") }
            TextButton(onClick = { advance() }) {
                Text("Bu adımı geç (özellikler sınırlı kalır)", color = Muted)
            }
        }
        Spacer(Modifier.height(2.dp))
        TextButton(onClick = onFinish) {
            Text("Kısıtları kabul et ve kokpiti aç", color = Muted)
        }
    }
}
