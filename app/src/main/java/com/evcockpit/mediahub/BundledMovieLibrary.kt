package com.evcockpit.mediahub

import android.content.Context
import java.net.URI

/**
 * User-supplied M3U packaged at build time, never committed to the public source
 * repository. It is parsed on a worker thread and kept only in app memory.
 * A media address being listed does not imply it is playable or licensed.
 */
object BundledMovieLibrary {
    const val ASSET_NAME = "filmler_full.m3u"
    data class Result(val entries: List<PlaylistEntry>,
                      val byName: Map<String, List<PlaylistEntry>>,
                      val problem: String? = null) {
        val size get() = entries.size
    }
    @Volatile private var cache: Result? = null

    fun peek(): Result? = cache

    /** Parse in the background; never flood SharedPreferences with 7K signed URLs. */
    fun load(context: Context): Result {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: try {
                val text = context.assets.open(ASSET_NAME).use {
                    PlaylistLoader.readLimited(it)
                }
                parse(text).also { cache = it }
            } catch (_: Exception) {
                Result(emptyList(), emptyMap(), "APK içinde tam M3U bulunamadı")
                    .also { cache = it }
            }
        }
    }

    fun parse(text: String): Result {
        val entries = M3uParser.parse(text).filterNot { it.isAudio }
        val index = entries.groupBy {
            EmbeddedFilmCatalog.normalized(it.name)
        }
        return Result(entries, index,
            if (entries.isEmpty()) "Paketlenen M3U video bağlantısı içermiyor" else null)
    }

    fun find(catalogueFilm: EmbeddedFilm, result: Result): PlaylistEntry? {
        val normalized = EmbeddedFilmCatalog.normalized(catalogueFilm.title.take(100))
        val choices = result.byName[normalized].orEmpty()
        if (choices.size <= 1) return choices.firstOrNull()
        val group = EmbeddedFilmCatalog.normalized(catalogueFilm.group.take(100))
        return choices.firstOrNull {
            EmbeddedFilmCatalog.normalized(it.group) == group
        } ?: choices.first()
    }

    /** 'tms' is an age indicator; it is NOT a verifiable expiry timestamp. */
    fun timestampSeconds(url: String): Long? = try {
        URI(url).rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("tms=") }
            ?.substringAfter('=')
            ?.toLongOrNull()
    } catch (_: Exception) { null }
}
