package com.evcockpit.diagnostics

import com.evcockpit.BuildConfig
import com.evcockpit.data.obd.MebUdsParser
import com.evcockpit.domain.model.ChargingCatalog
import com.evcockpit.domain.model.GpsFix
import com.evcockpit.data.traffic.TomTomTrafficState
import com.evcockpit.domain.model.ObdConnectionState
import com.evcockpit.domain.model.MebDiagnosticReport
import java.time.Instant
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** No arbitrary Logcat, URL, exception, Bluetooth MAC or coordinate can enter this vocabulary. */
enum class DiagnosticEvent(val title: String) {
    OPENED("Tanılama açıldı"),
    GPS_RECEIVED("Gerçek GPS alındı; koordinatlar kaydedilmedi"),
    CAR_ATTACHED("Android Auto Car Screen aktif"),
    CAR_DETACHED("Android Auto Car Screen kapandı"),
    CAR_RESUMED("Android Auto navigasyon oturumu yeniden bağlandı"),
    SURFACE_READY("Car Map yüzeyi güncellendi"),
    SURFACE_CLOSED("Car Map yüzeyi kapandı"),
    LOCATION_FGS_STARTED("Navigasyon konum servisi önde"),
    LOCATION_FGS_STOPPED("Navigasyon konum servisi durdu"),
    OBD_FGS_STARTED("OBD bağlı cihaz servisi önde"),
    OBD_FGS_STOPPED("OBD bağlı cihaz servisi durdu"),
    OBD_PHASE_CHANGED("OBD bağlantı durumu değişti"),
    OBD_READ_OBSERVED("Salt-okunur OBD tanılama yanıtı alındı"),
    WEATHER_STARTED("Rota hava tahmini yenilemesi denendi"),
    WEATHER_SUCCEEDED("Rota hava tahmini alındı"),
    WEATHER_FAILED("Rota hava tahmini yenilemesi başarısız"),
    CHARGING_UPDATED("EPDK katalog durumu değişti"),
    CHARGING_FETCH_STARTED("EPDK snapshot indirme denendi"),
    CHARGING_FETCH_OK("EPDK tarihli snapshot doğrulandı"),
    TRAFFIC_STARTED("TomTom trafik isteği denendi"),
    TRAFFIC_SUCCESS("TomTom gerçek trafik yanıtı alındı"),
    TRAFFIC_FAILED("TomTom trafik bilgisi kullanılamıyor")
}

data class DiagnosticsSnapshot(
    val entries: List<String> = emptyList(),
    val gpsAtMillis: Long? = null,
    val gpsAccuracyMeters: Float? = null,
    /** Car Screen lifecycle evidence, not a claim about a physical USB connection. */
    val carScreenActive: Boolean? = null,
    val mapWidthPx: Int? = null,
    val mapHeightPx: Int? = null,
    val mapDpi: Int? = null,
    val mapInsetsPx: String? = null,
    val locationFgsActive: Boolean = false,
    val obdFgsActive: Boolean = false,
    val weatherAttemptMillis: Long? = null,
    val weatherReceivedMillis: Long? = null,
    val weatherOutcome: String = "Kullanılamıyor",
    /** Safe model UTC instant only: no coordinate, station, route ID or account data. */
    val weatherCurrentModelUtc: String? = null,
    val weatherRoutePointCount: Int? = null,
    val chargeSnapshotUtc: String? = null,
    val chargingStatus: String = "Kullanılamıyor",
    val chargeDownloadAttemptMillis: Long? = null,
    val chargeDownloadSuccessMillis: Long? = null,
    val chargeNationalRowsCount: Int? = null,
    val chargePublicRowsCount: Int? = null,
    val obdStatus: String = "DISCONNECTED",
    val obdRawMasked: String = "Kullanılamıyor",
    val carReattachments: Int = 0,
    val trafficAttemptMillis: Long? = null,
    val trafficReceivedMillis: Long? = null,
    val trafficServerMillis: Long? = null,
    val trafficStatus: String = "Trafik: kullanılamıyor",
    val trafficIncidentsCount: Int? = null,
    val trafficFlowTilesCount: Int? = null
)

/**
 * Application-owned debug-only, volatile 500-event buffer. Never system logcat.
 * The Release build compiles a no-op store: no events or telemetry retained.
 */
@Singleton
class DiagnosticsHub @Inject constructor() {
    companion object {
        const val MAX_LINES = 500
        private val safeUtc = Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z""")
        private val hex = Regex("[A-F0-9]{8,512}")
    }
    private val mode01Response = Regex("""(?i)(?:^|[^A-F0-9])41\s*0D\s*([A-F0-9]{2})(?![A-F0-9])""")
    private val ring = ArrayDeque<String>(MAX_LINES)
    private var lastSpeedLogMillis = 0L
    private val mutable = MutableStateFlow(DiagnosticsSnapshot())
    val snapshot: StateFlow<DiagnosticsSnapshot> = mutable.asStateFlow()

    @Synchronized
    fun event(event: DiagnosticEvent, atMillis: Long = System.currentTimeMillis()) {
        if (!BuildConfig.DEBUG) return
        val stamp = java.time.format.DateTimeFormatter.ISO_INSTANT
            .format(Instant.ofEpochMilli(atMillis))
        if (ring.size == MAX_LINES) ring.removeFirst()
        ring.addLast("$stamp | ${event.title}")
        mutable.value = mutable.value.copy(entries = ring.toList())
    }

    @Synchronized
    fun gps(fix: GpsFix?, nowMillis: Long = System.currentTimeMillis()) {
        if (!BuildConfig.DEBUG || fix?.usable(nowMillis) != true ||
            !fix.accuracyMeters.isFinite() || fix.accuracyMeters !in 0f..10_000f
        ) return
        val before = mutable.value
        if (before.gpsAtMillis != null && fix.recordedAtMillis <= before.gpsAtMillis) return
        mutable.value = before.copy(
            gpsAtMillis = fix.recordedAtMillis, gpsAccuracyMeters = fix.accuracyMeters
        )
        if (before.gpsAtMillis == null) event(DiagnosticEvent.GPS_RECEIVED)
    }

    @Synchronized
    fun carScreen(active: Boolean) {
        if (!BuildConfig.DEBUG || mutable.value.carScreenActive == active) return
        mutable.value = mutable.value.copy(carScreenActive = active)
        event(if (active) DiagnosticEvent.CAR_ATTACHED else DiagnosticEvent.CAR_DETACHED)
    }

    @Synchronized
    fun carReattached() {
        if (!BuildConfig.DEBUG) return
        mutable.value = mutable.value.copy(
            carReattachments = mutable.value.carReattachments + 1
        )
        event(DiagnosticEvent.CAR_RESUMED)
    }

    @Synchronized
    fun surface(
        widthPx: Int, heightPx: Int, dpi: Int,
        leftInset: Int?, topInset: Int?, rightInset: Int?, bottomInset: Int?
    ) {
        if (!BuildConfig.DEBUG || widthPx !in 1..20_000 ||
            heightPx !in 1..20_000 || dpi !in 1..2_000
        ) return
        val safeInsets = listOf(leftInset, topInset, rightInset, bottomInset)
            .takeIf { it.all { value -> value == null || value in 0..20_000 } }
            ?.joinToString(",") { value -> value?.toString() ?: "?" }
        val before = mutable.value
        val next = before.copy(mapWidthPx = widthPx, mapHeightPx = heightPx,
            mapDpi = dpi, mapInsetsPx = safeInsets)
        if (before == next) return
        mutable.value = next
        event(DiagnosticEvent.SURFACE_READY)
    }

    @Synchronized
    fun surfaceGone() {
        if (!BuildConfig.DEBUG || mutable.value.mapWidthPx == null) return
        mutable.value = mutable.value.copy(
            mapWidthPx = null, mapHeightPx = null, mapDpi = null, mapInsetsPx = null
        )
        event(DiagnosticEvent.SURFACE_CLOSED)
    }

    @Synchronized
    fun foregroundService(location: Boolean, active: Boolean) {
        if (!BuildConfig.DEBUG) return
        val before = mutable.value
        if (location) {
            if (before.locationFgsActive == active) return
            mutable.value = before.copy(locationFgsActive = active)
        } else {
            if (before.obdFgsActive == active) return
            mutable.value = before.copy(obdFgsActive = active)
        }
        event(when {
            location && active -> DiagnosticEvent.LOCATION_FGS_STARTED
            location -> DiagnosticEvent.LOCATION_FGS_STOPPED
            active -> DiagnosticEvent.OBD_FGS_STARTED
            else -> DiagnosticEvent.OBD_FGS_STOPPED
        })
    }

    @Synchronized
    fun weatherAttempt(atMillis: Long = System.currentTimeMillis()) {
        if (!BuildConfig.DEBUG) return
        mutable.value = mutable.value.copy(
            weatherAttemptMillis = atMillis, weatherOutcome = "Yenileme deneniyor"
        )
        event(DiagnosticEvent.WEATHER_STARTED, atMillis)
    }

    @Synchronized
    fun weatherFinished(
        success: Boolean,
        atMillis: Long = System.currentTimeMillis(),
        sourceKind: String = "saatlik TAHMİN"
    ) {
        if (!BuildConfig.DEBUG) return
        mutable.value = mutable.value.copy(
            weatherReceivedMillis = if (success) atMillis else mutable.value.weatherReceivedMillis,
            weatherOutcome = if (success) "Başarılı ($sourceKind)" else "Başarısız"
        )
        event(if (success) DiagnosticEvent.WEATHER_SUCCEEDED
              else DiagnosticEvent.WEATHER_FAILED, atMillis)
    }

    /** Only an allowlisted UTC timestamp and an integer in 0..8 may be exported. */
    @Synchronized
    fun weatherEvidence(currentModelUtc: String?, routePointCount: Int) {
        if (!BuildConfig.DEBUG || routePointCount !in 0..8) return
        val safeModelTime = currentModelUtc?.takeIf { safeUtc.matches(it) }
        mutable.value = mutable.value.copy(
            weatherCurrentModelUtc = safeModelTime,
            weatherRoutePointCount = routePointCount
        )
    }

    /** Debug-only fixed-status provenance; no key, route ID, URL, incident body or GPS. */
    @Synchronized
    fun trafficAttempt(atMs: Long = System.currentTimeMillis()) {
        if (!BuildConfig.DEBUG) return
        mutable.value = mutable.value.copy(
            trafficAttemptMillis = atMs, trafficStatus = "TomTom istek deneniyor"
        )
        event(DiagnosticEvent.TRAFFIC_STARTED, atMs)
    }

    @Synchronized
    fun trafficFinished(result: TomTomTrafficState) {
        if (!BuildConfig.DEBUG) return
        val success = result.failure == null && result.incidentRequestVerified &&
            result.fetchedAtMillis != null && result.serverAtMillis != null
        val label = if (success) "TomTom olay ve akış yanıtı alındı"
            else "Trafik: kullanılamıyor · " + (result.failure?.name ?: "UNVERIFIED")
        mutable.value = mutable.value.copy(
            trafficReceivedMillis = if (success) result.fetchedAtMillis
                else mutable.value.trafficReceivedMillis,
            trafficServerMillis = if (success) result.serverAtMillis else null,
            trafficStatus = label,
            trafficIncidentsCount = if (success) result.incidents.size.coerceIn(0,120) else null,
            trafficFlowTilesCount = if (success) result.flowTiles.size.coerceIn(0,6) else null
        )
        event(if (success) DiagnosticEvent.TRAFFIC_SUCCESS else DiagnosticEvent.TRAFFIC_FAILED)
    }

    /** Real download-start evidence. No implicit success timestamp. */
    @Synchronized
    fun chargingAttempt(atMillis: Long = System.currentTimeMillis()) {
        if (!BuildConfig.DEBUG) return
        mutable.value = mutable.value.copy(
            chargeDownloadAttemptMillis = atMillis,
            chargingStatus = "EPDK indiriliyor / kaynak doğrulanmadı"
        )
        event(DiagnosticEvent.CHARGING_FETCH_STARTED, atMillis)
    }

    @Synchronized
    fun charging(catalog: ChargingCatalog) {
        if (!BuildConfig.DEBUG) return
        val stamp = catalog.verifiedAtUtc?.takeIf { safeUtc.matches(it) }
        val fresh = !catalog.staleCache &&
            com.evcockpit.domain.usecase.EpdkProvenancePolicy.isFresh(
                catalog.verifiedAtUtc, System.currentTimeMillis())
        val outcome = when {
            catalog.error != null -> "Yenileme başarısız / kaynak eski veya geçersiz"
            catalog.staleCache || !fresh -> "Önbellek / eski veri (72 saat sınırı)"
            catalog.loading -> "Yükleniyor"
            fresh -> "EPDK yayın snapshot'ı · canlı doluluk içermez"
            else -> "Kullanılamıyor"
        }
        val before = mutable.value
        if (before.chargeSnapshotUtc == stamp && before.chargingStatus == outcome &&
            before.chargeDownloadSuccessMillis == catalog.downloadSuccessAtMillis &&
            before.chargeNationalRowsCount == catalog.sourceRecordCount &&
            before.chargePublicRowsCount == catalog.publicRecordCount) return
        mutable.value = before.copy(
            chargeSnapshotUtc = stamp,
            chargingStatus = outcome,
            chargeDownloadSuccessMillis = catalog.downloadSuccessAtMillis
                ?: before.chargeDownloadSuccessMillis,
            chargeNationalRowsCount = catalog.sourceRecordCount,
            chargePublicRowsCount = catalog.publicRecordCount
        )
        event(DiagnosticEvent.CHARGING_UPDATED)
        if (catalog.downloadSuccessAtMillis != null &&
            before.chargeDownloadSuccessMillis != catalog.downloadSuccessAtMillis)
            event(DiagnosticEvent.CHARGING_FETCH_OK)
    }

    @Synchronized
    fun obd(connection: ObdConnectionState) {
        if (!BuildConfig.DEBUG) return
        val before = mutable.value
        val masked = connection.diagnosticResult?.let(::maskReadOnlyObd)
            ?: if (connection.phase == com.evcockpit.domain.model.ObdPhase.CONNECTED)
                before.obdRawMasked else "Kullanılamıyor"
        val phase = connection.phase.name
        if (phase == before.obdStatus && masked == before.obdRawMasked) return
        mutable.value = before.copy(obdStatus = phase, obdRawMasked = masked)
        if (phase != before.obdStatus) event(DiagnosticEvent.OBD_PHASE_CHANGED)
        if (masked != before.obdRawMasked && masked != "Kullanılamıyor") {
            event(DiagnosticEvent.OBD_READ_OBSERVED)
        }
    }

    /**
     * Store only the three-byte SAE Mode 01 PID 0D response after an existing
     * ElmSpeedParser success. No arbitrary serial frames, adapter identifiers,
     * Bluetooth addresses, or full raw UART transcript.
     */
    @Synchronized
    fun obdSpeedResponse(raw: String) {
        if (!BuildConfig.DEBUG) return
        val speedByte = mode01Response.find(raw.take(512))
            ?.groupValues?.getOrNull(1)?.uppercase() ?: return
        val safe = "010D / 410D" + speedByte
        if (mutable.value.obdRawMasked == safe) return
        mutable.value = mutable.value.copy(obdRawMasked = safe)
        val now = System.currentTimeMillis()
        if (now - lastSpeedLogMillis >= 30_000L) {
            lastSpeedLogMillis = now
            event(DiagnosticEvent.OBD_READ_OBSERVED, now)
        }
    }

    /** Only allowlisted read-only 0x22 UDS responses, at most eight hex bytes. */
    fun maskReadOnlyObd(report: MebDiagnosticReport?): String {
        if (!BuildConfig.DEBUG || report == null ||
            report.request !in MebUdsParser.ALLOWED_REQUESTS
        ) return "Kullanılamıyor"
        val payload = report.payloadHex?.uppercase()?.takeIf { hex.matches(it) }
            ?: return "Kullanılamıyor"
        val suffix = if (payload.length > 16) "… [maskeli]" else ""
        return report.request + " / " + report.status.name + " / " +
            payload.take(16) + suffix
    }

    /** Fixed event labels only, so no secrets even if export is shared. */
    @Synchronized
    fun exportText(): String {
        if (!BuildConfig.DEBUG) return "Tanılama yalnız Debug APK sürümünde kullanılabilir."
        return buildString {
            appendLine("EV Cockpit Debug tanılama")
            appendLine("Kayıtlar: " + mutable.value.entries.size + "/" + MAX_LINES)
            appendLine("Koordinatlar, kimlikler, anahtarlar ve URL'ler kaydedilmez.")
            appendLine("---")
            mutable.value.entries.forEach { appendLine(it) }
        }
    }
}
