package com.evcockpit.mediahub

/** Pure browsing logic shared by Films / Series / Matches. No playback or external feeds. */
data class MediaLibraryGroup(val name: String, val count: Int)
data class MediaLibraryBrowseResult(
    val categories: List<MediaLibraryGroup>,
    val continueWatching: List<VideoChannel>,
    val entries: List<VideoChannel>,
    val total: Int
)

object MediaLibraryBrowsePlanner {
    fun plan(
        sources: List<VideoChannel>,
        kind: VideoLibraryKind,
        query: String = "",
        favoritesOnly: Boolean = false,
        category: String? = null,
        recentIds: List<String> = emptyList(),
        resumableIds: Set<String> = emptySet()
    ): MediaLibraryBrowseResult {
        val matches = VideoLibraryClassifier.filtered(sources, kind, query, favoritesOnly)
        val categories = matches
            .flatMap { item ->
                item.group.split('|').map { it.trim().ifBlank { "Diğer" } }
                    .distinctBy { it.lowercase() }
            }
            .groupingBy { it }
            .eachCount()
            .map { MediaLibraryGroup(it.key, it.value) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

        val visible = if (category.isNullOrBlank()) matches else matches.filter {
            it.group.split('|').any { label -> label.trim().equals(category, ignoreCase = true) }
        }
        if (kind == VideoLibraryKind.SPORT || resumableIds.isEmpty()) {
            return MediaLibraryBrowseResult(categories, emptyList(), visible, visible.size)
        }
        // Preserve last-opened order, not alphabetical or "most watched" order.
        val index = visible.associateBy { it.id }
        val seen = HashSet<String>()
        val continueWatching = recentIds.mapNotNull { id ->
            if (id in resumableIds && seen.add(id)) index[id] else null
        }.take(8)
        val pinned = continueWatching.mapTo(HashSet()) { it.id }
        return MediaLibraryBrowseResult(
            categories, continueWatching,
            visible.filterNot { it.id in pinned },
            visible.size
        )
    }
}
