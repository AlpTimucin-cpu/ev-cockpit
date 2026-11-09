package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import rikka.shizuku.Shizuku

/**
 * Optional companion-app diagnostics for the PHONE only.
 *
 * No privileged Shizuku commands, AAAD installations, Android Auto category
 * impersonation, motion-signal changes, or in-motion video activation.
 */
object CarCompanionApps {
    private const val SHIZUKU = "moe.shizuku.privileged.api"
    private const val AAAD = "sksa.aa.customapps"
    private const val FERMATA_MOBILE = "me.aap.fermata"
    private const val FERMATA_AUTO = "me.aap.fermata.auto"
    private const val FERMATA_AAAD = "me.aap.fermata.auto.dear.google.why"

    // Fermata's official build uses '.auto'; AAAD has historically supplied a
    // separately suffixed package. Exact package visibility is declared in the manifest.
    private val fermataVariants = listOf(FERMATA_AUTO, FERMATA_AAAD, FERMATA_MOBILE)

    fun installedVersion(context: Context, packageName: String): String? = try {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        info.versionName ?: "Sürüm bilinmiyor"
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun shizukuStatus(context: Context): String {
        val version = installedVersion(context, SHIZUKU) ?: return "Kurulu görünmüyor"
        // Only read binder and permission states. Never request higher privileges.
        return try {
            if (!Shizuku.pingBinder()) "$version • API hizmeti yanıt vermiyor"
            else {
                val permission = if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    "Media Hub izni var"
                } else "Media Hub izni yok"
                "$version • API çalışıyor • $permission"
            }
        } catch (_: Exception) {
            "$version • API durumu okunamadı"
        }
    }

    fun statusReport(context: Context): String = buildString {
        appendLine("SHIZUKU / AAAD / FERMATA • TELEFON DURUMU")
        appendLine("Android API: " + Build.VERSION.SDK_INT)
        appendLine("Shizuku: " + shizukuStatus(context))
        appendLine("AAAD: " + (installedVersion(context, AAAD) ?: "Kurulu görünmüyor"))
        val found = fermataVariants.mapNotNull { pkg ->
            installedVersion(context, pkg)?.let { version -> pkg to version }
        }
        if (found.isEmpty()) appendLine("Fermata: Kurulu görünmüyor")
        else found.forEach { (pkg, version) ->
            appendLine("Fermata: $version ($pkg)")
        }
        appendLine()
        appendLine("Bu kontrol herhangi bir Shizuku izni istemez ya da kullanmaz.")
        appendLine("AAAD yalnızca bağımsız bir uygulamadır; Media Hub üzerinden APK yüklenmez.")
        appendLine("Fermata telefonda açılabilir. Android Auto araç ekranında görünmesi ayrı testtir.")
        appendLine("Hareket halinde ön ekranda video desteği iddia edilmez.")
    }

    fun show(activity: Activity) {
        val contents = statusReport(activity)
        AlertDialog.Builder(activity)
            .setTitle("Araç yardımcı uygulamaları")
            .setMessage(contents)
            .setPositiveButton("Tamam", null)
            .setNeutralButton("Fermata'yı aç") { _, _ -> launchFermataOnPhone(activity) }
            .show()
    }

    private fun launchFermataOnPhone(activity: Activity) {
        val pm = activity.packageManager
        val launcher = fermataVariants.firstNotNullOfOrNull { pkg ->
            if (installedVersion(activity, pkg) == null) null
            else pm.getLaunchIntentForPackage(pkg)
        }
        if (launcher == null) {
            AlertDialog.Builder(activity)
                .setTitle("Fermata açılamadı")
                .setMessage("Telefonda başlatılabilir Fermata sürümü bulunamadı. " +
                    "AAAD/Fermata kurulumu değiştirilmedi.")
                .setPositiveButton("Tamam", null)
                .show()
            return
        }
        try {
            activity.startActivity(launcher)
        } catch (_: Exception) {
            AlertDialog.Builder(activity)
                .setTitle("Fermata açılamadı")
                .setMessage("Telefon, Fermata uygulamasını açma isteğini reddetti.")
                .setPositiveButton("Tamam", null)
                .show()
        }
    }
}
