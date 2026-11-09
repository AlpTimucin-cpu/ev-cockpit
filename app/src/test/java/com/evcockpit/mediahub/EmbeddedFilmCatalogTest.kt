package com.evcockpit.mediahub

import org.junit.Assert.*
import org.junit.Test

class EmbeddedFilmCatalogTest {
    @Test fun offlineFilmRowsParseUnicodeCommaAndCategories() {
        val text = sequenceOf(
            "Kiralık Koca: Aşk mı, Oyun mu?\tKomedi | Romantik | Yerli Dizi / Film",
            "Bir Gün Daha\tDram | Türkçe Dublaj",
            "https://evil.example/movie.m3u8\tDram",
            "Kötü Satır",
            "Üçüncü\tTürkçe Altyazı"
        )
        val rows = EmbeddedFilmCatalog.parseRows(text)
        assertEquals(3, rows.size)
        assertEquals("Kiralık Koca: Aşk mı, Oyun mu?", rows.first().title)
        assertEquals("Komedi | Romantik | Yerli Dizi / Film", rows.first().group)
        assertEquals(1, EmbeddedFilmCatalog.filtered(rows, "AŞK MI", null).size)
        assertEquals(1, EmbeddedFilmCatalog.filtered(rows, "", "Türkçe Dublaj").size)
        assertEquals(1, EmbeddedFilmCatalog.filtered(rows, "ucuncu", null).size)
    }

    @Test fun genreFacetsSplitPipesAndKeepCounts() {
        val rows = EmbeddedFilmCatalog.parseRows(sequenceOf(
            "A\tDram | Türkçe Dublaj",
            "B\tDram | Türkçe Altyazı",
            "C\tKorku"
        ))
        val groups = EmbeddedFilmCatalog.groups(rows, "")
        assertEquals("Dram", groups.first().name)
        assertEquals(2, groups.first().count)
        assertEquals(1, EmbeddedFilmCatalog.filtered(rows,"","Korku").size)
        assertEquals(0, EmbeddedFilmCatalog.filtered(rows,"","Komedi").size)
    }
}
