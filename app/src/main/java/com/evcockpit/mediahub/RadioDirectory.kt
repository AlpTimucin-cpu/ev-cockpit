package com.evcockpit.mediahub

import android.content.Context
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Public, community-edited Radio Browser directory. This does not verify every live stream. */
object RadioDirectory {
    private val endpoints = listOf(
        "https://de1.api.radio-browser.info/json/stations/search",
        "https://nl1.api.radio-browser.info/json/stations/search",
        "https://at1.api.radio-browser.info/json/stations/search"
    )
    private const val REFRESH_MS = 24L * 60 * 60 * 1000
    private const val MAX_RESPONSE_BYTES = 2_500_000

    fun shouldRefresh(context: Context): Boolean {
        val prefs = context.getSharedPreferences("evcockpit_radio_directory", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_success", 0L)
        return last <= 0L || System.currentTimeMillis() - last > REFRESH_MS
    }

    fun loadAndMerge(context: Context): Int {
        var lastError: Exception? = null
        for (endpoint in endpoints) {
            try {
                val url = URL(
                    "$endpoint?countrycode=TR&hidebroken=true&is_https=true" +
                        "&order=clickcount&reverse=true&limit=320"
                )
                val conn = url.openConnection() as HttpsURLConnection
                conn.connectTimeout = 9_000
                conn.readTimeout = 12_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "EV-Cockpit/0.11 (Android radio directory)")
                conn.setRequestProperty("Accept", "application/json")
                try {
                    require(conn.responseCode == 200) { "Radio Browser HTTP response not 200" }
                    val bytes = ByteArrayOutputStream()
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val length = input.read(buffer)
                            if (length == -1) break
                            require(bytes.size() + length <= MAX_RESPONSE_BYTES) {
                                "Radio directory response too large"
                            }
                            bytes.write(buffer, 0, length)
                        }
                    }
                    val response = JSONArray(bytes.toString(Charsets.UTF_8.name()))
                    val options = buildList {
                        val urls = mutableSetOf<String>()
                        for (i in 0 until minOf(response.length(), 320)) {
                            val json = response.optJSONObject(i) ?: continue
                            val onlineFlag = json.opt("lastcheckok")
                            val online = onlineFlag == true || onlineFlag?.toString() == "1" ||
                                onlineFlag?.toString()?.equals("true", true) == true
                            if (!online) continue
                            val uuid = json.optString("stationuuid").trim()
                            val name = json.optString("name").trim().take(110)
                            val resolved = json.optString("url_resolved").trim()
                            val stream = resolved.takeIf { M3uParser.isHttpsUrl(it) }
                                ?: json.optString("url").trim()
                            if (uuid.length !in 8..80 || name.isBlank() ||
                                !M3uParser.isHttpsUrl(stream) || !urls.add(stream)) continue
                            val logo = json.optString("favicon").trim()
                                .takeIf { M3uParser.isHttpsUrl(it) }.orEmpty()
                            val tags = json.optString("tags").lowercase()
                            val group = category(tags)
                            add(Station(
                                id = "rb-$uuid", name = name, url = stream, group = group,
                                logo = logo
                            ))
                        }
                    }
                    require(options.size >= 8) { "Radio directory returned too few supported stations" }
                    val added = StationStore.mergeDirectory(context, options)
                    context.getSharedPreferences("evcockpit_radio_directory", Context.MODE_PRIVATE)
                        .edit().putLong("last_success", System.currentTimeMillis()).apply()
                    return added
                } finally { conn.disconnect() }
            } catch (ex: Exception) {
                // Never print full stream URLs or raw exceptions (which may carry credentials).
                lastError = ex
            }
        }
        throw IllegalStateException("Radyo kataloğu alınamadı. Bağlantıyı kontrol edip yeniden dene.", lastError)
    }

    private fun category(tags: String): String = when {
        listOf("news","haber","talk","politics").any { it in tags } -> "Haber & Sohbet"
        listOf("arabesk","fantazi","fantezi").any { it in tags } -> "Arabesk"
        listOf("rock","metal","alternative").any { it in tags } -> "Rock"
        listOf("classical","klasik","jazz","instrumental").any { it in tags } -> "Klasik & Jazz"
        listOf("turkish","türkçe","turkce","turk pop","turkish pop").any { it in tags } -> "Türkçe"
        listOf("pop","hits","dance","electronic","edm").any { it in tags } -> "Pop & Dans"
        listOf("folk","halk","türkü").any { it in tags } -> "Türküler"
        else -> "Diğer"
    }
}
