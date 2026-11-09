package com.evcockpit.mediahub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class VideoChannel(
    val id: String,
    val name: String,
    val url: String,
    val group: String,
    val favorite: Boolean,
    val logo: String = "",
    val tvgId: String = "",
    val kindOverride: VideoLibraryKind? = null
)

/** Private on-device catalogue. Video channels never appear in the Android Auto media tree. */
object VideoStore {
    private const val PREFS = "evcockpit_video_library"
    private const val KEY = "channels"
    private const val MAX_CHANNELS = 15000
    @Volatile private var cachedRaw: String? = null
    @Volatile private var cachedChannels: List<VideoChannel>? = null

    fun load(context: Context): List<VideoChannel> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        cachedChannels?.let { if (raw == cachedRaw) return it }
        return try {
            val array = JSONArray(raw)
            var needsMigration = false
            buildList {
                for (i in 0 until minOf(array.length(), MAX_CHANNELS)) {
                    val obj = array.getJSONObject(i)
                    val encodedUrl = obj.getString("url")
                    if (!PrivateUrlCodec.isEncrypted(encodedUrl)) needsMigration = true
                    val url = PrivateUrlCodec.decode(encodedUrl)
                    if (M3uParser.isHttpsUrl(url)) add(VideoChannel(
                        obj.getString("id"), M3uParser.displayTitle(obj.getString("name")), url,
                        obj.optString("group", "Diğer"), obj.optBoolean("favorite", false),
                        PrivateUrlCodec.decode(obj.optString("logo", ""))
                            .takeIf { M3uParser.isHttpsUrl(it) }.orEmpty(),
                        obj.optString("tvgId", "").take(160),
                        VideoLibraryKind.values().firstOrNull { it.name == obj.optString("kind") }
                    ))
                }
            }.also { parsed ->
                cachedRaw = raw
                cachedChannels = parsed
                // Upgrade legacy plaintext credentials on the first successful read.
                // Large saved playlists must NOT be re-encrypted on the UI launch path.
                // The next explicit user edit/save writes encrypted URLs.
                // A dedicated background migrator will be added after device testing.
            }
        } catch (_: Exception) { emptyList() }
    }

    fun add(context: Context, name: String, url: String, group: String = "Diğer"): Boolean {
        if (name.isBlank() || !M3uParser.isHttpsUrl(url)) return false
        val channels = load(context).toMutableList()
        if (channels.size >= MAX_CHANNELS || channels.any { it.url == url }) return false
        channels.add(VideoChannel(UUID.randomUUID().toString(), M3uParser.displayTitle(name), url,
            group.trim().take(100).ifBlank { "Diğer" }, false))
        save(context, channels)
        return true
    }

    fun addAll(context: Context, items: List<PlaylistEntry>,
               kindOverride: VideoLibraryKind? = null): Int {
        val channels = load(context).toMutableList()
        val existing = channels.withIndex().associate { it.value.url to it.index }.toMutableMap()
        var added = 0
        var reclassified = false
        for (item in items) {
            if (item.isAudio || !M3uParser.isHttpsUrl(item.url)) continue
            val savedIndex = existing[item.url]
            if (savedIndex != null) {
                // The same M3U may have been imported via generic IPTV before.
                // Keep the stable ID, favorite and playback progress, but allow
                // explicit user selection to move it into Movies/Series/Sports.
                if (kindOverride != null && channels[savedIndex].kindOverride != kindOverride) {
                    channels[savedIndex] = channels[savedIndex].copy(kindOverride = kindOverride)
                    reclassified = true
                }
                continue
            }
            if (channels.size >= MAX_CHANNELS) break
            val channel = VideoChannel(UUID.randomUUID().toString(),
                M3uParser.displayTitle(item.name), item.url, item.group,
                false, item.logo, item.tvgId, kindOverride)
            existing[item.url] = channels.size
            channels.add(channel)
            added++
        }
        if (added > 0 || reclassified) save(context, channels)
        return added
    }

    fun toggleFavorite(context: Context, id: String) {
        save(context, load(context).map { if (it.id == id) it.copy(favorite = !it.favorite) else it })
    }

    fun remove(context: Context, id: String) = save(context, load(context).filterNot { it.id == id })

    private fun save(context: Context, channels: List<VideoChannel>) {
        val json = JSONArray()
        channels.forEach { channel ->
            json.put(JSONObject().put("id", channel.id).put("name", channel.name)
                .put("url", PrivateUrlCodec.encode(channel.url)).put("group", channel.group)
                .put("favorite", channel.favorite).put("logo", PrivateUrlCodec.encode(channel.logo))
                .put("tvgId", channel.tvgId).put("kind", channel.kindOverride?.name ?: ""))
        }
        val encoded = json.toString()
        // Keep the in-memory view consistent with the exact local snapshot just saved.
        cachedRaw = encoded
        cachedChannels = channels.toList()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encoded).apply()
    }
}
