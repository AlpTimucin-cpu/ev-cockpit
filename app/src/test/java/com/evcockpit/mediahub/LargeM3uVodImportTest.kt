package com.evcockpit.mediahub

import org.junit.Assert.*
import org.junit.Test

class LargeM3uVodImportTest {
    @Test fun largeM3uLikeASevenThousandMovieFileIsNotCutOff() {
        val source = buildString {
            append("#EXTM3U\n")
            repeat(7232) { index ->
                append("#EXTINF:-1 tvg-logo=\"https://example.org/posters/$index.jpg\" ")
                append("group-title=\"Dram | Türkçe Dublaj\",Film $index\n")
                append("https://licensed.example.org/movies/$index/index.m3u8\n")
            }
        }
        val parsed = M3uParser.parse(source)
        assertEquals(7232, parsed.size)
        assertEquals("Dram | Türkçe Dublaj", parsed[0].group)
        assertEquals("https://example.org/posters/0.jpg", parsed[0].logo)
        assertEquals("Film 7231", parsed.last().name)
        assertTrue(parsed.all { !it.isAudio })
    }

    @Test fun explicitImportedMovieTypeWorksWithoutFilmWordInGenre() {
        val channel = VideoChannel(
            "entry-1", "Sinema Örneği", "https://licensed.example.org/movie.m3u8",
            "Aksiyon | Dram | Türkçe Altyazı", false,
            kindOverride = VideoLibraryKind.MOVIE
        )
        assertEquals(VideoLibraryKind.MOVIE, VideoLibraryClassifier.classify(channel))
        val result = MediaLibraryBrowsePlanner.plan(listOf(channel), VideoLibraryKind.MOVIE)
        assertEquals(1, result.total)
        assertEquals(listOf("Aksiyon", "Dram", "Türkçe Altyazı"),
            result.categories.map { it.name })
        assertEquals(listOf("entry-1"), MediaLibraryBrowsePlanner.plan(
            listOf(channel), VideoLibraryKind.MOVIE, category = "Dram"
        ).entries.map { it.id })
        assertEquals(listOf("entry-1"), MediaLibraryBrowsePlanner.plan(
            listOf(channel), VideoLibraryKind.MOVIE, category = "Türkçe Altyazı"
        ).entries.map { it.id })
    }

    @Test fun m3uRejectsUnsecureAndHlsSegmentPlaylists() {
        val malformed = "#EXTM3U\n#EXTINF:-1 group-title=\"Dram\",A\nhttp://invalid.example/a.m3u8\n"
        assertTrue(M3uParser.parse(malformed).isEmpty())
        val hls = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=600000\nhttps://example.org/master.m3u8"
        assertTrue(M3uParser.parse(hls).isEmpty())
    }

    @Test fun previouslyImportedUnclassifiedTvEntryStaysOther() {
        val tv = VideoChannel("tv", "Some stream", "https://licensed.example.org/live.m3u8",
            "Dram | Türkçe Dublaj", false)
        assertEquals(VideoLibraryKind.OTHER, VideoLibraryClassifier.classify(tv))
    }
}
