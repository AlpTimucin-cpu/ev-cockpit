package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.net.Uri
import java.net.URI

/**
 * Native provider import inspired by the *documented* Xtream support in
 * GPL-licensed MediaEngine/Fermata. This is an original implementation and DOES NOT
 * unpack, load or execute any third-party APK or unsafe plugin.
 *
 * Only streams explicitly supplied by the user from an authorized HTTPS
 * provider may be imported. The server credentials are never stored directly;
 * stream URLs stored by VideoStore/StationStore are Android Keystore encrypted.
 */
object MediaEngineXtreamImport {
    fun show(activity: Activity, onImported: () -> Unit) {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16f * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        fun field(hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText =
            EditText(activity).apply {
                this.hint = hint
                inputType = type
                isSingleLine = true
                maxLines = 1
                column.addView(this, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        val server = field("https://ornek-saglayici.com")
        val username = field("Kullanıcı adı")
        val password = field("Şifre", InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_PASSWORD)
        column.addView(TextView(activity).apply {
            text = "Hesabını yalnızca yetkili olduğun HTTPS IPTV sağlayıcısında kullan. " +
                "Şifren bu ekranda saklanmaz. İçe alınan yayın URL'leri cihazda " +
                "Android Keystore ile şifrelenir. HTTP adresleri kabul edilmez."
            textSize = 12f
        })
        AlertDialog.Builder(activity)
            .setTitle("MediaEngine • Xtream Codes kaynağı ekle")
            .setView(column)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Kanalları içe aktar") { _, _ ->
                val address = server.text.toString().trim()
                val user = username.text.toString().trim()
                val secret = password.text.toString()
                password.text.clear()
                val link = try { playlistUrl(address, user, secret) }
                    catch (_: IllegalArgumentException) { null }
                if (link == null) {
                    toast(activity, "Geçerli HTTPS sunucu adresi, kullanıcı adı ve şifre gerekli.")
                } else {
                    importSource(activity, link, onImported)
                }
            }.show()
    }

    /** Kept internal to avoid leaking the provider password in UI or logs. */
    internal fun playlistUrl(address: String, username: String, password: String): String {
        require(username.isNotBlank() && username.length <= 180)
        require(password.isNotBlank() && password.length <= 180)
        require(address.length <= 512 && M3uParser.isHttpsUrl(address))
        val uri = URI(address)
        require(uri.scheme.equals("https", true) && uri.host != null)
        require(uri.rawQuery == null && uri.rawFragment == null)
        // The Xtream API lives in the same HTTPS directory as the user's portal.
        var base = uri.path.orEmpty().trimEnd('/')
        if (base.endsWith("/c", true)) base = base.dropLast(2)
        require(!base.contains("..") && !base.contains("//"))
        val path = base + "/get.php"
        val endpoint = URI("https", null, uri.host, uri.port, path, null, null)
        val result = Uri.parse(endpoint.toString()).buildUpon()
            .appendQueryParameter("username", username)
            .appendQueryParameter("password", password)
            .appendQueryParameter("type", "m3u_plus")
            .appendQueryParameter("output", "m3u8")
            .build().toString()
        require(M3uParser.isHttpsUrl(result))
        return result
    }

    private fun importSource(activity: Activity, playlist: String, onImported: () -> Unit) {
        val waiting = AlertDialog.Builder(activity)
            .setTitle("MediaEngine • Xtream içe aktarma")
            .setMessage("Sağlayıcının HTTPS yayın listesi okunuyor. Şifreler ve tam URL'ler günlüğe yazılmıyor.")
            .setCancelable(false)
            .create()
        waiting.show()
        Thread {
            val result = try {
                val m3u = PlaylistLoader.fromHttps(playlist, strictOrigin = true)
                val items = M3uParser.parse(m3u)
                require(items.isNotEmpty()) {
                    "Sağlayıcı geçerli HTTPS yayın listesi döndürmedi."
                }
                val audios = StationStore.addAll(activity.applicationContext, items)
                val videos = VideoStore.addAll(activity.applicationContext, items)
                ImportResult(audios, videos, null)
            } catch (_: Exception) {
                ImportResult(0, 0,
                    "İçe aktarma başarısız. Hesap bilgilerini, sağlayıcı bağlantısını ve " +
                    "HTTPS yayınlarını kontrol et. HTTP yayınlar güvenlik nedeniyle atlanır.")
            }
            activity.runOnUiThread {
                waiting.dismiss()
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (result.error != null) {
                    AlertDialog.Builder(activity).setTitle("Xtream kaynağı yüklenemedi")
                        .setMessage(result.error)
                        .setPositiveButton("Tamam", null).show()
                } else {
                    AlertDialog.Builder(activity).setTitle("MediaEngine kaynakları alındı")
                        .setMessage("${result.video} yeni video/TV ve " +
                            "${result.audio} yeni radyo eklendi.\n\n" +
                            "Mevcut kanallar ve favoriler korundu. " +
                            "Yayınların gerçekten oynatılması ayrıca kontrol edilmelidir.")
                        .setPositiveButton("Tamam", null)
                        .show()
                    onImported()
                }
            }
        }.start()
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private data class ImportResult(val audio: Int, val video: Int, val error: String?)
}
