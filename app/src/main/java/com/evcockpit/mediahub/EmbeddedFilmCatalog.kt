package com.evcockpit.mediahub

import android.content.Context
import java.text.Normalizer
import java.util.Locale

/** 
 * Offline-only index of movie *metadata* supplied by the owner of this app.
 * Assets deliberately contain NO stream URL, protected token, Referer header,
 * User-Agent override or third-party poster link. This is not a VOD provider.
 */
data class EmbeddedFilm(val title: String, val group: String)

object EmbeddedFilmCatalog {
    private val assetNames = listOf(
        "filmler_catalog_01.tsv", "filmler_catalog_02.tsv",
        "filmler_catalog_03.tsv", "filmler_catalog_04.tsv"
    )
    @Volatile private var cached: List<EmbeddedFilm>? = null

    /** Pure parser for unit testing. Tab separates title and genre. */
    fun parseRows(lines: Sequence<String>): List<EmbeddedFilm> =
        lines.mapNotNull { row ->
            val split = row.indexOf('\t')
            if (split < 1 || split >= row.lastIndex) return@mapNotNull null
            val title = row.substring(0, split).trim().take(160)
            val group = row.substring(split + 1).trim().take(160)
            if (title.isEmpty() || group.isEmpty() ||
                title.contains("://") || group.contains("://")) null
            else EmbeddedFilm(title, group)
        }.take(15000).toList()

    fun load(context: Context): List<EmbeddedFilm> {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: buildList {
                for (filename in assetNames) {
                    try {
                        context.assets.open(filename).bufferedReader(Charsets.UTF_8).use { reader ->
                            addAll(parseRows(reader.lineSequence()))
                        }
                    } catch (_: Exception) {
                        // The list must be treated as unavailable rather than
                        // inventing films when a damaged/incomplete APK is used.
                    }
                }
            }.also { cached = it }
        }
    }

    fun normalized(text: String): String = Normalizer.normalize(
        text.lowercase(Locale.ROOT).replace('ı', 'i'), Normalizer.Form.NFD
    ).replace(Regex("\\p{M}+"), "")

    fun genreParts(group: String): List<String> = group.split('|')
        .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy(::normalized)

    fun filtered(rows: List<EmbeddedFilm>, query: String, category: String?): List<EmbeddedFilm> {
        val needle = normalized(query.trim())
        return rows.filter { film ->
            (needle.isEmpty() || normalized(film.title).contains(needle) ||
                normalized(film.group).contains(needle)) &&
                (category.isNullOrBlank() || genreParts(film.group).any {
                    normalized(it) == normalized(category)
                })
        }
    }

    fun groups(rows: List<EmbeddedFilm>, query: String): List<MediaLibraryGroup> {
        val needle = normalized(query.trim())
        return rows.asSequence().filter {
            needle.isEmpty() || normalized(it.title).contains(needle) ||
                normalized(it.group).contains(needle)
        }.flatMap { genreParts(it.group).asSequence() }.groupingBy { it }.eachCount()
            .map { MediaLibraryGroup(it.key, it.value) }
            .sortedWith(compareByDescending<MediaLibraryGroup> { it.count }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
}
