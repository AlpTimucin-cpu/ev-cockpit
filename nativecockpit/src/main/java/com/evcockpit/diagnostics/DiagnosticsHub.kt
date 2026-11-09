package com.evcockpit.diagnostics

import com.evcockpit.BuildConfig
import com.evcockpit.data.obd.MebUdsParser
import com.evcockpit.domain.model.ChargingCatalog
import com.evcockpit.domain.model.GpsFix
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
    CHARGING_UPDATED("EPDK katalog durumu değişti")
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
    val chargeSnapshotUtc: String? = null,
    val chargingStatus: String = "Kullanılamıyor",
    val obdStatus: String = "DISCONNECTED",
    val obdRawMasked: String = "Kullanılamıyor",
    val carReattachments: Int = 0
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
    fun weatherFinished(success: Boolean, atMillis: Long = System.currentTimeMillis()) {
        if (!BuildConfig.DEBUG) return
        mutable.value = mutable.value.copy(
            weatherReceivedMillis = if (success) atMillis else mutable.value.weatherReceivedMillis,
            weatherOutcome = if (success) "Başarılı (saatlik TAHMİN)" else "Başarısız"
        )
        event(if (success) DiagnosticEvent.WEATHER_SUCCEEDED
              else DiagnosticEvent.WEATHER_FAILED, atMillis)
    }

    @Synchronized
    fun charging(catalog: ChargingCatalog) {
        if (!BuildConfig.DEBUG) return
        val stamp = catalog.verifiedAtUtc?.takeIf { safeUtc.matches(it) }
        val outcome = when {
            catalog.loading -> "Yükleniyor"
            catalog.staleCache -> "Önbellek / güncel değil"
            catalog.error != null -> "Yenileme başarısız"
            stamp != null -> "EPDK anlık doluluk verisi değil"
            else -> "Kullanılamıyor"
        }
        val before = mutable.value
        if (before.chargeSnapshotUtc == stamp && before.chargingStatus == outcome) return
        mutable.value = before.copy(chargeSnapshotUtc = stamp, chargingStatus = outcome)
        event(DiagnosticEvent.CHARGING_UPDATED)
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
        event(DiagnosticEvent.OBD_READ_OBSERVED)
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
