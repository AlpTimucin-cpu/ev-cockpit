package com.evcockpit.mediahub

import org.junit.Assert.*
import org.junit.Test

/** Synthetic examples of four-line M3U files; never stores any third-party streams. */
class RecStyleM3uTest {
    @Test fun fileWithExtVlcOptFieldsImportsVideoAndGenres() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-logo="https://example.org/poster1.jpg" group-title="Dram | Türkçe Dublaj | Türkçe Altyazı",Deneme Filmi
            #EXTVLCOPT:http-user-agent=example-agent
            #EXTVLCOPT:http-referrer=https://example.org/
            https://licensed.example.org/film1/index.m3u8
            #EXTINF:-1 group-title="Komedi | Yerli Dizi / Film",İkinci Film
            #EXTVLCOPT:http-user-agent=example-agent
            #EXTVLCOPT:http-referrer=https://example.org/
            https://licensed.example.org/film2/index.m3u8
        """.trimIndent()
        val parsed = M3uParser.parse(m3u)
        assertEquals(2, parsed.size)
        assertEquals("Deneme Filmi", parsed[0].name)
        assertEquals("Dram | Türkçe Dublaj | Türkçe Altyazı", parsed[0].group)
        assertEquals("https://example.org/poster1.jpg", parsed[0].logo)
        assertEquals("https://licensed.example.org/film2/index.m3u8", parsed[1].url)
        assertTrue(parsed.none { it.isAudio })

        // The film type is chosen by the user, not silently guessed from genres.
        val imported = parsed.mapIndexed { i, entry ->
            VideoChannel("test-$i", entry.name, entry.url, entry.group, false,
                entry.logo, entry.tvgId, VideoLibraryKind.MOVIE)
        }
        val result = MediaLibraryBrowsePlanner.plan(imported, VideoLibraryKind.MOVIE)
        assertEquals(2, result.total)
        assertEquals(1, MediaLibraryBrowsePlanner.plan(imported, VideoLibraryKind.MOVIE,
            category = "Dram").total)
        assertEquals(1, MediaLibraryBrowsePlanner.plan(imported, VideoLibraryKind.MOVIE,
            category = "Türkçe Altyazı").total)
    }
}
