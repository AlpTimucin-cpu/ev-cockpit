package com.evcockpit.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
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
import com.evcockpit.diagnostics.DiagnosticsHub

@Singleton
class EpdkChargingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: EpdkService,
    private val diagnostics: DiagnosticsHub
): ChargingRepository {
    private val mutex=Mutex()
    private val _catalog=MutableStateFlow(ChargingCatalog())
    override val catalog: StateFlow<ChargingCatalog> = _catalog.asStateFlow()
    private val cache: File get()=File(context.cacheDir,"eya_epdk_public_v1.json.gz")

    override suspend fun refresh() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val previous=_catalog.value
            _catalog.value=previous.copy(loading=true,error=null)
            diagnostics.charging(_catalog.value)
            // Immediately publish cached source WITH original snapshot date.
            val cached=runCatching { if(cache.isFile) EpdkSnapshotParser.parseGzip(
                cache.inputStream().use { EpdkSnapshotParser.readBounded(it,EpdkSnapshotParser.MAX_GZIP_BYTES) }) else null
            }.getOrNull()
            if(cached!=null) {
                _catalog.value=ChargingCatalog(cached.stations,cached.verifiedAtUtc,
                    loading=true,staleCache=true)
                diagnostics.charging(_catalog.value)
            }
            try {
                val network=service.getPublicStations().use {
                    EpdkSnapshotParser.readBounded(it.byteStream(),EpdkSnapshotParser.MAX_GZIP_BYTES)
                }
                val result=EpdkSnapshotParser.parseGzip(network)
                // Atomic replacement; preserve previous cache if network is corrupt.
                val temp=File(cache.parentFile,cache.name+".new")
                try {
                    temp.writeBytes(network)
                    check(temp.renameTo(cache)) { "Could not update the station cache" }
                } finally { temp.delete() }
                _catalog.value=ChargingCatalog(result.stations,result.verifiedAtUtc,loading=false)
                diagnostics.charging(_catalog.value)
            } catch(e: Exception) {
                val existing=_catalog.value
                _catalog.value=existing.copy(loading=false,staleCache=existing.stations.isNotEmpty(),
                    error="Could not refresh EPDK · ${e.javaClass.simpleName}")
                diagnostics.charging(_catalog.value)
            }
        }
        Unit
    }
}
