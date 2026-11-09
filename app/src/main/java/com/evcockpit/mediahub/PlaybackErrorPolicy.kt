package com.evcockpit.mediahub

/** Pure, privacy-safe failure classification for independently controlled Media3 retry. */
data class PlaybackErrorDecision(
    val category: String,
    val userMessage: String,
    val retryable: Boolean
)

object PlaybackErrorPolicy {
    /**
     * No URLs, account tokens, DRM values or exception strings are persisted.
     * 401/403/404 and DRM errors must never be retried automatically.
     */
    fun classify(codeName: String, httpStatus: Int? = null): PlaybackErrorDecision {
        val error = codeName.uppercase(java.util.Locale.ROOT)
        return when {
            httpStatus == 401 || httpStatus == 403 -> PlaybackErrorDecision(
                "permission", "Sunucu erişimi reddetti (HTTP $httpStatus). Yetkili ve güncel kaynak gerekli.", false)
            httpStatus == 404 || httpStatus == 410 -> PlaybackErrorDecision(
                "missing", "Bağlantı bulunamadı veya kaldırıldı (HTTP $httpStatus).", false)
            httpStatus == 429 -> PlaybackErrorDecision(
                "throttled", "Sunucu çok fazla istek aldı (HTTP 429). Daha sonra elle dene.", false)
            httpStatus != null && httpStatus in 500..599 -> PlaybackErrorDecision(
                "server", "Sunucu geçici bir hata verdi (HTTP $httpStatus).", true)
            "DRM" in error -> PlaybackErrorDecision(
                "drm", "Yayın resmî sağlayıcının DRM lisansını gerektiriyor.", false)
            "NETWORK" in error || "TIMEOUT" in error ||
                "CONNECTION_FAILED" in error -> PlaybackErrorDecision(
                "network", "Bağlantı kesildi veya zaman aşımına uğradı.", true)
            "BAD_HTTP_STATUS" in error || "FILE_NOT_FOUND" in error -> PlaybackErrorDecision(
                "http", "Sunucu bu yayın adresini kabul etmedi.", false)
            "PARSING" in error || "FORMAT" in error || "DECODING" in error -> PlaybackErrorDecision(
                "format", "Yayın biçimi veya kod çözücü bu videoyu açamadı.", false)
            else -> PlaybackErrorDecision(
                "unknown", "Video oynatılamadı. Kaynağın erişim yetkisini ve güncelliğini kontrol et.", false)
        }
    }
}
