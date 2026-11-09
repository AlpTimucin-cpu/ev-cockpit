package com.evcockpit.mediahub

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * Independent compatibility diagnostics informed by publicly documented DiPlay
 * v0.2.20 architecture and a static inspection of the owner-provided APK.
 *
 * No DiPlay classes, assets, identity/certificates, CarPlay protocol,
 * protected endpoints or GPL/AGPL source are embedded in Media Hub.
 * CarPlay receiver code belongs on a compatible Android head unit, not the
 * projecting Android phone used with an EV Cockpit.
 */
object DiPlayCompatibility {
    const val PACKAGE = "com.shihab.diplay"
    const val REFERENCE = "https://github.com/shihabal3amri/DiPlay"

    /** Pure policy: prevent misleading compatibility / playback promises. */
    fun scope(target: String): String = when(target) {
        "phone" -> "Telefon: yalnızca tanılama. DiPlay araç ekranı alıcısıdır."
        "evcockpit" -> "VW EV Cockpit: fabrika Android Auto projeksiyonunda DiPlay APK kurulumu doğrulanmadı."
        "byd" -> "Android tabanlı uyumlu BYD üniteleri: DiPlay üreticisinin hedef platformu."
        else -> "Araç ünitesi bilinmiyor; CarPlay uyumluluğu doğrulanmadı."
    }

    fun report(context: Context): String {
        val pm = context.packageManager
        val installed=CarCompanionApps.installedVersion(context, PACKAGE)
        val usbHost=pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val usbCount = try { usb?.deviceList?.size } catch (_: SecurityException) { null }
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = try { network?.getNetworkCapabilities(network.activeNetwork) }
                   catch (_: SecurityException) { null }
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val wifiDirect=pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        val bt=pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        return buildString {
            appendLine("DIPLAY 0.2.20 / EV Cockpit UYUMLULUK ve BAĞLANTI RAPORU")
            appendLine("Telefon Android API: " + Build.VERSION.SDK_INT)
            appendLine("DiPlay telefonda: " + (installed ?: "Kurulu görünmüyor"))
            appendLine("USB host: " + if (usbHost) "Sistem özelliği var" else "Sistem özelliği yok")
            appendLine("Bağlı USB aygıtı: " + (usbCount?.toString() ?: "Sorgulanamadı"))
            appendLine("Wi-Fi Direct: " + if (wifiDirect) "Sistem özelliği var" else "Sistem özelliği yok")
            appendLine("Etkin Wi-Fi ağı: " + when (onWifi) {
                true -> "Evet"; false -> "Hayır"; null -> "Sorgulanamadı"
            })
            appendLine("Bluetooth: " + if (bt) "Sistem özelliği var" else "Sistem özelliği yok")
            appendLine()
            appendLine(scope("phone"))
            appendLine(scope("evcockpit"))
            appendLine("Bu testler TELEFONU inceler; USB/Wi-Fi bulunması araç ünitesinde " +
                "CarPlay alıcısı veya Android Auto video desteği bulunduğunu kanıtlamaz.")
            appendLine("BYD HUD / direksiyon / DiLink arayüzleri VW EV Cockpit için taşınmadı.")
            appendLine("APK'deki MFi kimlik dosyaları, sertifikalar ve CarPlay özgü " +
                "kütüphaneler kullanılmaz veya dışa aktarılmaz.")
            appendLine("Bağlantı iyileştirmesi: Media Hub oynatıcısındaki " +
                "yerel tamponlama ölçümü, kodlanmış hata türleri ve sınırlı retry.")
            append("Referans proje: $REFERENCE")
        }
    }
}
