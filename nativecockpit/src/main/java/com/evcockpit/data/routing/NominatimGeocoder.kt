package com.evcockpit.data.routing

import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.RoutePlace
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

internal interface NominatimService {
    @Headers(
        "User-Agent: EV-Cockpit/1.0 (https://github.com/AlpTimucin-cpu/ev-cockpit)",
        "Accept-Language: tr-TR"
    )
    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("format") format: String = "jsonv2",
        @Query("limit") limit: Int = 5,
        @Query("addressdetails") addressDetails: Int = 0
    ): Response<ResponseBody>
}

/** Only user-submitted searches. Never invoke from text-change/autocomplete. */
internal class NominatimGeocoder(private val http: OkHttpClient) {
    private val mutex = Mutex()
    private var lastRequestAtNanos: Long? = null
    private data class Cached(val atMillis: Long, val places: List<RoutePlace>)
    private val cache = LinkedHashMap<String, Cached>()
    private val cacheTtlMs = 24L * 60 * 60 * 1000

    suspend fun lookup(baseUrl: String, query: String): List<RoutePlace> =
        mutex.withLock {
            val normalized = query.trim()
            require(normalized.length in 3..100)
            val key = baseUrl + "|" + normalized.lowercase(Locale.forLanguageTag("tr-TR"))
            val now = System.currentTimeMillis()
            cache[key]?.takeIf {
                now >= it.atMillis && now - it.atMillis <= cacheTtlMs
            }?.let { return@withLock it.places }

            // Public Nominatim: absolute maximum 1 request/second, including errors.
            lastRequestAtNanos?.let { last ->
                val elapsed = (System.nanoTime() - last) / 1_000_000L
                if (elapsed in 0L..999L) delay(1000L - elapsed)
            }
            lastRequestAtNanos = System.nanoTime()
            val service = Retrofit.Builder()
                .baseUrl(baseUrl).client(http).build()
                .create(NominatimService::class.java)
            val response = service.search(normalized)
            if (!response.isSuccessful) {
                response.errorBody()?.close()
                throw IOException("Adres servisi HTTP " + response.code() + " yanıtı döndürdü.")
            }
            val body = response.body() ?: throw IOException("Adres servisi boş yanıt verdi.")
            val places = NominatimResponseParser.parse(body.use { boundedText(it) })
            cache[key] = Cached(System.currentTimeMillis(), places)
            if (cache.size > 80) cache.remove(cache.keys.first())
            places
        }

    private fun boundedText(body: ResponseBody): String {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        body.byteStream().use { stream ->
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                if (n == 0) continue
                if (output.size() + n > 300_000) {
                    throw IOException("Adres sunucusu çok büyük yanıt verdi.")
                }
                output.write(chunk, 0, n)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

internal object NominatimResponseParser {
    fun parse(json: String): List<RoutePlace> {
        require(json.length in 2..300_000)
        val root = JsonParser.parseString(json)
        require(root.isJsonArray) { "Adres yanıtı liste değil." }
        return root.asJsonArray.take(10).mapNotNull { item ->
            try {
                val obj = item.asJsonObject
                val latitude = obj.get("lat")?.asString?.toDoubleOrNull()
                    ?: return@mapNotNull null
                val longitude = obj.get("lon")?.asString?.toDoubleOrNull()
                    ?: return@mapNotNull null
                val coordinate = GeoCoordinate(latitude, longitude)
                if (!coordinate.valid()) return@mapNotNull null
                val name = obj.get("display_name")?.asString?.trim()
                    ?.takeIf { it.isNotEmpty() && it.length <= 320 &&
                        it.none { c -> c.isISOControl() || c == '<' || c == '>' }
                    }?.take(145) ?: return@mapNotNull null
                RoutePlace(name, coordinate, source = "Nominatim / OpenStreetMap")
            } catch (_: RuntimeException) {
                null
            }
        }.distinctBy { it.label to it.coordinate }.take(5)
    }
}
