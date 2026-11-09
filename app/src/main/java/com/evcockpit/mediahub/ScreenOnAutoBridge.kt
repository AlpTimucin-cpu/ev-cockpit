package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Uses ScreenOnAuto's PUBLICLY DOCUMENTED start/stop activity actions.
 * This is phone-side user-initiated interoperation, not an Android Auto bypass.
 */
object ScreenOnAutoBridge {
    const val PACKAGE = "idv.lzn.screenonauto"
    const val START = "idv.lzn.screenonauto.action.START_MIRROR"
    const val STOP = "idv.lzn.screenonauto.action.STOP_MIRROR"
    private const val CONTROL_ACTIVITY = "idv.lzn.screenonauto.MirrorControlActivity"

    fun isInstalled(activity: Activity): Boolean = try {
        activity.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) { false }
      catch (_: Exception) { false }

    fun send(activity: Activity, start: Boolean) {
        if (!isInstalled(activity)) {
            AlertDialog.Builder(activity)
                .setTitle("ScreenOnAuto yüklü değil")
                .setMessage("POCO F5 üzerinde ScreenOnAuto kurulu görünmüyor. " +
                    "Android 15 için geliştiricinin resmî Play test kurulumunu kullan. " +
                    "Media Hub otomatik APK indirme veya kurulum yapmaz.")
                .setPositiveButton("Tamam", null).show()
            return
        }
        val action = if (start) START else STOP
        val intent = Intent(action).setClassName(PACKAGE, CONTROL_ACTIVITY)
        try {
            activity.startActivity(intent)
            android.widget.Toast.makeText(
                activity, if (start) "ScreenOnAuto başlatma isteği gönderildi" else "Durdurma isteği gönderildi",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (_: ActivityNotFoundException) {
            error(activity)
        } catch (_: SecurityException) {
            error(activity)
        } catch (_: IllegalStateException) {
            error(activity)
        }
    }

    private fun error(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("ScreenOnAuto yanıt vermedi")
            .setMessage("Yansıtma komutu başlatılamadı. Kurulu sürümde resmî kontrol " +
                "etkinliğinin olup olmadığını denetle. Bu işlem araç ekranında görüntüyü garanti etmez.")
            .setPositiveButton("Tamam", null).show()
    }
}
