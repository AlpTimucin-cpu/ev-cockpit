package com.evcockpit.data.traffic

import android.content.Context
import com.evcockpit.diagnostics.DiagnosticsHub
import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.model.RouteSnapshot
import com.evcockpit.domain.usecase.TrafficFailure
import com.evcockpit.domain.usecase.RouteProgressTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.YearMonth
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.min

/**
 * Local cap lower than documented free monthly request counts.
 * Limits are conservative safeguards, not a guarantee about the owner's plan.
 */
internal object TomTomQuotaLimits {
    const val INCIDENTS_PER_MONTH = 2_000
    const val FLOW_TILES_PER_MONTH = 80_000
    fun allowed(previous: Int, limit: Int): Boolean =
        previous >= 0 && previous < limit
}

/** Persistent monthly request meter. Counts failures too; never stores coordinates or keys. */
internal class TomTomLocalMeter(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "evc_tomtom_traffic_monthly_requests_v1", Context.MODE_PRIVATE
    )
    @Synchronized fun reserve(kind: String, maximum: Int): Boolean {
        require(kind in setOf("incident", "tile"))
        val month = YearMonth.now(ZoneOffset.UTC).toString()
        val key = month + "_" + kind
        val used = prefs.getInt(key, 0)
        if (!TomTomQuotaLimits.allowed(used, maximum)) return false
        // Retain one month of counts; changing/deleting the API key does not reset quota.
        return prefs.edit().putInt(key, used + 1).commit()
    }
}
internal class TomTomLocalQuotaExceeded : IOException("Local traffic request budget exhausted")

/**
 * One active 5-minute route traffic snapshot shared between phone and Android Auto.
 * Strictly no background location polling, no vehicle settings/UDS writes.
 */
@Singleton
class TomTomTrafficRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val vault: TomTomCredentialVault,
    private val diagnostics: DiagnosticsHub
) {
    private val client = TomTomTrafficHttp() // Always HTTPS api.tomtom.com, no key in URL.
    private val meter = TomTomLocalMeter(context)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(TomTomTrafficState())
    val state: StateFlow<TomTomTrafficState> = _state.asStateFlow()
    private var lastRouteId: String? = null
    private var lastAttemptMs: Long = 0L
    private var backoffUntilMs: Long = 0L

    suspend fun refresh(route: RouteSnapshot?, fix: GpsFix?, manual: Boolean = false):
        TomTomTrafficState = mutex.withLock {
        val access = vault.access.value
        if (!access.enabled || !access.keyAvailable) {
            lastRouteId = null
            lastAttemptMs = 0L
            _state.value = TomTomTrafficState(failure =
                if (access.keyAvailable) TrafficFailure.PROVIDER_NOT_APPROVED
                else TrafficFailure.KEY_MISSING)
            return@withLock _state.value
        }
        val now = System.currentTimeMillis()
        val routeId = route?.routeId?.takeIf(String::isNotBlank)
        if (routeId == null || fix?.usable(now) != true || fix.accuracyMeters > 35f) {
            lastAttemptMs = 0L
            _state.value = TomTomTrafficState(routeId = routeId,
                failure = TrafficFailure.UNVERIFIED)
            return@withLock _state.value
        }
        // Require <=35m verified GPS/route projection. A different parallel
        // road is not considered a matching route.
        val matched = RouteProgressTracker().enrich(route, fix, now)
            ?.takeIf { it.validProgress() != null }
        val corridor = matched?.let { TomTomCorridorPolicy.from(it, fix, now) }
        if (corridor == null) {
            _state.value = TomTomTrafficState(routeId = routeId,
                failure = TrafficFailure.WRONG_ROUTE)
            return@withLock _state.value
        }
        if (lastRouteId != routeId) {
            lastRouteId = routeId
            lastAttemptMs = 0L
            backoffUntilMs = 0L
            _state.value = TomTomTrafficState(routeId = routeId)
        }
        if (now < backoffUntilMs) return@withLock _state.value
        val minInterval = if (manual) 60_000L else 5L * 60_000L
        if (lastAttemptMs > 0L && now >= lastAttemptMs &&
            now - lastAttemptMs < minInterval) return@withLock _state.value
        // A successful per-route cache is not a substitute for current GPS;
        // all source data is invalidated when 5 minutes old.
        val key = vault.requestKey()
        if (key == null || !vault.access.value.enabled) {
            _state.value = TomTomTrafficState(routeId = routeId,
                failure = TrafficFailure.KEY_MISSING)
            return@withLock _state.value
        }
        lastAttemptMs = now
        diagnostics.trafficAttempt(now)
        _state.value = TomTomTrafficState(routeId = routeId, loading = true)
        try {
            val fresh = withTimeout(45_000L) {
                withContext(Dispatchers.IO) {
                    if (!meter.reserve("incident", TomTomQuotaLimits.INCIDENTS_PER_MONTH))
                        throw TomTomLocalQuotaExceeded()
                    val response = client.incidents(key, corridor.bounds)
                    val serverMs = response.responseDateMs
                        ?: throw IOException("TomTom HTTP Date missing")
                    if (serverMs > now + 30_000L || now - serverMs > 5L * 60_000L)
                        throw IOException("TomTom server date stale")
                    val incidents = TomTomIncidentParser.parse(
                        response.bytes, corridor.routePoints
                    )
                    val tiles = mutableListOf<TomTomFlowTile>()
                    for (id in corridor.tileIds) {
                        if (!meter.reserve("tile", TomTomQuotaLimits.FLOW_TILES_PER_MONTH))
                            throw TomTomLocalQuotaExceeded()
                        val tile = client.flowTile(key, id)
                        val observed = tile.responseDateMs
                            ?: throw IOException("TomTom tile server date missing")
                        if (observed > now + 30_000L || now - observed > 5L * 60_000L)
                            throw IOException("TomTom tile server date stale")
                        if (!tile.bytes.isPng())
                            throw IOException("TomTom raster response is not a PNG")
                        tiles += TomTomFlowTile(id.z, id.x, id.y, tile.bytes, observed)
                    }
                    TomTomTrafficState(
                        routeId = routeId,
                        fetchedAtMillis = System.currentTimeMillis(),
                        serverAtMillis = min(serverMs, tiles.minOfOrNull { it.serverAtMillis }
                            ?: serverMs),
                        incidents = incidents,
                        flowTiles = tiles,
                        incidentRequestVerified = true
                    )
                }
            }
            // Consents and route may have changed during the outbound request:
            // re-read both access and current source binding before publication.
            if (!vault.access.value.enabled || lastRouteId != routeId)
                _state.value = TomTomTrafficState(failure = TrafficFailure.PROVIDER_NOT_APPROVED)
            else _state.value = fresh
            diagnostics.trafficFinished(_state.value)
            _state.value
        } catch (timeout: TimeoutCancellationException) {
            _state.value = TomTomTrafficState(
                routeId = routeId, failure = TrafficFailure.NETWORK_UNAVAILABLE
            )
            diagnostics.trafficFinished(_state.value)
            _state.value
        } catch (cancel: CancellationException) {
            _state.value = TomTomTrafficState(
                routeId = routeId, failure = TrafficFailure.UNVERIFIED
            )
            throw cancel
        } catch (error: Exception) {
            val failure = when (error) {
                is TomTomHttpException ->
                    when (error.status) {
                        401, 403 -> TrafficFailure.AUTH_FAILED
                        429 -> TrafficFailure.QUOTA_EXCEEDED
                        else -> TrafficFailure.HTTP_FAILURE
                    }
                is TomTomLocalQuotaExceeded -> TrafficFailure.BUDGET_EXHAUSTED
                is IOException -> TrafficFailure.NETWORK_UNAVAILABLE
                else -> TrafficFailure.UNVERIFIED
            }
            if (failure in setOf(TrafficFailure.QUOTA_EXCEEDED, TrafficFailure.BUDGET_EXHAUSTED,
                    TrafficFailure.AUTH_FAILED)) backoffUntilMs = now + 30L * 60_000L
            _state.value = TomTomTrafficState(routeId = routeId, failure = failure)
            diagnostics.trafficFinished(_state.value)
            _state.value
        }
    }

    private fun ByteArray.isPng(): Boolean =
        size >= 8 && this[0] == 0x89.toByte() &&
            this[1] == 80.toByte() && this[2] == 78.toByte() &&
            this[3] == 71.toByte() && this[4] == 13.toByte() &&
            this[5] == 10.toByte() && this[6] == 26.toByte() &&
            this[7] == 10.toByte()
}
