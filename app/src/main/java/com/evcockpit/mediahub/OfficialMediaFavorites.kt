package com.evcockpit.mediahub

import android.content.Context

/**
 * Local favorite state for cards from known official publishers.
 * Stores only stable card identifiers, never video URLs, account data or tokens.
 */
object OfficialMediaFavorites {
    private const val PREFS = "evcockpit_official_media_favorites_v1"
    private const val KEY = "ids"
    private const val LIMIT = 200
    private val safeId = Regex("[a-zA-Z0-9_-]{1,80}")

    fun isSafeId(id: String): Boolean = safeId.matches(id)

    fun contains(context: Context, id: String): Boolean =
        isSafeId(id) && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())?.contains(id) == true

    fun toggle(context: Context, id: String): Boolean {
        if (!isSafeId(id)) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updated = prefs.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        val selected = if (id in updated) {
            updated.remove(id)
            false
        } else {
            if (updated.size >= LIMIT) return false
            updated.add(id)
            true
        }
        prefs.edit().putStringSet(KEY, updated).apply()
        return selected
    }
}
