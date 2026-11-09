package com.evcockpit.mediahub

import org.junit.Assert.*
import org.junit.Test

/** The real bundled playlist is checked by CI against the user's SHA-256. */
class BundledMovieLibraryTest {
    @Test fun embeddedPlaylistResolvesTitlesWithoutStoringSignedUrlsInMetadata() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="Aksiyon | Türkçe Dublaj",Film Örneği
            #EXTVLCOPT:http-user-agent=example
            #EXTVLCOPT:http-referrer=https://example.test
            https://licensed.example.org/media/movie.mp4?tms=1788032805
            #EXTINF:-1 group-title="Komedi",Diğer Film
            https://licensed.example.org/media/second.m3u8
        """.trimIndent()
        val parsed=BundledMovieLibrary.parse(playlist)
        assertEquals(2,parsed.size)
        assertEquals(
            "https://licensed.example.org/media/movie.mp4?tms=1788032805",
            BundledMovieLibrary.find(
                EmbeddedFilm("Film Örneği","Aksiyon | Türkçe Dublaj"),parsed
            )?.url
        )
        assertNull(BundledMovieLibrary.find(EmbeddedFilm("Listede Yok","Dram"),parsed))
        assertEquals(1788032805L,BundledMovieLibrary.timestampSeconds(parsed.entries[0].url))
        assertNull(BundledMovieLibrary.timestampSeconds(parsed.entries[1].url))
    }
}
