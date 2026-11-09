package com.evcockpit.mediahub

import android.content.Context
import org.json.JSONArray

/** Local, on-device last-opened catalog entries. No web tracking or user accounts. */
object RecentStore {
    private const val PREF = "evcockpit_recent"
    private const val KEY = "ids"
    private const val CAP = 24

    fun ids(context: Context): List<String> = try {
        val value = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val json = JSONArray(value)
        (0 until json.length()).mapNotNull { json.optString(it).takeIf(String::isNotBlank) }
    } catch (_: Exception) { emptyList() }

    fun remember(context: Context, id: String, kind: String) {
        val key = "$kind:$id"
        val history = (listOf(key) + ids(context).filter { it != key }).take(CAP)
        val json = JSONArray()
        history.forEach { json.put(it) }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }
}
