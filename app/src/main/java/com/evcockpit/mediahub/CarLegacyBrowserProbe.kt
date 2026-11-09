package com.evcockpit.mediahub

import android.app.Activity
import android.content.ComponentName
import android.media.browse.MediaBrowser
import android.os.Handler
import android.os.Looper

/**
 * On-phone compatibility probe for platform MediaBrowser clients (API 21+).
 * Android Auto can use this legacy transport to browse Media3's library service.
 * A successful result DOES NOT prove an actual vehicle connection or car video.
 */
object CarLegacyBrowserProbe {
    fun run(activity: Activity, complete: (String) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var browser: MediaBrowser? = null
        var finished = false
        lateinit var timeout: Runnable

        fun finish(result: String) {
            if (finished) return
            finished = true
            handler.removeCallbacks(timeout)
            try {
                browser?.disconnect()
            } catch (_: Exception) {
                // A failed disconnect must not hide the original test outcome.
            }
            complete(result)
        }

        timeout = Runnable { finish("ESKİ ANDROID MEDIABROWSER: ZAMAN AŞIMI (10 sn)") }
        val callback = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                try {
                    val connected = browser ?: run {
                        finish("ESKİ ANDROID MEDIABROWSER: Tarayıcı bulunamadı")
                        return
                    }
                    val root = connected.root
                    if (root != "evcockpit_audio_root") {
                        finish("ESKİ ANDROID MEDIABROWSER: Beklenmeyen kitaplık kökü")
                        return
                    }
                    connected.subscribe(root, object : MediaBrowser.SubscriptionCallback() {
                        override fun onChildrenLoaded(
                            parentId: String,
                            children: MutableList<MediaBrowser.MediaItem>
                        ) {
                            val ids = children.map { it.mediaId }.toSet()
                            val expected = setOf(
                                "evcockpit_audio_all", "evcockpit_audio_favorites", "evcockpit_audio_groups",
                                "evcockpit_audio_recents", "evcockpit_podcast_root"
                            )
                            val ok = parentId == root && ids.containsAll(expected)
                            finish(if (ok) {
                                "ESKİ ANDROID MEDIABROWSER: BAĞLANDI (5 kök kategori)"
                            } else {
                                "ESKİ ANDROID MEDIABROWSER: Eksik veya yanlış kök kategorileri"
                            })
                        }

                        override fun onError(parentId: String) {
                            finish("ESKİ ANDROID MEDIABROWSER: Kitaplık isteği başarısız")
                        }
                    })
                } catch (_: Exception) {
                    finish("ESKİ ANDROID MEDIABROWSER: Kitaplık okunamadı")
                }
            }

            override fun onConnectionFailed() {
                finish("ESKİ ANDROID MEDIABROWSER: Bağlantı reddedildi")
            }

            override fun onConnectionSuspended() {
                finish("ESKİ ANDROID MEDIABROWSER: Bağlantı kesildi")
            }
        }
        try {
            browser = MediaBrowser(
                activity,
                ComponentName(activity, AudioLibraryService::class.java),
                callback,
                null
            )
            handler.postDelayed(timeout, 10_000L)
            browser?.connect()
        } catch (_: Exception) {
            finish("ESKİ ANDROID MEDIABROWSER: Başlatılamadı")
        }
    }
}
