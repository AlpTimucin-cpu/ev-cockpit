package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent

/**
 * Launches a separately installed SportzX Android application.
 *
 * Package and exported entry point verified from user-provided SportzX_3.2v.apk:
 * com.sportzx.live / com.sportzx.live.activities.SplashActivity.
 * Never downloads, bundles, patches, or opens the SportzX website.
 */
object SportzXLauncher {
    private const val PACKAGE = "com.sportzx.live"
    private const val SPLASH = "com.sportzx.live.activities.SplashActivity"

    fun isInstalled(activity: Activity): Boolean =
        activity.packageManager.getLaunchIntentForPackage(PACKAGE) != null

    fun openIfInstalled(activity: Activity) {
        val intent = activity.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(PACKAGE, SPLASH))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showNotInstalled(activity)
        } catch (_: SecurityException) {
            showCannotStart(activity)
        } catch (_: IllegalArgumentException) {
            showCannotStart(activity)
        }
    }

    private fun showNotInstalled(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("SportzX yüklü değil")
            .setMessage("SportzX 3.2 Android uygulaması telefonda bulunamadı. " +
                "Önce ayrı olarak kurman gerekiyor; Media Hub herhangi bir indirme sitesine yönlendirmez.")
            .setPositiveButton("Tamam", null)
            .show()
    }

    private fun showCannotStart(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("SportzX açılamadı")
            .setMessage("SportzX yüklü olsa da Android uygulamayı başlatamadı. " +
                "SportzX uygulamasının kendi başına açılıp açılmadığını kontrol et.")
            .setPositiveButton("Tamam", null)
            .show()
    }
}
