package com.evcockpit.mediahub

/**
 * Failure classification shared by VOD error UI and its finite recovery policy.
 * No URL, token, exception message, or user-identifying stream name is needed.
 *
 * DiPlay research informed the session-recovery *goals*, not this implementation.
 * DiPlay GPL/AGPL source, binary and CarPlay credentials are not included here.
 */
enum class VodFailureKind { ACCESS, GONE, RATE_LIMIT, SERVER, NETWORK, DRM, DECODER, FORMAT, UNKNOWN }

data class VodFailure(val kind: VodFailureKind, val canRetry: Boolean, val message: String)

object VodErrorPolicy {
    fun classify(httpStatus: Int?, code: String): VodFailure {
        when (httpStatus) {
            401, 403 -> return VodFailure(VodFailureKind.ACCESS, false,
                "Sunucu yayına erişimi reddetti (HTTP $httpStatus). Yetki veya oturum gerekebilir.")
            404, 410 -> return VodFailure(VodFailureKind.GONE, false,
                "Bu yayın adresi artık bulunamıyor (HTTP $httpStatus). Güncel kaynak gerekli.")
            429 -> return VodFailure(VodFailureKind.RATE_LIMIT, false,
                "Sunucu bağlantı sınırına ulaştı (HTTP 429). Daha sonra elle tekrar deneyebilirsin.")
            408, 500, 502, 503, 504 -> return VodFailure(VodFailureKind.SERVER, true,
                "Yayın sunucusunda geçici HTTP $httpStatus sorunu var.")
        }
        if (httpStatus != null && httpStatus in 400..599)
            return VodFailure(VodFailureKind.SERVER, false, "Yayın sunucusu HTTP $httpStatus hatası döndürdü.")
        return when {
            code.contains("DRM",true) -> VodFailure(VodFailureKind.DRM,false,
                "İçerik lisans/DRM denetimi gerektiriyor; uygulama bunu aşamaz.")
            code.contains("DECOD",true) -> VodFailure(VodFailureKind.DECODER,false,
                "Video veya ses çözücü bu akışı desteklemiyor.")
            code.contains("PARSING",true) || code.contains("FORMAT",true) ->
                VodFailure(VodFailureKind.FORMAT,false,
                    "Yayın biçimi tanınamadı veya kaynak dosyası hatalı.")
            code.contains("NETWORK",true) || code.contains("TIMEOUT",true) ->
                VodFailure(VodFailureKind.NETWORK,true,
                    "Geçici internet bağlantısı / zaman aşımı sorunu.")
            else -> VodFailure(VodFailureKind.UNKNOWN,false,
                "Video başlatılamadı. Yayın adresi veya kaynağın erişim hakkını kontrol et.")
        }
    }
}
