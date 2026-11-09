package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaDrm
import android.webkit.WebView
import java.util.UUID

/**
 * Clear local-only Android Auto capability report. This does not attempt to
 * bypass projection / driving restrictions or claim video capability.
 */
object CarCompatibility {
    private const val AA_PACKAGE = "com.google.android.projection.gearhead"
    private const val AABROWSER_PACKAGE = "com.kododake.aabrowser"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private fun appVersion(activity: Activity, packageName: String): String {
        return try {
            val info = activity.packageManager.getPackageInfo(packageName, 0)
            info.versionName ?: "Bilinmiyor"
        } catch (_: PackageManager.NameNotFoundException) {
            "Yüklü görünmüyor veya gizli"
        } catch (_: Exception) {
            "Sorgulanamadı"
        }
    }

    private fun widevineStatus(): String {
        val uuid = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
        if (!MediaDrm.isCryptoSchemeSupported(uuid)) return "Desteklenmiyor"
        return try {
            val drm = MediaDrm(uuid)
            try {
                drm.getPropertyString("securityLevel") + " (cihaz DRM)"
            } finally {
                drm.release()
            }
        } catch (_: Exception) {
            "Şema mevcut; güvenlik seviyesi okunamadı"
        }
    }

    private fun webEngineVersion(): String = try {
        WebView.getCurrentWebViewPackage()?.versionName ?: "Bilinmiyor"
    } catch (_: Exception) {
        "Sorgulanamadı"
    }

    fun report(activity: Activity): String = buildString {
        appendLine("EV Cockpit • Araç bağlantı raporu")
        appendLine("Android API: " + Build.VERSION.SDK_INT)
        appendLine("Cihaz: " + Build.MANUFACTURER + " " + Build.MODEL)
        appendLine("Android Auto: " + appVersion(activity, AA_PACKAGE))
        appendLine("Media Hub: " + appVersion(activity, activity.packageName))
        appendLine("AABrowser: " + appVersion(activity, AABROWSER_PACKAGE))
        appendLine("Shizuku: " + appVersion(activity, SHIZUKU_PACKAGE))
        appendLine("Android WebView: " + webEngineVersion())
        appendLine("Widevine: " + widevineStatus())
        appendLine("Widevine seviyesinin yüksek olması Netflix WebView videosunu garanti etmez.")
        appendLine()
        appendLine(CarCompanionApps.statusReport(activity))
        appendLine()
        appendLine(DiPlayCompatibility.report(activity))
        appendLine()
        appendLine("SES KİTAPLIĞI BAĞLANTI KAYITLARI")
        appendLine(CarConnectionJournal.summary(activity))
        appendLine()
        appendLine("Android Auto ses kataloğu: MediaLibraryService (uygulama içinde)")
        appendLine("Telefon video oynatıcısı: Media3 + Web Medya")
        appendLine("Android Auto park halindeki VIDEO: bu platformda onaylanmış destek yok")
        appendLine("EV Cockpit üzerinde gerçek video: DOĞRULANMADI")
        appendLine("Yerel ses API testini Kitaplık bölümünden çalıştırabilirsin.")
        appendLine("Bu yalnızca telefon servis testidir, araç bağlantısı sayılmaz.")
        appendLine("Raporu yenilemek için kapatıp yeniden aç.")
        appendLine("Araçta Media Hub'ın radyo simgesine girip kitaplığı aç;")
        appendLine("ardından raporda yeni Android Auto isteği kaydedilip kaydedilmediğini kontrol et.")
        appendLine("Hiçbir tarihin görünmemesi, sürüm/istemci uyumsuzluğu anlamına gelebilir.")
        appendLine("Bu rapor tek başına bağlantı veya araçtaki video görünürlüğünü kanıtlamaz.")
    }

    fun show(activity: Activity) {
        val contents = report(activity)
        AlertDialog.Builder(activity)
            .setTitle("Android Auto / EV Cockpit tanılama")
            .setMessage(contents)
            .setNegativeButton("Kapat", null)
            .setNeutralButton("Paylaş") { _, _ ->
                val sharing = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, contents)
                }
                try {
                    activity.startActivity(Intent.createChooser(sharing, "Tanılama raporunu paylaş"))
                } catch (_: Exception) {
                    android.widget.Toast.makeText(activity, "Paylaşım menüsü açılamadı.",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .setPositiveButton("Raporu kopyala") { _, _ ->
                val clipboard=activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("EVCOCKPIT bağlantı raporu", contents))
            }
            .show()
    }
}
