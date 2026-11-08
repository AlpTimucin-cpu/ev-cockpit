package com.evcockpit.data.repository

import com.evcockpit.BuildConfig

import com.google.gson.JsonParser
import com.evcockpit.data.settings.AppConfigRepository
import com.evcockpit.domain.usecase.BoundedNetworkRetry
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.evcockpit.data.routing.*
import com.evcockpit.domain.model.*
import com.evcockpit.domain.repository.RouteRepository

@Singleton
class OsrmRouteRepository @Inject constructor(
    private val appConfig: AppConfigRepository
) : RouteRepository {
    private val http = OkHttpClient.Builder()
        .connectTimeout(9, TimeUnit.SECONDS)
        .readTimeout(17, TimeUnit.SECONDS)
        .callTimeout(22, TimeUnit.SECONDS)
        .build()
    private fun osrm(root: String): OsrmService = Retrofit.Builder()
        .baseUrl(root).client(http).build().create(OsrmService::class.java)

    private fun photon(root: String): PhotonService = Retrofit.Builder()
        .baseUrl(root).client(http).build().create(PhotonService::class.java)

    private val nominatim = NominatimGeocoder(http)
    private val selected = MutableStateFlow<RouteSnapshot?>(null)
    private val variants = MutableStateFlow<List<RouteOption>>(emptyList())
    private val index = MutableStateFlow<Int?>(null)
    private val demoGate = DebugDemoRequestGate()
    override val currentRoute: StateFlow<RouteSnapshot?> = selected.asStateFlow()
    override val options: StateFlow<List<RouteOption>> = variants.asStateFlow()
    override val selectedIndex: StateFlow<Int?> = index.asStateFlow()

    override suspend fun search(query: String): List<RoutePlace> = withContext(Dispatchers.IO) {
        require(query.trim().length in 3..100) { "Adres için en az üç karakter yazın" }
        val root = appConfig.configuration.first().endpoints.geocodingBaseUrl
        // Preserve users who explicitly configured Photon in FAZ 1.
        // Public Nominatim is NEVER used from automatic text-change suggestions.
        if (!java.net.URI(root).host.equals("photon.komoot.io", ignoreCase = true)) {
            return@withContext nominatim.lookup(root, query.trim())
        }
        val result = BoundedNetworkRetry.io {
            photon(root).search(query.trim())
        }
        if (!result.isSuccessful) {
            result.errorBody()?.close()
            throw IOException(when (result.code()) {
                400 -> "Adres arama isteği reddedildi (HTTP 400)."
                429 -> "Adres servisinde istek sınırına ulaşıldı (HTTP 429)."
                in 500..599 -> "Adres servisi kullanılamıyor (HTTP ${result.code()})."
                else -> "Adres servisi hata verdi (HTTP ${result.code()})."
            })
        }
        val body = result.body() ?: throw IOException("Adres servisi boş yanıt döndürdü")
        body.use { PhotonResponseParser.parse(it.string()) }
    }

    override suspend fun calculate(
        origin: GeoCoordinate, destination: RoutePlace
    ): RouteCalculationResult = calculateVia(origin, destination, emptyList())

    override suspend fun calculateVia(
        origin: GeoCoordinate,
        destination: RoutePlace,
        stops: List<GeoCoordinate>
    ): RouteCalculationResult = withContext(Dispatchers.IO) {
        // A road route does not require BMS SoC, usable capacity or a
        // confirmed energy scenario. Only a valid HTTPS road-router is needed.
        val endpoint = OsrmEndpointSelector.choose(
            appConfig.configuration.first().endpoints, BuildConfig.DEBUG
        ) ?: return@withContext RouteCalculationResult.Failure(
            null, "Rota servisi seçilmedi. Ayarlar bölümünden kendi HTTPS " +
                "OSRM adresini girin veya TEST sürümünde deneme rotasını açın."
        )
        if (endpoint.demo && !demoGate.accept(System.currentTimeMillis())) {
            return@withContext RouteCalculationResult.Failure(
                null, "Deneme rota servisi için 15 saniyelik istek aralığını bekleyin."
            )
        }
        val root = endpoint.baseUrl
        val url = try {
            OsrmRequestFormat.urlVia(origin, stops, destination.coordinate)
        } catch (error: IllegalArgumentException) {
            return@withContext RouteCalculationResult.Failure(null, error.message ?: "Coordinate error")
        }
        try {
            // Shared public demo: exactly one request, no automatic retries.
            val response = BoundedNetworkRetry.io(
                maxAttempts = if (endpoint.demo) 1 else 2
            ) { osrm(root).route(url) }
            if (!response.isSuccessful) {
                val info = response.errorBody()?.use { body ->
                    val bytes = body.bytes()
                    if (bytes.size > 4_096) null else parseErrorCode(bytes.toString(Charsets.UTF_8))
                }
                response.body()?.close()
                return@withContext RouteCalculationResult.Failure(
                    response.code(), explainStatus(response.code(), info)
                )
            }
            val body = response.body()
                ?: return@withContext RouteCalculationResult.Failure(null, "OSRM boş yanıt döndürdü")
            val alternatives = try {
                body.use { OsrmResponseParser.parse(it.string(), destination.label) }
            } catch (error: RuntimeException) {
                return@withContext RouteCalculationResult.Failure(null,
                    "OSRM rota verileri okunamadı: ${error.message?.take(80) ?: "geçersiz JSON"}")
            }
            // Publish only a completely validated response. Do not publish a partial route.
            variants.value = alternatives
            index.value = 0
            selected.value = alternatives.first().snapshot
            RouteCalculationResult.Success(alternatives.size)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: UnknownHostException) {
            RouteCalculationResult.Failure(null, CoreIssue.NETWORK_UNAVAILABLE.messageTr)
        } catch (_: SocketTimeoutException) {
            RouteCalculationResult.Failure(null, "OSRM yanıtı zaman aşımına uğradı.")
        } catch (_: IOException) {
            RouteCalculationResult.Failure(null, "OSRM bağlantısı kurulamadı. Yeniden deneyin.")
        } catch (error: RuntimeException) {
            RouteCalculationResult.Failure(null,
                "Rota yanıtı işlenemedi: ${error.message?.take(90) ?: "beklenmeyen hata"}")
        }
    }

    override fun choose(index: Int) {
        val option = variants.value.getOrNull(index) ?: return
        this.index.value = index
        selected.value = option.snapshot
    }
    override fun clear() {
        variants.value = emptyList()
        index.value = null
        selected.value = null
    }

    companion object {
        fun explainStatus(status: Int, code: String?): String {
            val suffix = code?.let { " · OSRM: $it" } ?: ""
            return when (status) {
                400 -> "OSRM HTTP 400: koordinatlar veya istek geçersiz$suffix"
                404 -> "OSRM HTTP 404: rota servisi bulunamadı$suffix"
                429 -> "OSRM HTTP 429: istek sınırına ulaşıldı$suffix"
                in 500..599 -> "OSRM HTTP $status: servis geçici olarak kullanılamıyor$suffix"
                else -> "OSRM HTTP $status: istek başarısız$suffix"
            }
        }
        /** Only expose the OSRM machine-readable error code, not arbitrary server text. */
        fun parseErrorCode(json: String): String? = try {
            JsonParser.parseString(json).asJsonObject.get("code")
                ?.asString?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,40}")) }
        } catch (_: Exception) { null }
    }
}
