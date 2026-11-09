package com.evcockpit.mediahub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Browser data is kept on the phone only; it is never placed in Android Auto's audio catalogue. */
data class StoredWebTab(val title: String, val url: String)
data class SavedWebPage(val title: String, val url: String)

object BrowserStore {
    private const val PREFS = "evcockpit_browser_v2"
    private const val KEY_TABS = "tabs"
    private const val KEY_BOOKMARKS = "bookmarks"
    private const val KEY_HISTORY = "history"
    private const val MAX_TABS = 6
    private const val MAX_BOOKMARKS = 80
    private const val MAX_HISTORY = 80

    /** No javascript:, file:, intent:, http:, content:, embedded credentials or oversized URLs. */
    fun safeHttps(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 2048 || text.any { it.isWhitespace() || it.isISOControl() }) return null
        return try {
            val u = URI(text)
            if (!u.scheme.equals("https", ignoreCase = true) ||
                u.host.isNullOrBlank() || u.rawUserInfo != null || u.port !in -1..65535 ||
                u.host.length > 250) null else u.toASCIIString()
        } catch (_: Exception) { null }
    }

    private fun jsonLoad(context: Context, key: String, max: Int): List<SavedWebPage> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
            ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until minOf(max, array.length())) {
                    val item = array.optJSONObject(i) ?: continue
                    val url = safeHttps(item.optString("url")) ?: continue
                    val title = item.optString("title", "").take(90).ifBlank { URI(url).host ?: "Web" }
                    if (none { it.url == url }) add(SavedWebPage(title, url))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun jsonSave(context: Context, key: String, pages: List<SavedWebPage>, limit: Int) {
        val array = JSONArray()
        pages.take(limit).forEach { page ->
            array.put(JSONObject().put("title", page.title.take(90)).put("url", page.url))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key, array.toString()).apply()
    }

    fun tabs(context: Context): List<StoredWebTab> =
        jsonLoad(context, KEY_TABS, MAX_TABS).map { StoredWebTab(it.title, it.url) }

    fun saveTabs(context: Context, tabs: List<StoredWebTab>) {
        jsonSave(context, KEY_TABS, tabs.mapNotNull { item ->
            safeHttps(item.url)?.let { SavedWebPage(item.title, it) }
        }, MAX_TABS)
    }

    fun bookmarks(context: Context) = jsonLoad(context, KEY_BOOKMARKS, MAX_BOOKMARKS)

    fun isBookmarked(context: Context, url: String): Boolean =
        bookmarks(context).any { it.url == url }

    /** Returns true if the URL was added, false when it was removed. */
    fun toggleBookmark(context: Context, title: String, address: String): Boolean {
        val url = safeHttps(address) ?: return false
        val pages = bookmarks(context)
        val alreadySaved = pages.any { it.url == url }
        val next = if (alreadySaved) pages.filterNot { it.url == url }
            else listOf(SavedWebPage(title.take(90).ifBlank { "Web" }, url)) + pages
        jsonSave(context, KEY_BOOKMARKS, next, MAX_BOOKMARKS)
        return !alreadySaved
    }

    fun history(context: Context) = jsonLoad(context, KEY_HISTORY, MAX_HISTORY)

    fun remember(context: Context, title: String, address: String) {
        val url = safeHttps(address) ?: return
        val pages = history(context).filterNot { it.url == url }
        jsonSave(context, KEY_HISTORY,
            listOf(SavedWebPage(title.take(90).ifBlank { "Web" }, url)) + pages, MAX_HISTORY)
    }

    fun clearHistory(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_HISTORY).apply()
    }
}
