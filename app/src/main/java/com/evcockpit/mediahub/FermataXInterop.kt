package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

/**
 * User-confirmed phone-side media handoff to the exported VIEW activity in the
 * supplied FermataX 2.2.6 debug manifest. Never invokes an AA projection service.
 */
object FermataXInterop {
    const val DEBUG_PACKAGE = "me.app.fermataX.auto.debug"
    const val EXPORTED_ACTIVITY = "me.aap.fermata.ui.activity.MainActivity"

    fun canShare(url: String): Boolean = M3uParser.isHttpsUrl(url)

    fun offerVideo(activity: Activity, channel: VideoChannel?) =
        offer(activity, channel?.name, channel?.url, "video/*", "TV kanalı")

    fun offerRadio(activity: Activity, station: Station?) =
        offer(activity, station?.name, station?.url, "audio/*", "radyo")

    private fun offer(
        activity: Activity,
        name: String?,
        address: String?,
        mime: String,
        label: String
    ) {
        if (name == null || address == null) {
            AlertDialog.Builder(activity)
                .setTitle("Önce " + label + " seç")
                .setMessage("FermataX'e göndermek istediğin yayını önce EV Cockpit Drive Hub'da seç.")
                .setPositiveButton("Tamam", null)
                .show()
            return
        }
        if (!canShare(address)) {
            AlertDialog.Builder(activity)
                .setTitle("Bağlantı gönderilemedi")
                .setMessage("Yalnızca geçerli HTTPS yayın adresleri FermataX'e aktarılabilir.")
                .setPositiveButton("Tamam", null)
                .show()
            return
        }
        val installed = CarCompanionApps.installedVersion(activity, DEBUG_PACKAGE)
        if (installed == null) {
            AlertDialog.Builder(activity)
                .setTitle("FermataX Debug kurulu değil")
                .setMessage("Manifestte doğrulanan FermataX Debug paketi bu telefonda bulunamadı. " +
                    "Başka bir uygulama otomatik yüklenmez.")
                .setPositiveButton("Tamam", null)
                .show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("FermataX ile telefonda aç")
            .setMessage("“" + name.take(80) + "” yayınının HTTPS bağlantısı " +
                "FermataX ($installed) uygulamasına gönderilecek. " +
                "Abonelik bağlantısında erişim anahtarı bulunabilir. " +
                "Gönderimi onaylıyor musun? Araç ekranında video başlatılmaz.")
            .setNegativeButton("İptal", null)
            .setPositiveButton("Bağlantıyı gönder") { _, _ ->
                // MainActivity is explicitly exported and supports ACTION_VIEW
                // media links in the supplied 2.2.6 manifest. Playback isn't
                // guaranteed and requires a real device with FermataX installed.
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setClassName(DEBUG_PACKAGE, EXPORTED_ACTIVITY)
                    setDataAndType(Uri.parse(address), mime)
                }
                try {
                    activity.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    unavailable(activity)
                } catch (_: SecurityException) {
                    unavailable(activity)
                } catch (_: IllegalArgumentException) {
                    unavailable(activity)
                }
            }
            .show()
    }

    private fun unavailable(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("FermataX açılamadı")
            .setMessage("FermataX bağlantı isteğini kabul etmedi veya Android engelledi. " +
                "Media Hub kitaplığın değişmedi. Gerçek cihaz testi gerekiyor.")
            .setPositiveButton("Tamam", null)
            .show()
    }
}
