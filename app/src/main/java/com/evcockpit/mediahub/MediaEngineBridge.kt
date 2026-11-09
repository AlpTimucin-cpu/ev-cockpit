package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

/**
 * User-confirmed phone-side handoff to a separately installed MediaEngine app.
 * This is stage 1, not an embedded copy of MediaEngine or proof of car-screen video.
 */
object MediaEngineBridge {
    const val MEDIAENGINE_4110 = "me.app.fermataX.auto.mola.debug"
    const val MEDIAENGINE_32 = "me.app.fermataX.auto.mola32.debug"
    const val MEDIAENGINE_UPSTREAM = "me.app.fermataX.auto.debug"
    const val MEDIAENGINE_RELEASE = "me.app.fermataX.auto"
    private const val MAIN_ACTIVITY = "me.aap.fermata.ui.activity.MainActivity"
    private val packages = listOf(MEDIAENGINE_4110, MEDIAENGINE_32, MEDIAENGINE_UPSTREAM, MEDIAENGINE_RELEASE)

    private fun installed(activity: Activity): Pair<String, String>? =
        packages.firstNotNullOfOrNull { name ->
            CarCompanionApps.installedVersion(activity, name)?.let { name to it }
        }

    fun status(activity: Activity): String =
        installed(activity)?.let { (name, version) -> "Bulundu: $version ($name)" }
            ?: "MediaEngine yüklü görünmüyor. Uygulama indirilmez veya kendiliğinden kurulmaz."

    fun open(activity: Activity) {
        val found = installed(activity)
        if (found == null) {
            message(activity, "MediaEngine bulunamadı", "MediaEngine 4.1.10 veya bilinen FermataX paketlerinden biri bu telefonda kurulu değil.")
            return
        }
        val (name, version) = found
        val launch = activity.packageManager.getLaunchIntentForPackage(name)
        if (launch == null) {
            message(activity, "MediaEngine başlatılamıyor", "Paket görüldü fakat başlatılabilir telefon etkinliği bulunamadı.")
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("MediaEngine'yı aç")
            .setMessage("MediaEngine $version ayrı bir uygulama olarak telefonda açılacak.\n\n" +
                "Bu işlem yeni izin vermez, APK yüklemez veya ekran paylaşımı başlatmaz. " +
                "Android Auto ekranındaki görüntü ayrıca doğrulanmalıdır.")
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Telefonda aç") { _, _ ->
                try {
                    activity.startActivity(launch)
                } catch (_: ActivityNotFoundException) {
                    message(activity, "MediaEngine açılamadı", "Android başlatma isteğini kabul etmedi.")
                } catch (_: SecurityException) {
                    message(activity, "MediaEngine açılamadı", "Android erişim isteğini reddetti.")
                } catch (_: IllegalStateException) {
                    message(activity, "MediaEngine açılamadı", "Uygulama başlatılamadı.")
                }
            }
            .show()
    }

    fun chooseVideo(activity: Activity) {
        val channels = VideoStore.load(activity)
        if (channels.isEmpty()) {
            message(activity, "Kanal bulunamadı", "Önce EV Cockpit içinden kendi yasal M3U listenizi ekleyin.")
            return
        }
        val candidates = channels.filter { M3uParser.isHttpsUrl(it.url) }.take(150)
        if (candidates.isEmpty()) {
            message(activity, "HTTPS kanal bulunamadı", "MediaEngine'ya yalnızca seçtiğin HTTPS yayın adresi gönderilebilir.")
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("MediaEngine'da açılacak TV kanalını seç")
            .setItems(candidates.map { it.name.take(90) }.toTypedArray()) { _, i ->
                val channel = candidates[i]
                offer(activity, channel.name, channel.url, "video/*")
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    fun offerRadio(activity: Activity, station: Station?) {
        if (station == null) {
            message(activity, "Radyo seç", "MediaEngine'ya göndermek için önce Radyo bölümünden bir istasyon seç.")
            return
        }
        offer(activity, station.name, station.url, "audio/*")
    }

    private fun offer(activity: Activity, title: String, address: String, mime: String) {
        val found = installed(activity)
        if (found == null) {
            message(activity, "MediaEngine bulunamadı", "MediaEngine uygulaması telefonda yüklü değil.")
            return
        }
        if (!M3uParser.isHttpsUrl(address)) {
            message(activity, "Güvensiz yayın adresi", "Bu aktarımda yalnızca HTTPS yayın bağlantılarına izin verilir.")
            return
        }
        val (name, version) = found
        AlertDialog.Builder(activity)
            .setTitle("MediaEngine'ya tek yayın gönder")
            .setMessage("“${title.take(80)}” yayınının adresi MediaEngine $version uygulamasına gönderilecek.\n\n" +
                "Bağlantı özel abonelik anahtarı içerebilir; yalnızca güvendiğin kaynağı paylaş. " +
                "Bu işlem araç ekranında video oynatmayı garanti etmez. Devam edilsin mi?")
            .setNegativeButton("İptal", null)
            .setPositiveButton("Bağlantıyı gönder") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setClassName(name, MAIN_ACTIVITY)
                    setDataAndType(Uri.parse(address), mime)
                }
                try {
                    activity.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    message(activity, "MediaEngine isteği reddetti", "Bu MediaEngine sürümü medya bağlantısını kabul etmiyor olabilir.")
                } catch (_: SecurityException) {
                    message(activity, "MediaEngine isteği reddetti", "Android bu etkinliğe erişimi engelledi.")
                } catch (_: IllegalArgumentException) {
                    message(activity, "Bağlantı açılamadı", "Yayın adresi hedef uygulamada desteklenmiyor.")
                }
            }
            .show()
    }

    private fun message(activity: Activity, title: String, detail: String) {
        AlertDialog.Builder(activity).setTitle(title).setMessage(detail)
            .setPositiveButton("Tamam", null).show()
    }
}
