package com.evcockpit.presentation.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Permission grants are current observations, not proof of a continuously
 * valid GPS fix or a functioning Bluetooth adapter. No location is recorded.
 */
internal data class PermissionSnapshot(
    val preciseLocationGranted: Boolean,
    val approximateLocationGranted: Boolean,
    val deviceLocationEnabled: Boolean,
    val bluetoothConnectGranted: Boolean,
    val notificationPermissionGranted: Boolean
) {
    val detailedNavigationReady: Boolean
        get() = preciseLocationGranted && deviceLocationEnabled

    /** Explain precisely which functionality cannot yet be relied upon. */
    fun missingCapabilities(): List<String> = buildList {
        if (!preciseLocationGranted) {
            add(
                if (approximateLocationGranted)
                    "Yalnız yaklaşık konum verildi: hassas rota takibi ve araç işaretçisi kullanılamayabilir."
                else
                    "Konum izni yok: gerçek GPS, rota başlangıcı ve konuma bağlı uyarılar kullanılamaz."
            )
        }
        if (!deviceLocationEnabled) {
            add("Telefonun konumu kapalı: GPS ölçümü alınamaz.")
        }
        if (!bluetoothConnectGranted) {
            add("Bluetooth izni yok: eşleşmiş ELM327/OBD adaptörüne bağlanılamaz.")
        }
        if (!notificationPermissionGranted) {
            add(
                "Bildirim izni yok: uygulama ve foreground servis bildirimleri " +
                    "bildirim panelinde gösterilmeyebilir; bu, servis çalışmasını tek başına durdurmaz."
            )
        }
    }

    companion object {
        fun read(context: Context): PermissionSnapshot {
            fun granted(name: String) =
                ContextCompat.checkSelfPermission(context, name) ==
                    PackageManager.PERMISSION_GRANTED
            val locationEnabled = runCatching {
                (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                    ?.isLocationEnabled == true
            }.getOrDefault(false)
            return PermissionSnapshot(
                preciseLocationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION),
                approximateLocationGranted = granted(Manifest.permission.ACCESS_COARSE_LOCATION),
                deviceLocationEnabled = locationEnabled,
                bluetoothConnectGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    granted(Manifest.permission.BLUETOOTH_CONNECT),
                notificationPermissionGranted =
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        granted(Manifest.permission.POST_NOTIFICATIONS)
            )
        }
    }
}
