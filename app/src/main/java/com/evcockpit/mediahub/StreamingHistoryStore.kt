package com.evcockpit.mediahub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Opt-in local title-page history for Prime Video and Netflix.
 * No account data, tokens, cookies, video playback metrics or progress is stored.
 */
object StreamingHistoryStore {
    private const val PREFS = "evcockpit_streaming_recent_v1"
    private const val MAX_PER_SERVICE = 30

    private fun key(service: StreamingService): String = when(service) {
        StreamingService.NETFLIX -> "netflix_titles"
        StreamingService.PRIME_VIDEO -> "prime_video_titles"
    }

    fun load(context: Context, service: StreamingService): List<SavedWebPage> {
        val jsonText = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(service), null) ?: return emptyList()
        return try {
            val array = JSONArray(jsonText)
            buildList {
                for (i in 0 until minOf(MAX_PER_SERVICE, array.length())) {
                    val obj = array.optJSONObject(i) ?: continue
                    val canonical = StreamingPagePolicy.canonical(
                        obj.optString("url")) ?: continue
                    if (canonical.service != service || any { it.url == canonical.url }) continue
                    val title = obj.optString("title", "").trim()
                        .replace(Regex("[\\p{Cntrl}]"), " ")
                        .take(90).ifBlank { service.displayName + " içeriği" }
                    add(SavedWebPage(title, canonical.url))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun remember(context: Context, title: String, rawUrl: String): Boolean {
        val canonical = StreamingPagePolicy.canonical(rawUrl) ?: return false
        val name = title.trim().replace(Regex("[\\p{Cntrl}]"), " ")
            .take(90).ifBlank { canonical.service.displayName + " içeriği" }
        val existing = load(context, canonical.service)
        if (existing.firstOrNull()?.let { it.url == canonical.url && it.title == name } == true)
            return true
        val updated = (listOf(SavedWebPage(name, canonical.url)) +
            existing.filterNot { it.url == canonical.url }).take(MAX_PER_SERVICE)
        val json = JSONArray()
        updated.forEach { json.put(JSONObject().put("title",it.title).put("url",it.url)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key(canonical.service),json.toString()).apply()
        return true
    }

    fun clear(context: Context, service: StreamingService) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(key(service)).apply()
    }
}
