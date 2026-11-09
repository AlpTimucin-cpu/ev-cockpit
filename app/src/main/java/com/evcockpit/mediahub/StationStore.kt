package com.evcockpit.mediahub

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Station(
    val id: String,
    val name: String,
    val url: String,
    val group: String = "Radyo",
    val favorite: Boolean = false,
    val logo: String = ""
) {
    /** Keep M3U query tokens private until the station is actually played. */
    fun toMediaItem(includeUri: Boolean = false): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .also { builder ->
            if (includeUri) {
                builder.setUri(Uri.parse(url))
                if (url.substringBefore('?').endsWith(".m3u8", ignoreCase = true))
                    builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            }
        }
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(name)
            .setSubtitle(group)
            .also { metadata ->
                if (M3uParser.isHttpsUrl(logo)) metadata.setArtworkUri(Uri.parse(logo))
            }
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .build())
        .build()
}

/** Small, local catalogue: only audio stations are exposed to Android Auto. */
object StationStore {
    private const val PREFS = "evcockpit_media_hub"
    private const val KEY = "stations"
    private const val MAX_STATIONS = 2500
    private val defaults = listOf(
        Station("soma-groovesalad", "Groove Salad • SomaFM", "https://ice5.somafm.com/groovesalad-128-mp3", "SomaFM"),
        Station("soma-gsclassic", "Groove Salad Classic • SomaFM", "https://ice5.somafm.com/gsclassic-128-mp3", "SomaFM")
    )

    fun load(context: Context): List<Station> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return defaults
        return try {
            val array = JSONArray(raw)
            var needsMigration = false
            buildList {
                for (i in 0 until minOf(array.length(), MAX_STATIONS)) {
                    val obj = array.getJSONObject(i)
                    val storedUrl = obj.getString("url")
                    if (!PrivateUrlCodec.isEncrypted(storedUrl)) needsMigration = true
                    val station = Station(
                        obj.getString("id"), M3uParser.displayTitle(obj.getString("name")),
                        PrivateUrlCodec.decode(storedUrl),
                        obj.optString("group", "Radyo"), obj.optBoolean("favorite", false),
                        PrivateUrlCodec.decode(obj.optString("logo", ""))
                        .takeIf { M3uParser.isHttpsUrl(it) }.orEmpty()
                    )
                    if (validUrl(station.url)) add(station)
                }
            }.also { parsed ->
                // Large saved playlists must NOT be re-encrypted on the UI launch path.
                // The next explicit user edit/save writes encrypted URLs.
                // A dedicated background migrator will be added after device testing.
            }
        } catch (_: Exception) { defaults }
    }

    fun add(context: Context, name: String, url: String, group: String = "Radyo"): Boolean {
        val cleanUrl = url.trim()
        if (name.isBlank() || !validUrl(cleanUrl)) return false
        val stations = load(context).toMutableList()
        if (stations.size >= MAX_STATIONS || stations.any { it.url == cleanUrl }) return false
        stations.add(Station(UUID.randomUUID().toString(), name.trim().take(160), cleanUrl, group.trim().take(100).ifBlank { "Radyo" }))
        save(context, stations)
        return true
    }

    fun addAll(context: Context, entries: List<PlaylistEntry>): Int {
        val stations = load(context).toMutableList()
        val used = stations.mapTo(mutableSetOf()) { it.url }
        var count = 0
        for (entry in entries) {
            if (stations.size >= MAX_STATIONS) break
            if (entry.isAudio && validUrl(entry.url) && used.add(entry.url)) {
                stations.add(Station(UUID.randomUUID().toString(), entry.name, entry.url, entry.group, logo = entry.logo))
                count++
            }
        }
        if (count > 0) save(context, stations)
        return count
    }

    /** Merge directory results without removing user-added stations or losing favourites. */
    fun mergeDirectory(context: Context, incoming: List<Station>): Int {
        val stations = load(context).toMutableList()
        val indices = stations.withIndex().associate { it.value.id to it.index }
        val urls = stations.mapTo(mutableSetOf()) { it.url }
        var added = 0
        var changed = false
        for (candidate in incoming.take(400)) {
            if (candidate.name.isBlank() || !validUrl(candidate.url)) continue
            val knownIndex = indices[candidate.id]
            if (knownIndex != null) {
                val previous = stations[knownIndex]
                val refreshed = candidate.copy(favorite = previous.favorite)
                if (previous != refreshed && (candidate.url == previous.url || !urls.contains(candidate.url))) {
                    urls.remove(previous.url)
                    urls.add(candidate.url)
                    stations[knownIndex] = refreshed
                    changed = true
                }
            } else if (stations.size < MAX_STATIONS && urls.add(candidate.url)) {
                stations.add(candidate)
                added++
                changed = true
            }
        }
        if (changed) save(context, stations)
        return added
    }

    fun toggleFavorite(context: Context, id: String) =
        save(context, load(context).map { if (it.id == id) it.copy(favorite = !it.favorite) else it })

    fun remove(context: Context, id: String) =
        save(context, load(context).filterNot { it.id == id })

    private fun save(context: Context, stations: List<Station>) {
        val data = JSONArray()
        stations.forEach { s ->
            data.put(JSONObject().put("id", s.id).put("name", s.name)
                .put("url", PrivateUrlCodec.encode(s.url))
                .put("group", s.group).put("favorite", s.favorite)
                .put("logo", PrivateUrlCodec.encode(s.logo)))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, data.toString()).apply()
        // Refresh an already open Android Auto library; never broadcast playlist contents/URLs.
        context.sendBroadcast(Intent(AudioLibraryService.ACTION_CATALOG_UPDATED).setPackage(context.packageName))
    }

    fun validUrl(url: String): Boolean = try {
        M3uParser.isHttpsUrl(url.trim())
    } catch (_: Exception) { false }
}
