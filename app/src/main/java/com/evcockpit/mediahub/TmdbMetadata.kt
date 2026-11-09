package com.evcockpit.mediahub

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/** Public TMDB metadata, separate from REC TV's private movie playback service.
 * A user-supplied TMDB Read Access Token lives only in memory, never in an APK or log.
 */
data class TmdbEntry(
    val id: Long,
    val title: String,
    val description: String,
    val posterUrl: String,
    val releaseYear: String,
    val score: Double
)

enum class TmdbKind(val route: String, val label: String) {
    MOVIE("movie", "Filmler"),
    SERIES("tv", "Diziler")
}

/** Regional streaming availability only; no movie/episode playback streams. */
data class TmdbWatchAvailability(
    val subscriptions: List<String>,
    val freeOrAds: List<String>,
    val rental: List<String>,
    val purchase: List<String>,
    val watchPage: String
)

object TmdbMetadata {
    private const val API = "https://api.themoviedb.org/3/"
    private const val MAX_RESPONSE_BYTES = 550_000
    private val workers = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())

    // Never commit, log, or write credentials into SharedPreferences.
    @Volatile private var sessionToken: String? = null
    fun isConfigured(): Boolean = !sessionToken.isNullOrBlank()
    fun authorizeForSession(token: String): Boolean {
        val t = token.trim()
        if (t.length !in 32..2048 || t.any { it.isWhitespace() || it.isISOControl() }) return false
        sessionToken = t
        return true
    }
    fun clearSession() { sessionToken = null }

    /** Official TMDB API, fixed host. The search query is strictly URL-encoded. */
    fun requestPath(kind: TmdbKind, search: String): String {
        val needle = search.trim().take(80)
        return if (needle.isEmpty()) "trending/" + kind.route + "/week?language=tr-TR"
        else "search/" + kind.route + "?language=tr-TR&include_adult=false&query=" +
            URLEncoder.encode(needle, "UTF-8")
    }

    fun poster(path: String?): String {
        val value = path.orEmpty()
        return if (value.matches(Regex("/[a-zA-Z0-9_-]{8,110}\\.(jpg|png|webp)")))
            "https://image.tmdb.org/t/p/w342" + value
        else ""
    }

    /** Official TMDB/JustWatch endpoint for watch availability in Türkiye. */
    fun watchProviderPath(kind: TmdbKind, id: Long): String =
        TmdbWatchPolicy.apiPath(kind,id)

    fun officialWatchPage(kind: TmdbKind, id: Long): String =
        TmdbWatchPolicy.watchPage(kind,id)

    fun loadWatchProviders(
        kind: TmdbKind, id: Long,
        callback: (Result<TmdbWatchAvailability>) -> Unit
    ) {
        val token = sessionToken
        if (token.isNullOrBlank()) {
            handler.post {
                callback(Result.failure(IllegalStateException(
                    "TMDB API Read Access Token gerekli")))
            }
            return
        }
        val path = try { watchProviderPath(kind, id) } catch (e: Exception) {
            handler.post { callback(Result.failure(e)) }
            return
        }
        workers.execute {
            val outcome = try {
                val conn = URL(API + path).openConnection() as HttpsURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 12_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Authorization", "Bearer " + token)
                conn.setRequestProperty("Accept", "application/json")
                try {
                    val code = conn.responseCode
                    if (code != 200) throw IllegalStateException(
                        if (code == 401 || code == 403)
                            "TMDB erişim anahtarını kontrol et"
                        else "Sağlayıcı bilgileri alınamadı (HTTP " + code + ")")
                    val bytes = conn.inputStream.use {
                        it.readNBytes(MAX_RESPONSE_BYTES + 1)
                    }
                    if (bytes.size > MAX_RESPONSE_BYTES)
                        throw IllegalStateException("TMDB yanıtı beklenenden büyük")
                    val region = JSONObject(String(bytes, Charsets.UTF_8))
                        .optJSONObject("results")?.optJSONObject("TR")
                    fun providers(key: String): List<String> {
                        val array = region?.optJSONArray(key) ?: return emptyList()
                        return (0 until minOf(array.length(), 40)).mapNotNull { i ->
                            val name = array.optJSONObject(i)?.optString("provider_name")
                                ?.trim().orEmpty()
                            name.takeIf { it.length in 2..80 &&
                                !it.any(Char::isISOControl) }
                        }.distinct()
                    }
                    Result.success(TmdbWatchAvailability(
                        subscriptions = providers("flatrate"),
                        freeOrAds = (providers("free") + providers("ads")).distinct(),
                        rental = providers("rent"),
                        purchase = providers("buy"),
                        watchPage = officialWatchPage(kind, id)
                    ))
                } finally { conn.disconnect() }
            } catch (e: Exception) {
                Result.failure(IllegalStateException(
                    if (e is IllegalStateException) e.message
                    else "Türkiye'deki yayın sağlayıcıları alınamadı"))
            }
            handler.post { callback(outcome) }
        }
    }

    fun load(kind: TmdbKind, search: String, callback: (Result<List<TmdbEntry>>) -> Unit) {
        val token = sessionToken
        if (token.isNullOrBlank()) {
            handler.post { callback(Result.failure(IllegalStateException("TMDB API anahtarı gerekli"))) }
            return
        }
        workers.execute {
            val result = try {
                val location = URL(API + requestPath(kind, search))
                val conn = location.openConnection() as HttpsURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 10_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Authorization", "Bearer " + token)
                conn.setRequestProperty("Accept", "application/json")
                try {
                    val code = conn.responseCode
                    if (code != 200) throw IllegalStateException(
                        if (code in listOf(401, 403)) "TMDB erişim anahtarını kontrol et"
                        else "TMDB sunucusu HTTP " + code + " döndürdü")
                    val raw = conn.inputStream.use { it.readNBytes(MAX_RESPONSE_BYTES + 1) }
                    if (raw.size > MAX_RESPONSE_BYTES)
                        throw IllegalStateException("TMDB yanıtı çok büyük")
                    val payload = JSONObject(String(raw, Charsets.UTF_8))
                    val results = payload.optJSONArray("results")
                    val list = buildList {
                        if (results != null) for (i in 0 until minOf(results.length(), 30)) {
                            val item = results.optJSONObject(i) ?: continue
                            val id = item.optLong("id")
                            if (id <= 0) continue
                            val title = item.optString(if (kind == TmdbKind.MOVIE)
                                "title" else "name").trim().take(100)
                            if (title.isEmpty()) continue
                            val date = item.optString(if (kind == TmdbKind.MOVIE)
                                "release_date" else "first_air_date")
                            add(TmdbEntry(
                                id = id, title = title,
                                description = item.optString("overview").take(460),
                                posterUrl = poster(item.optString("poster_path")),
                                releaseYear = date.take(4).takeIf {
                                    it.length == 4 && it.all(Char::isDigit)
                                }.orEmpty(),
                                score = item.optDouble("vote_average", 0.0)
                                    .coerceIn(0.0, 10.0)
                            ))
                        }
                    }
                    Result.success(list)
                } finally { conn.disconnect() }
            } catch (_: java.net.SocketTimeoutException) {
                Result.failure(IllegalStateException("TMDB bağlantısı zaman aşımına uğradı"))
            } catch (t: Exception) {
                // Never expose query URLs or request headers in errors.
                Result.failure(IllegalStateException(
                    if (t is IllegalStateException) t.message else "TMDB bağlantısı kurulamadı"
                ))
            }
            handler.post { callback(result) }
        }
    }
}
