package com.evcockpit.mediahub

/** Deterministic user-playlist grouping for the MediaEngine-inspired TV directory. */
data class ChannelCategory(val title: String, val count: Int)

object MediaEngineTvPlanner {
    fun categories(channels: List<VideoChannel>): List<ChannelCategory> {
        val groups = channels.groupingBy {
            it.group.trim().ifBlank { "Diğer" }.take(90)
        }.eachCount()
        return groups.entries.sortedWith(
            compareByDescending<Map.Entry<String,Int>> { it.value }.thenBy { it.key.lowercase() }
        ).map { ChannelCategory(it.key,it.value) }
    }

    fun list(channels: List<VideoChannel>, group: String?, query: String = "",
             favorites: Boolean = false): List<VideoChannel> {
        val q = query.trim()
        return channels.asSequence()
            .filter { group == null || it.group.trim().ifBlank { "Diğer" }.equals(group,true) }
            .filter { !favorites || it.favorite }
            .filter { q.isEmpty() || it.name.contains(q,true) ||
                it.group.contains(q,true) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()
    }
}
