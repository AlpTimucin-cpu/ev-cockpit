package com.evcockpit.data.radio

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Singleton
class RadioRepository @Inject constructor(private val api: RadioBrowserApi) {
    /** Cold stream; each refresh fetches current radio listings and wraps failures. */
    fun stations(): Flow<Result<List<RadioStation>>> = flow {
        var candidates = playableStations(api.stations())
        // A larger second request helps when the most popular streams are HTTP-only.
        if (candidates.isEmpty()) candidates = playableStations(api.stations(limit = 300))
        if (candidates.isEmpty()) throw IllegalStateException("Uyumlu HTTPS radyo bulunamadı")
        emit(Result.success(candidates))
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(Result.failure(error))
    }.flowOn(Dispatchers.IO)

    companion object {
        // AndroidManifest usesCleartextTraffic=false: never silently upgrade an
        // HTTP-only stream to HTTPS or relax app-wide network security.
        internal fun playableStations(stations: List<RadioStation>): List<RadioStation> =
            stations.asSequence()
                .filter {
                    !it.id.isNullOrBlank() && !it.name.isNullOrBlank() &&
                        it.urlResolved?.toHttpUrlOrNull()?.isHttps == true
                }
                .sortedWith(compareByDescending<RadioStation> { it.clickCount }.thenByDescending { it.votes })
                .distinctBy { it.urlResolved }
                .distinctBy { it.id }
                .toList()
    }
}
