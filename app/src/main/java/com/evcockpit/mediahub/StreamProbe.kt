package com.evcockpit.mediahub

import java.net.URL
import javax.net.ssl.HttpsURLConnection

enum class StreamStatus { SERVER_RESPONDS, LOGIN_REQUIRED, NOT_FOUND, REDIRECT, HEAD_UNSUPPORTED, SERVER_PROBLEM, UNKNOWN }

data class StreamProbeResult(val status: StreamStatus, val httpCode: Int? = null) {
    fun description(): String = when (status) {
        StreamStatus.SERVER_RESPONDS -> "Sunucu HTTP ${httpCode ?: 200} yanıtladı. Yayının oynatılabildiği doğrulanmadı."
        StreamStatus.LOGIN_REQUIRED -> "HTTP $httpCode: erişim izni veya oturum gerekebilir."
        StreamStatus.NOT_FOUND -> "HTTP $httpCode: içerik bulunamadı veya kaldırılmış."
        StreamStatus.REDIRECT -> "HTTP $httpCode: adres yönlendiriyor. Otomatik takip edilmedi."
        StreamStatus.HEAD_UNSUPPORTED -> "HTTP $httpCode: sunucu HEAD isteğini desteklemiyor; yayın hakkında kesin sonuç yok."
        StreamStatus.SERVER_PROBLEM -> "HTTP $httpCode: sunucu hatası."
        StreamStatus.UNKNOWN -> "Bağlantı kontrol edilemedi (ağ, sertifika veya zaman aşımı)."
    }
}

/** Diagnostic only: HEAD never downloads a broadcast or tries to bypass access restrictions. */
object StreamProbe {
    fun classify(code: Int): StreamProbeResult = StreamProbeResult(when (code) {
        in 200..299 -> StreamStatus.SERVER_RESPONDS
        401, 403 -> StreamStatus.LOGIN_REQUIRED
        404, 410 -> StreamStatus.NOT_FOUND
        in 300..399 -> StreamStatus.REDIRECT
        405, 501 -> StreamStatus.HEAD_UNSUPPORTED
        in 500..599 -> StreamStatus.SERVER_PROBLEM
        else -> StreamStatus.UNKNOWN
    }, code)

    fun check(url: String): StreamProbeResult {
        if (!M3uParser.isHttpsUrl(url)) return StreamProbeResult(StreamStatus.UNKNOWN)
        var connection: HttpsURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpsURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 7_000
                readTimeout = 7_000
                instanceFollowRedirects = false
                useCaches = false
            }
            classify(connection.responseCode)
        } catch (_: Exception) {
            // Never include URL, auth tokens, or exceptions containing URLs in UI/logcat.
            StreamProbeResult(StreamStatus.UNKNOWN)
        } finally { connection?.disconnect() }
    }
}
