package com.evcockpit.mediahub

/**
 * Deterministic and side-effect-free homepage model.
 * Media URL, tokens and account details must never appear in this model.
 */
data class CockpitEntry(
    val kind: String,
    val id: String,
    val title: String,
    val subtitle: String,
    val favourite: Boolean = false,
    val logo: String = "",
    val tvgId: String = ""
)

data class CockpitOverview(
    val recents: List<CockpitEntry>,
    val favourites: List<CockpitEntry>,
    val tvCount: Int,
    val radioCount: Int
)

object CockpitPlanner {
    fun build(
        history: List<String>,
        tv: List<VideoChannel>,
        radio: List<Station>,
        recentLimit: Int = 6,
        favouriteLimit: Int = 6
    ): CockpitOverview {
        val channels = tv.associateBy { it.id }
        val stations = radio.associateBy { it.id }
        fun fromKey(key: String): CockpitEntry? {
            val separator = key.indexOf(':')
            if (separator <= 0 || separator == key.lastIndex) return null
            val kind = key.substring(0, separator)
            val id = key.substring(separator + 1)
            return when (kind) {
                "tv" -> channels[id]?.let {
                    CockpitEntry(kind, it.id, it.name, it.group, it.favorite, it.logo, it.tvgId)
                }
                "radio" -> stations[id]?.let {
                    CockpitEntry(kind, it.id, it.name, it.group, it.favorite, it.logo)
                }
                else -> null
            }
        }
        val recents = history.asSequence().mapNotNull(::fromKey)
            .distinctBy { it.kind + ":" + it.id }
            .take(recentLimit.coerceIn(0, 12)).toList()
        val favoriteKeys = buildList {
            tv.filter { it.favorite }.forEach { add("tv:" + it.id) }
            radio.filter { it.favorite }.forEach { add("radio:" + it.id) }
        }
        val favourites = favoriteKeys.asSequence().mapNotNull(::fromKey)
            .take(favouriteLimit.coerceIn(0, 12)).toList()
        return CockpitOverview(recents, favourites, tv.size, radio.size)
    }
}
