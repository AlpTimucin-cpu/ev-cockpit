package com.evcockpit.mediahub

import java.text.Normalizer
import java.util.Locale

/** Source-based VOD/live categories. No REC TV code or feeds are consumed. */
enum class VideoLibraryKind(val title: String, val sourceGroup: String, val subtitle: String) {
    MOVIE("Filmler", "Filmler", "Filmler ve video arşivi"),
    SERIES("Diziler", "Diziler", "Diziler ve bölümler"),
    SPORT("Maçlar", "Spor / Maçlar", "Canlı spor yayınları"),
    OTHER("Canlı TV", "Canlı TV", "Diğer kanallar")
}

object VideoLibraryClassifier {
    private val seasonEpisode = Regex("(?i)(?:s\\d{1,2}\\s*e\\d{1,3}|\\d{1,2}x\\d{1,3}|\\b\\d+\\.\\s*sezon\\b)")
    private val movieWords = listOf("film", "filmler", "movie", "cinema", "sinema", "vod", "movies")
    private val seriesWords = listOf("dizi", "diziler", "series", "serial", "sezon", "episode", "bolum", "bolumler")
    private val sportsWords = listOf("spor", "sport", "mac", "maclar", "futbol", "football", "basketbol",
        "tenis", "tennis", "voleybol", "super lig", "premier league", "champions league")

    private fun fold(s: String): String {
        val replaced = s.lowercase(Locale.ROOT).replace('ı', 'i').replace('İ','i')
        return Normalizer.normalize(replaced, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
    }

    private fun hasWord(text: String, words: List<String>) =
        words.any { word ->
            val start = text.indexOf(word)
            if (start < 0) false else {
                // "spor" must not match "transport"; groups may include slashes/spaces.
                val before = start == 0 || !text[start - 1].isLetterOrDigit()
                val end = start + word.length
                val after = end == text.length || !text[end].isLetterOrDigit()
                before && after
            }
        }

    fun classify(item: VideoChannel): VideoLibraryKind {
        item.kindOverride?.let { return it }
        val group = fold(item.group)
        val title = fold(item.name)
        // Explicit groups take precedence over ambiguous titles (e.g. a movie named "Match").
        return when {
            hasWord(group, seriesWords) -> VideoLibraryKind.SERIES
            hasWord(group, movieWords) -> VideoLibraryKind.MOVIE
            hasWord(group, sportsWords) -> VideoLibraryKind.SPORT
            seasonEpisode.containsMatchIn(title) -> VideoLibraryKind.SERIES
            else -> VideoLibraryKind.OTHER
        }
    }

    fun filtered(
        items: List<VideoChannel>, kind: VideoLibraryKind,
        query: String = "", onlyFavorites: Boolean = false
    ): List<VideoChannel> {
        val needle = fold(query.trim())
        return items.filter { item ->
            classify(item) == kind &&
                (!onlyFavorites || item.favorite) &&
                (needle.isEmpty() || fold(item.name).contains(needle) ||
                    fold(item.group).contains(needle))
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
}
