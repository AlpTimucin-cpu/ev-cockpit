package com.evcockpit.mediahub

import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * EPDK Șarj@TR 1.1.8 capability parity built WITHOUT copying the proprietary
 * APK's images, code, database, API access token or hidden service protocol.
 *
 * Official station/connector metadata is supplied by the EPDK public snapshot.
 * Live socket occupancy remains UNKNOWN until an authorized source is attached.
 * Source/time validation prevents stale "available" status and price claims.
 */
object ChargingStationDiscovery {
    enum class Connector { ALL, CCS, TYPE2, CHADEMO, OTHER }
    enum class Availability { AVAILABLE, IN_USE, RESERVED, FAULT,
        MAINTENANCE, OUT_OF_HOURS, UNKNOWN }
    data class Filter(
        val operator: String = "",
        val text: String = "",
        val connector: Connector = Connector.ALL,
        val onlyPublic: Boolean = true
    )
    data class TimedTariff(
        val socketId: String,
        val amountTlPerKwh: Double,
        val effectiveFrom: Instant,
        val effectiveUntil: Instant,
        val fetchedAt: Instant,
        val source: String
    ) {
        fun currentlyApplicable(now: Instant): Boolean =
            amountTlPerKwh.isFinite() && amountTlPerKwh in 0.0..200.0 &&
                effectiveUntil.isAfter(effectiveFrom) &&
                !now.isBefore(effectiveFrom) && now.isBefore(effectiveUntil) &&
                !fetchedAt.isAfter(now) &&
                Duration.between(fetchedAt, now) <= Duration.ofMinutes(30) &&
                source.isNotBlank()
    }
    data class SocketAvailability(
        val stationId: String, val socketId: String,
        val status: Availability, val observedAt: Instant,
        val providerId: String
    ) {
        fun current(now: Instant): Availability {
            if (providerId.isBlank() || observedAt.isAfter(now) ||
                Duration.between(observedAt, now) > Duration.ofSeconds(120))
                return Availability.UNKNOWN
            return status
        }
    }

    fun connectorOf(type: String, kind: String): Connector {
        val normalized = (type + " " + kind).uppercase(Locale.ROOT)
            .replace("İ", "I").replace("Ç", "C")
        return when {
            normalized.contains("CHADEMO") -> Connector.CHADEMO
            normalized.contains("CCS") || normalized.contains("COMBO") -> Connector.CCS
            normalized.contains("TYPE 2") || normalized.contains("TYPE2") ||
                normalized.contains("TIP 2") || normalized.contains("TIP2") -> Connector.TYPE2
            else -> Connector.OTHER
        }
    }

    fun filter(entries: List<EpdkPublicStations.Entry>, filter: Filter):
            List<EpdkPublicStations.Entry> {
        val q = filter.text.trim()
        val operator = filter.operator.trim()
        return entries.filter { station ->
            station.stationId.isNotBlank() &&
                (operator.isEmpty() ||
                    station.operator.contains(operator, ignoreCase = true)) &&
                (q.isEmpty() || station.name.contains(q, ignoreCase = true) ||
                    station.address.contains(q, ignoreCase = true) ||
                    station.operator.contains(q, ignoreCase = true)) &&
                (filter.connector == Connector.ALL || station.sockets.any {
                    connectorOf(it.type, it.kind) == filter.connector
                })
        }
    }

    fun statusLabel(status: Availability): String = when(status) {
        Availability.AVAILABLE -> "Müsait"
        Availability.IN_USE -> "Kullanımda"
        Availability.RESERVED -> "Rezerve"
        Availability.FAULT -> "Arıza"
        Availability.MAINTENANCE -> "Bakım"
        Availability.OUT_OF_HOURS -> "Mesai saatleri dışında"
        Availability.UNKNOWN -> "Bilinmiyor • canlı veri yok"
    }

    fun socketDetails(station: EpdkPublicStations.Entry): String = buildString {
        append("EPDK resmî istasyon no: ").append(station.stationId).append('\n')
        append("Lisans no: ").append(station.operatorLicense.ifBlank { "Belirtilmemiş" }).append('\n')
        if(station.brand.isNotBlank())
            append("Marka: ").append(station.brand).append('\n')
        if(station.stationOperator.isNotBlank())
            append("İstasyon işletmecisi: ").append(station.stationOperator).append('\n')
        if(station.greenStationRaw.isNotBlank())
            append("Yeşil istasyon (EPDK ham değeri): ")
                .append(station.greenStationRaw).append('\n')
        if(station.distributionCompany.isNotBlank())
            append("Dağıtım şirketi: ").append(station.distributionCompany).append('\n')
        append("Adres: ").append(station.address.ifBlank { "Belirtilmemiş" }).append('\n')
        append("Soket sayısı: ").append(station.sockets.size).append('\n')
        station.sockets.take(24).forEach { socket ->
            append("\n• Soket ").append(socket.id.ifBlank { "?" })
            append(" · ").append(socket.type.ifBlank { socket.kind.ifBlank { "Tür bilinmiyor" } })
            if(socket.kind.isNotBlank()) append(" · ").append(socket.kind)
            if(socket.rawPower.isNotBlank())
                append(" · EPDK güç alanı: ").append(socket.rawPower)
            append("\n  Durum: ").append(statusLabel(Availability.UNKNOWN))
            append("\n  Sokete özgü güncel fiyat: Doğrulanmadı")
        }
        append("\n\nCanlı durum, fiili ödeme tutarı ve rezervasyon operatörün " +
            "yetkili servisiyle doğrulanmadan kullanılmaz.")
    }
}
