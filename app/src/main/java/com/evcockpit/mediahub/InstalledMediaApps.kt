package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager

/** Launch supported media apps installed on the same Android phone. No web redirects. */
object InstalledMediaApps {
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    const val NETFLIX_PACKAGE = "com.netflix.mediaclient"
    const val PRIME_VIDEO_PACKAGE = "com.amazon.avod.thirdpartyclient"
    const val REC_TV_PACKAGE = "com.rectv.shot"

    fun openYouTube(activity: Activity) = open(activity, "YouTube", YOUTUBE_PACKAGE)
    fun openNetflix(activity: Activity) = open(activity, "Netflix", NETFLIX_PACKAGE)
    fun openPrimeVideo(activity: Activity) = open(activity, "Prime Video", PRIME_VIDEO_PACKAGE)
    fun openRecTv(activity: Activity) = open(activity, "REC TV", REC_TV_PACKAGE)

    /**
     * User-initiated handoff to the installed native app. Only a canonical
     * public content ID is forwarded; no cookies, auth or tracking parameters.
     * The official service may reject the deep link, so opening its main app
     * remains the supported fallback.
     */
    fun openStreamingTitle(activity: Activity, service: StreamingService, rawUrl: String) {
        val item=StreamingPagePolicy.canonical(rawUrl)
        val title=service.displayName
        val packageName=when(service) {
            StreamingService.NETFLIX->NETFLIX_PACKAGE
            StreamingService.PRIME_VIDEO->PRIME_VIDEO_PACKAGE
        }
        if(item==null || item.service!=service) {
            open(activity,title,packageName)
            return
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: ActivityNotFoundException) {
            open(activity,title,packageName)
        } catch (_: SecurityException) {
            open(activity,title,packageName)
        } catch (_: IllegalStateException) {
            open(activity,title,packageName)
        }
    }

    fun open(activity: Activity, title: String, packageName: String) {
        val pm = activity.packageManager
        val exists = try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!exists) {
            AlertDialog.Builder(activity)
                .setTitle("$title yüklü değil")
                .setMessage("Bu telefonda $title uygulaması yüklü görünmüyor. " +
                    "EV Cockpit tarayıcıya yönlendirme yapmayacak. " +
                    "Lütfen kurulum ve hesap durumunu cihaz üzerinden kontrol et.")
                .setPositiveButton("Tamam", null)
                .show()
            return
        }
        val intent = pm.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            AlertDialog.Builder(activity)
                .setTitle("$title başlatılamadı")
                .setMessage("$title kurulu ancak başlatılabilir ekran bulunamadı. " +
                    "Uygulamanın etkin olduğundan emin ol.")
                .setPositiveButton("Tamam", null).show()
            return
        }
        try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            showError(activity, title)
        } catch (_: SecurityException) {
            showError(activity, title)
        } catch (_: IllegalStateException) {
            showError(activity, title)
        }
    }

    private fun showError(activity: Activity, title: String) {
        AlertDialog.Builder(activity)
            .setTitle("$title açılamadı")
            .setMessage("Android, kurulu $title uygulamasının başlatılmasını reddetti. " +
                "Telefonun uygulama çekmecesinden doğrudan çalışıp çalışmadığını kontrol et.")
            .setPositiveButton("Tamam", null)
            .show()
    }
}
