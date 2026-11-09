package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.widget.Toast
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/**
 * End-to-end, on-phone check that a Media3 client can attach to our audio
 * library service and browse its root. It is NOT an Android Auto or video test.
 */
object CarAudioSelfTest {
    fun run(activity: Activity) {
        val token = SessionToken(activity, ComponentName(activity, AudioLibraryService::class.java))
        val pending = try {
            MediaBrowser.Builder(activity, token).buildAsync()
        } catch (_: Exception) {
            finishWithLegacy(activity, "Medya tarayıcısı başlatılamadı.")
            return
        }
        pending.addListener({
            try {
                val browser = pending.get()
                val rootFuture = browser.getLibraryRoot(null)
                rootFuture.addListener({
                    try {
                        val root = rootFuture.get()
                        val item = root.value
                        if (item == null) {
                            finishWithLegacy(activity, "Ses kitaplığının kökü alınamadı (kod " +
                                root.resultCode + ").")
                            MediaController.releaseFuture(pending)
                            return@addListener
                        }
                        val childrenFuture = browser.getChildren(item.mediaId, 0, 10, null)
                        childrenFuture.addListener({
                            try {
                                val children = childrenFuture.get()
                                val n = children.value?.size ?: 0
                                finishWithLegacy(activity,
                                    "TELEFON İÇİ SES KÜTÜPHANESİ: BAĞLANDI\n" +
                                    "Kök: " + item.mediaId + "\n" +
                                    "Bulunan ana kategori: " + n + "\n\n" +
                                    "Bu test Media Hub'ın ses servisini kontrol eder. " +
                                    "EV Cockpit Android Auto'nun bağlandığını veya video gösterdiğini " +
                                    "kanıtlamaz. Araç bağlantısını ayrı rapordan kontrol et.")
                            } catch (_: Exception) {
                                finishWithLegacy(activity, "Ses kitaplığı kategori isteği başarısız.")
                            } finally {
                                MediaController.releaseFuture(pending)
                            }
                        }, activity.mainExecutor)
                    } catch (_: Exception) {
                        finishWithLegacy(activity, "Ses kitaplığı kök isteği başarısız.")
                        MediaController.releaseFuture(pending)
                    }
                }, activity.mainExecutor)
            } catch (_: Exception) {
                finishWithLegacy(activity, "Ses servisine bağlanılamadı.")
                MediaController.releaseFuture(pending)
            }
        }, activity.mainExecutor)
        Toast.makeText(activity, "Ses kitaplığı kontrol ediliyor…",
            Toast.LENGTH_SHORT).show()
    }

    /**
     * Both requests originate on the phone. The legacy probe verifies the platform
     * MediaBrowser transport that some Android Auto versions use. Neither probe
     * demonstrates a real head-unit connection or any car-display video surface.
     */
    private fun finishWithLegacy(activity: Activity, media3Result: String) {
        CarLegacyBrowserProbe.run(activity) { legacyResult ->
            show(activity, media3Result + "\n\n" + legacyResult +
                "\n\nBu iki test telefon içindedir; EV Cockpit bağlantısını kanıtlamaz. " +
                "Gerçek araç isteğini ayrı bağlantı raporunda ara.")
        }
    }

    private fun show(activity: Activity, message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle("Telefon içi iki medya API testi")
            .setMessage(message)
            .setPositiveButton("Tamam", null)
            .show()
    }
}
