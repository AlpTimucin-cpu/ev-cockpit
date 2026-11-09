package com.evcockpit.mediahub

import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * User-invoked HTTPS media availability probe. It reports only status and
 * media type, never host, query string, bearer tokens, cookies or response body.
 * This is not an authorization workaround or a substitute for playback.
 */
data class SourceCheck(val status: Int?, val kind: SourceCheckKind, val description: String)
enum class SourceCheckKind { AVAILABLE, ACCESS_DENIED, NOT_FOUND, THROTTLED, TEMPORARY, BAD_FORMAT, UNREACHABLE }

object SourceHealthPolicy {
    fun classify(status: Int?, contentType: String?, signature: String = ""): SourceCheck {
        val mime = contentType.orEmpty().substringBefore(';').trim().lowercase()
        val hasM3u = signature.trimStart('\uFEFF', '\r', '\n', ' ', '\t')
            .startsWith("#EXTM3U", ignoreCase = true)
        return when (status) {
            200, 206 -> {
                when {
                    mime.contains("text/html") || signature.trimStart().startsWith("<!DOCTYPE", true) ||
                        signature.trimStart().startsWith("<html", true) ->
                        SourceCheck(status, SourceCheckKind.BAD_FORMAT,
                            "Sunucu video yerine HTML sayfası döndürüyor. Giriş ya da erişim engeli olabilir.")
                    mime.contains("application/json") ||
                        (signature.trimStart().startsWith("{") && !hasM3u) ->
                        SourceCheck(status, SourceCheckKind.BAD_FORMAT,
                            "Sunucu video yerine JSON yanıtı döndürüyor. Bu adres medya oynatma bağlantısı değil.")
                    mime.contains("mpegurl") || mime.contains("video/") ||
                        mime.contains("octet-stream") || mime.contains("dash+xml") || hasM3u ->
                        SourceCheck(status, SourceCheckKind.AVAILABLE,
                            "Sunucu medya benzeri veri döndürdü. Bu, videonun oynayacağını garanti etmez.")
                    else -> SourceCheck(status, SourceCheckKind.AVAILABLE,
                        "Sunucu HTTP yanıtı veriyor ancak formatı kesin doğrulanamadı.")
                }
            }
            401, 403 -> SourceCheck(status, SourceCheckKind.ACCESS_DENIED,
                "Sunucu erişimi reddediyor. Kaynak izni veya geçerli oturum gerekli.")
            404, 410 -> SourceCheck(status, SourceCheckKind.NOT_FOUND,
                "Yayın sunucuda bulunamadı. Bağlantı değişmiş veya süresi dolmuş olabilir.")
            429 -> SourceCheck(status, SourceCheckKind.THROTTLED,
                "Sunucu isteği sınırladı. Tekrar tekrar denemek uygun değil.")
            408, 500, 502, 503, 504 -> SourceCheck(status, SourceCheckKind.TEMPORARY,
                "Sunucu geçici bir hata veriyor. Daha sonra yeniden denenebilir.")
            null -> SourceCheck(null, SourceCheckKind.UNREACHABLE,
                "HTTPS sunucusuna ulaşılamadı. İnternet, DNS veya zaman aşımını kontrol et.")
            else -> SourceCheck(status, SourceCheckKind.UNREACHABLE,
                "Sunucu HTTP " + status + " yanıtı döndürüyor.")
        }
    }
}

object SourceHealthProbe {
    fun check(source: String): SourceCheck {
        if (!M3uParser.isHttpsUrl(source))
            return SourceCheck(null, SourceCheckKind.UNREACHABLE,
                "Yalnızca HTTPS medya bağlantıları ağ üzerinden kontrol edilir.")
        return try {
            var target = URL(source)
            repeat(4) {
                require(target.protocol.equals("https", true) && target.userInfo == null)
                val connection = (target.openConnection() as HttpsURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6_000
                    readTimeout = 6_000
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/vnd.apple.mpegurl, video/*, */*")
                    setRequestProperty("Range", "bytes=0-1023")
                    setRequestProperty("User-Agent", "EV-Cockpit")
                }
                try {
                    val status = connection.responseCode
                    if (status in 300..399) {
                        val loc = connection.getHeaderField("Location")
                            ?: return SourceHealthPolicy.classify(status, null)
                        val next = URL(target, loc)
                        if (!next.protocol.equals("https", true) || next.userInfo != null)
                            return SourceCheck(status, SourceCheckKind.UNREACHABLE,
                                "HTTPS dışına yönlendirme engellendi.")
                        target = next
                    } else {
                        // Read at most 1 KiB; never print response text in a log/report.
                        val signature = if (status == 200 || status == 206)
                            connection.inputStream.use { stream ->
                                val chunk=ByteArray(1024)
                                val n=stream.read(chunk)
                                if(n>0) String(chunk,0,n,Charsets.UTF_8) else ""
                            } else ""
                        return SourceHealthPolicy.classify(
                            status, connection.getHeaderField("Content-Type"), signature)
                    }
                } finally {
                    connection.disconnect()
                }
            }
            SourceCheck(null, SourceCheckKind.UNREACHABLE,
                "Sunucu çok fazla yönlendirme yapıyor.")
        } catch (problem: Exception) {
            // Exception messages often include signed URL tokens. Never expose them.
            val safe = SafeConnectionFailure.classify(problem)
            SourceCheck(null, SourceCheckKind.UNREACHABLE,
                safe.kind.name + " • " + safe.explanation)
        }
    }
}
