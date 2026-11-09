package com.evcockpit.mediahub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/** Tiny opt-in logo loader; HTTPS-only, 512 KiB response cap, bounded in-memory cache. */
object ChannelLogoLoader {
    private val cache = object : LruCache<String, Bitmap>(24) {}
    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun into(url: String, image: ImageView, context: Context) {
        if (!M3uParser.isHttpsUrl(url)) return
        val known = cache.get(url)
        if (known != null) { image.setImageBitmap(known); image.alpha = 1f; return }
        image.tag = url
        executor.execute {
            try {
                val conn = URL(url).openConnection() as HttpsURLConnection
                conn.connectTimeout = 3500
                conn.readTimeout = 3500
                conn.instanceFollowRedirects = false
                try {
                    val length = conn.contentLength
                    val mime = conn.contentType.orEmpty().lowercase()
                    if (conn.responseCode !in 200..299 || (length > 512_000) ||
                        !(mime.startsWith("image/png") || mime.startsWith("image/jpeg") || mime.startsWith("image/webp"))) return@execute
                    val bytes = conn.inputStream.use { it.readNBytes(512_001) }
                    if (bytes.size > 512_000) return@execute
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@execute
                    cache.put(url, bitmap)
                    main.post {
                        if (image.tag == url) {
                            image.setImageBitmap(bitmap)
                            image.alpha = 1f
                        }
                    }
                } finally { conn.disconnect() }
            } catch (_: Exception) {
                // Logos are optional. Never block playback due to an unavailable image.
            }
        }
    }
}
