package com.evcockpit.domain.model

/** Independent evidence category: never describe a model or scenario as measured. */
enum class EvidenceClass { MEASUREMENT, PROVIDER_FORECAST, MODEL_ESTIMATE, USER_SCENARIO }
enum class EvidenceSource { FUSED_GPS, VERIFIED_OBD, PROVIDER, ENERGY_MODEL, USER_INPUT }

/** Each available value must carry its own source and observation time. */
sealed interface CoreEvidence<out T> {
    data class Available<T : Any>(
        val value: T,
        val recordedAtMillis: Long,
        val source: EvidenceSource,
        val classification: EvidenceClass
    ) : CoreEvidence<T> {
        init {
            require(recordedAtMillis > 0L) { "Measurement timestamp required" }
            require(
                when (classification) {
                    EvidenceClass.MEASUREMENT -> source in setOf(
                        EvidenceSource.FUSED_GPS, EvidenceSource.VERIFIED_OBD)
                    EvidenceClass.PROVIDER_FORECAST -> source == EvidenceSource.PROVIDER
                    EvidenceClass.MODEL_ESTIMATE -> source == EvidenceSource.ENERGY_MODEL
                    EvidenceClass.USER_SCENARIO -> source == EvidenceSource.USER_INPUT
                }
            ) { "Evidence source does not match its classification" }
        }

        fun currentOrNull(nowMillis: Long, maxAgeMillis: Long): T? {
            require(maxAgeMillis >= 0L)
            if (nowMillis < recordedAtMillis) return null
            return value.takeIf { nowMillis - recordedAtMillis <= maxAgeMillis }
        }

        fun isStale(nowMillis: Long, maxAgeMillis: Long): Boolean =
            currentOrNull(nowMillis, maxAgeMillis) == null
    }

    data class Unavailable(val issue: CoreIssue) : CoreEvidence<Nothing>
}

enum class CoreIssue(val messageTr: String) {
    LOCATION_MISSING("Konum alınamıyor. Konum iznini ve GPS'i kontrol edin."),
    NETWORK_UNAVAILABLE("Ağ bağlantısı kullanılamıyor."),
    PROVIDER_ERROR("Uzak hizmet yanıt veremedi. Daha sonra yeniden deneyin."),
    OBD_DISCONNECTED("ELM327 bağlantısı yok. Batarya ölçümleri kullanılamıyor."),
    DATA_STALE("Verinin güncelliği doğrulanamadı. Son ölçüm kullanılamıyor."),
    CONFIG_UNCONFIRMED("Araç veya hizmet ayarları kullanıcı tarafından teyit edilmedi."),
    UNKNOWN("Veri şu anda kullanılamıyor.");

    companion object {
        fun fromHttpStatus(status: Int): CoreIssue = when (status) {
            408, 429 -> PROVIDER_ERROR
            in 500..599 -> PROVIDER_ERROR
            else -> PROVIDER_ERROR
        }
    }
}
