package com.evcockpit.mediahub

import org.junit.Assert.*
import org.junit.Test

class MediaLibraryBrowsePlannerTest {
    private val sources = listOf(
        VideoChannel("a", "Film A", "https://example.com/a.mp4", "Filmler / Dram", true),
        VideoChannel("b", "Film B", "https://example.com/b.mp4", "Filmler / Dram", false),
        VideoChannel("c", "Film C", "https://example.com/c.mp4", "Filmler / Komedi", true),
        VideoChannel("s", "Dizi S01E01", "https://example.com/s.mp4", "Diziler / Dram", true),
        VideoChannel("m", "Maç", "https://example.com/m.m3u8", "Spor / Maçlar", false)
    )

    @Test fun categoriesComeFromActualImportedLibraryOnly() {
        val result = MediaLibraryBrowsePlanner.plan(sources, VideoLibraryKind.MOVIE)
        assertEquals(3, result.total)
        assertEquals(listOf("Filmler / Dram", "Filmler / Komedi"),
            result.categories.map { it.name })
        assertEquals(listOf(2, 1), result.categories.map { it.count })
        assertTrue(result.continueWatching.isEmpty())
    }

    @Test fun favoritesAndSearchRespectKindAndCategory() {
        val result = MediaLibraryBrowsePlanner.plan(
            sources, VideoLibraryKind.MOVIE, "Film", true, "Filmler / Komedi"
        )
        assertEquals(listOf("c"), result.entries.map { it.id })
        assertEquals(1, result.total)
        assertEquals(listOf("a","c"), MediaLibraryBrowsePlanner.plan(
            sources, VideoLibraryKind.MOVIE, favoritesOnly = true
        ).entries.map { it.id })
    }

    @Test fun continueWatchingUsesRecentOrderAndNeverDuplicatesItems() {
        val result = MediaLibraryBrowsePlanner.plan(
            sources, VideoLibraryKind.MOVIE,
            recentIds = listOf("c", "missing", "a", "c", "s"),
            resumableIds = setOf("a", "c", "s")
        )
        assertEquals(listOf("c","a"), result.continueWatching.map { it.id })
        assertEquals(listOf("b"), result.entries.map { it.id })
        assertEquals(3, result.total)
    }

    @Test fun matchesNeverExposeResumeForLiveSport() {
        val result = MediaLibraryBrowsePlanner.plan(
            sources, VideoLibraryKind.SPORT,
            recentIds = listOf("m"), resumableIds = setOf("m")
        )
        assertTrue(result.continueWatching.isEmpty())
        assertEquals(listOf("m"), result.entries.map { it.id })
    }

    @Test fun officialFavoriteIdsRejectUnsafeKeys() {
        assertTrue(OfficialMediaFavorites.isSafeId("tabii_gassal"))
        assertFalse(OfficialMediaFavorites.isSafeId("https://example.com?token=private"))
        assertFalse(OfficialMediaFavorites.isSafeId(""))
        assertFalse(OfficialMediaFavorites.isSafeId("".padEnd(81, 'a')))
    }
}
