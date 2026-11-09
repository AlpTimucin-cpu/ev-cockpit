package com.evcockpit.mediahub

/**
 * Stable, side-effect-free home dashboard selector. Incoming media IDs remain
 * local and are never included in logs or external analytics.
 */
data class DriveRecentItem(val kind: String, val id: String, val title: String)

object DriveHomePlanner {
    fun recent(history: List<String>,
               tvNames: Map<String, String>,
               radioNames: Map<String, String>,
               maximum: Int = 5): List<DriveRecentItem> {
        if (maximum <= 0) return emptyList()
        val unique = mutableSetOf<String>()
        val result = ArrayList<DriveRecentItem>()
        for (key in history.take(48)) {
            val position = key.indexOf(':')
            if (position < 1 || position == key.lastIndex) continue
            val kind = key.substring(0, position)
            val id = key.substring(position + 1)
            val name = when (kind) {
                "tv" -> tvNames[id]
                "radio" -> radioNames[id]
                else -> null
            }?.trim().orEmpty()
            if (name.isEmpty() || !unique.add(key)) continue
            result.add(DriveRecentItem(kind, id, name.take(90)))
            if (result.size == maximum) break
        }
        return result
    }
}
