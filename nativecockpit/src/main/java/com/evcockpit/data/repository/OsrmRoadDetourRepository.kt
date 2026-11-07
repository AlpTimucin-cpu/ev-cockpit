package com.evcockpit.data.repository

import com.evcockpit.data.routing.OsrmDetourResponseParser
import com.evcockpit.data.routing.OsrmRequestFormat
import com.evcockpit.data.routing.OsrmService
import com.evcockpit.data.routing.RoutingEndpointPolicy
import com.evcockpit.data.routing.OsrmDemoRateLimiter
import com.evcockpit.BuildConfig
import com.evcockpit.data.settings.AppConfigRepository
import com.evcockpit.domain.model.*
import com.evcockpit.domain.usecase.BoundedNetworkRetry
import com.evcockpit.domain.usecase.DCCorridorCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Read-only OSRM inspection. Unlike OsrmRouteRepository.calculateVia(),
 * this never modifies the active route or selected alternatives.
 * Do not send all EPDK stations to a routing server.
 */
@Singleton
class OsrmRoadDetourRepository @Inject constructor(
    private val configuration: AppConfigRepository,
    private val demoLimiter: OsrmDemoRateLimiter
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(13, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .build()

    suspend fun inspect(
        route: RouteSnapshot,
        candidates: List<DCCorridorCandidate>
    ): RoadDetourCatalog = withContext(Dispatchers.IO) {
        val routeId = route.routeId ?: return@withContext RoadDetourCatalog(
            message = "Doğrulanmış OSRM rotası bulunamadı"
        )
        if (!validRouteGeometry(route.routePoints) ||
            route.totalKm?.takeIf { it.isFinite() && it in 0.05..5_000.0 } == null
        ) return@withContext RoadDetourCatalog(
            routeId = routeId, message = "OSRM güzergâhı geçersiz"
        )
        val chosen = selectCandidates(route, candidates)
        if (chosen.isEmpty()) return@withContext RoadDetourCatalog(
            routeId = routeId, message = "Rotaya 5 km yakın doğrulanabilir DC/CCS adayı yok"
        )
        val selected = RoutingEndpointPolicy.choose(
            configuration.configuration.first().endpoints, BuildConfig.DEBUG
        ) ?: return@withContext RoadDetourCatalog(
            routeId = routeId,
            candidates = chosen.map { it.station },
            message = "Karayolu sapması hesaplanamadı: OSRM rota servisi ayarlanmadı"
        )
        val api = Retrofit.Builder().baseUrl(selected.root)
            .client(OsrmDemoRateLimiter.client(http, selected.publicDemo)).build()
            .create(OsrmService::class.java)
        val results = mutableListOf<RoadDetour>()
        for (candidate in chosen) {
            val endpoint = candidate.station.coordinate
            val request = OsrmRequestFormat.urlDetour(
                route.routePoints.first(), endpoint, route.routePoints.last()
            )
            try {
                val response = BoundedNetworkRetry.io(maxAttempts = 2) {
                    demoLimiter.execute(selected.publicDemo) { api.route(request) }
                }
                if (!response.isSuccessful) {
                    response.errorBody()?.close()
                    response.body()?.close()
                    continue
                }
                val body = response.body() ?: continue
                val evidence = body.use {
                    // Never download an unbounded OSRM payload into memory.
                    val source = it.source()
                    if (source.request(200_001L)) null else
                        OsrmDetourResponseParser.parse(
                            source.readUtf8(), routeId, candidate.station.id,
                            requireNotNull(route.totalKm), route.osrmDurationSeconds,
                            System.currentTimeMillis()
                        )
                }
                if (evidence != null) results += evidence
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: IOException) {
                // Fail closed per station. The rest may still be available.
            } catch (_: RuntimeException) {
                // Malformed server payload must not become a fake detour estimate.
            }
        }
        RoadDetourCatalog(
            routeId = routeId,
            checked = results,
            candidates = chosen.map { it.station },
            message = when {
                results.isEmpty() -> "OSRM istasyonlara karayolu sapmasını doğrulayamadı"
                results.size < chosen.size -> "Bazı istasyonların karayolu sapması doğrulanamadı"
                else -> null
            }
        )
    }

    companion object {
        /** At most six evenly distributed CCS stops (real road calls are bounded). */
        internal fun selectCandidates(
            route: RouteSnapshot,
            input: List<DCCorridorCandidate>
        ): List<DCCorridorCandidate> {
            val total = route.totalKm ?: return emptyList()
            if (!total.isFinite() || total <= 20.0) return emptyList()
            val valid = input.asSequence()
                .filter {
                    it.station.id.isNotBlank() && it.station.hasDcCcs &&
                    it.station.coordinate.valid() && it.distanceFromStartKm.isFinite() &&
                    it.distanceFromStartKm in 10.0..(total - 10.0)
                }
                .distinctBy { it.station.id }
                .sortedBy { it.distanceFromStartKm }
                .toList()
            if (valid.size <= 6) return valid
            val targets = (1..6).map { (it.toDouble() / 7.0) * total }
            val remaining = valid.toMutableList()
            val picked = mutableListOf<DCCorridorCandidate>()
            for (target in targets) {
                val item = remaining.minWithOrNull(
                    compareBy<DCCorridorCandidate> {
                        abs(it.distanceFromStartKm - target)
                    }.thenBy { it.station.id }
                ) ?: break
                picked += item
                remaining.remove(item)
            }
            return picked.sortedBy { it.distanceFromStartKm }
        }
    }
}
