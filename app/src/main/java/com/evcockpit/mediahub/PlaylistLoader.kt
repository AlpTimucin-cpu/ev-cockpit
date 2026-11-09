package com.evcockpit.mediahub

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

object PlaylistLoader {
    private const val MAX_BYTES = 8_000_000

    fun fromHttps(url: String, strictOrigin: Boolean = false): String {
        require(M3uParser.isHttpsUrl(url)) { "HTTPS M3U adresi gerekli." }
        val initial = URL(url)
        var current = initial
        repeat(5) {
            // Only HTTPS-to-HTTPS redirects are allowed. Never expose a token-bearing URL in the UI.
            val connection = (current.openConnection() as HttpsURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json, audio/x-mpegurl, application/x-mpegURL, text/plain, */*")
                setRequestProperty("User-Agent", "EV-Cockpit/0.9")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val destination = connection.getHeaderField("Location")
                        ?: error("M3U yönlendirmesi eksik")
                    val next = URL(current, destination)
                    require(next.protocol.equals("https", ignoreCase = true)) {
                        "Yalnızca HTTPS yönlendirmeleri desteklenir"
                    }
                    require(next.userInfo == null) { "Kimlik içeren URL desteklenmez" }
                    if (strictOrigin) require(next.host.equals(initial.host, ignoreCase = true) &&
                        next.port == initial.port) {
                        "Xtream hesabı başka sunucuya yönlendirilmeyecek"
                    }
                    current = next
                } else {
                    require(code == 200) { "M3U sunucusu HTTP $code yanıtı döndürdü" }
                    return connection.inputStream.use { readLimited(it) }
                }
            } finally { connection.disconnect() }
        }
        error("M3U yönlendirme sayısı aşıldı")
    }

    fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            total += count
            require(total <= MAX_BYTES) { "M3U listesi 8 MB sınırını aşıyor." }
            out.write(buffer, 0, count)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
