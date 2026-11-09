package com.evcockpit.mediahub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Separate, on-device YouTube video history.
 * This stores only canonical video URLs without query tracking tokens.
 * No history is shared with Android Auto or external apps automatically.
 */
object YoutubeVideoStore {
    private const val PREFS = "evcockpit_youtube_history_v1"
    private const val KEY = "recent_videos"
    private const val LIMIT = 30

    fun load(context: Context): List<SavedWebPage> {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(value)
            buildList {
                for (i in 0 until minOf(array.length(), LIMIT)) {
                    val item = array.optJSONObject(i) ?: continue
                    val canonical = YoutubePagePolicy.canonicalWatchUrl(
                        item.optString("url")) ?: continue
                    if (any { it.url == canonical }) continue
                    val title = item.optString("title", "").trim().take(90)
                        .ifBlank { "YouTube videosu" }
                    add(SavedWebPage(title, canonical))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun remember(context: Context, title: String, rawUrl: String): Boolean {
        val canonical = YoutubePagePolicy.canonicalWatchUrl(rawUrl) ?: return false
        val safeTitle = title.trim().take(90).ifBlank { "YouTube videosu" }
        val entries = load(context)
        val current = entries.firstOrNull { it.url == canonical }
        // Avoid excessive disk writes during SPA title/URL events and reloads.
        if (current?.title == safeTitle && entries.firstOrNull()?.url == canonical)
            return true
        val next = listOf(SavedWebPage(safeTitle, canonical)) +
            entries.filterNot { it.url == canonical }
        val json = JSONArray()
        next.take(LIMIT).forEach {
            json.put(JSONObject().put("title", it.title).put("url", it.url))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, json.toString()).apply()
        return true
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY).apply()
    }
}
