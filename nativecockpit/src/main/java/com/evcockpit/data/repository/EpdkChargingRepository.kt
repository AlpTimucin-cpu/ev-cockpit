package com.evcockpit.data.repository

import android.content.Context
import androidx.core.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.evcockpit.data.remote.EpdkService
import com.evcockpit.data.remote.EpdkSnapshotParser
import com.evcockpit.domain.model.ChargingCatalog
import com.evcockpit.domain.repository.ChargingRepository
import com.evcockpit.domain.usecase.EpdkProvenancePolicy
import com.evcockpit.diagnostics.DiagnosticsHub

/**
 * The public GitHub .gz is an EPDK-derived export, NOT a live EPDK request.
 * Preserves the true source export stamp and separately tracks the actual download.
 */
@Singleton
class EpdkChargingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: EpdkService,
    private val diagnostics: DiagnosticsHub
) : ChargingRepository {
    private val mutex = Mutex()
    private val _catalog = MutableStateFlow(ChargingCatalog())
    override val catalog: StateFlow<ChargingCatalog> = _catalog.asStateFlow()
    private val cache: File get() = File(context.cacheDir, "eya_epdk_public_v1.json.gz")

    override suspend fun refresh() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val attemptMs = System.currentTimeMillis()
            diagnostics.chargingAttempt(attemptMs)
            _catalog.value = _catalog.value.copy(
                loading = true, error = null, downloadAttemptAtMillis = attemptMs
            )
            diagnostics.charging(_catalog.value)

            // Cache may be old. Do not rewrite publisher verification date to "now".
            val cached = runCatching {
                if (cache.isFile) EpdkSnapshotParser.parseGzip(
                    cache.inputStream().use {
                        EpdkSnapshotParser.readBounded(it, EpdkSnapshotParser.MAX_GZIP_BYTES)
                    }
                ) else null
            }.getOrNull()
            if (cached != null) {
                _catalog.value = ChargingCatalog(
                    stations = cached.stations,
                    verifiedAtUtc = cached.verifiedAtUtc,
                    loading = true,
                    staleCache = true, // cached != successful current network download
                    error = if (!EpdkProvenancePolicy.isFresh(
                            cached.verifiedAtUtc, attemptMs))
                        "EPDK önbellek kaynağı 72 saatten eski" else null,
                    sourceUrl = cached.sourceUrl,
                    sourceRecordCount = cached.geocodedRowCount,
                    publicRecordCount = cached.publicRowCount,
                    downloadAttemptAtMillis = attemptMs
                )
                diagnostics.charging(_catalog.value)
            }
            try {
                val compressed = service.getPublicStations().use {
                    EpdkSnapshotParser.readBounded(
                        it.byteStream(), EpdkSnapshotParser.MAX_GZIP_BYTES
                    )
                }
                val result = EpdkSnapshotParser.parseGzip(compressed)
                val receivedMs = System.currentTimeMillis()
                // The actual server may continue serving a month-old snapshot.
                // A successful HTTP call must never refresh its source timestamp.
                if (!EpdkProvenancePolicy.isFresh(result.verifiedAtUtc, receivedMs)) {
                    throw IOException("EPDK snapshot verified date exceeds 72 hours")
                }
                // Android AtomicFile keeps a backup on interrupted replacement.
                val file = AtomicFile(cache)
                val output = file.startWrite()
                try {
                    output.write(compressed)
                    file.finishWrite(output)
                } catch (error: Exception) {
                    file.failWrite(output)
                    throw error
                }
                _catalog.value = ChargingCatalog(
                    stations = result.stations,
                    verifiedAtUtc = result.verifiedAtUtc,
                    loading = false, staleCache = false,
                    sourceUrl = result.sourceUrl,
                    sourceRecordCount = result.geocodedRowCount,
                    publicRecordCount = result.publicRowCount,
                    downloadAttemptAtMillis = attemptMs,
                    downloadSuccessAtMillis = receivedMs
                )
                diagnostics.charging(_catalog.value)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val prior = _catalog.value
                _catalog.value = prior.copy(
                    loading = false,
                    staleCache = true,
                    error = "EPDK kaynağı doğrulanamadı: " + when (error) {
                        is IOException -> "kaynak eski veya ağ erişilemedi"
                        else -> "şema/bütünlük hatası"
                    }
                )
                diagnostics.charging(_catalog.value)
            }
        }
    }
}
