package com.evcockpit.mediahub

import org.junit.Assert.*
import org.junit.Test

class UserMediaApiImporterTest {
    @Test fun importsCommonJsonEnvelopeAndFiltersHttpOrInvalidEntries() {
        val json = """
          {"data":{"items":[
            {"name":"Lisanslı Film","stream_url":"https://media.example.org/film.mp4","category":"Filmler","poster":"https://media.example.org/poster.jpg"},
            {"title":"Yayın B","playUrl":"https://media.example.org/sport.m3u8","group_title":"Spor"},
            {"name":"Güvensiz","url":"http://example.org/cleartext.mp4"},
            {"name":"Yinelenen","url":"https://media.example.org/film.mp4"}
          ]}}
        """.trimIndent()
        val result=UserMediaApiImporter.parse(json)
        assertEquals(2,result.size)
        assertEquals("Lisanslı Film",result[0].name)
        assertEquals("Filmler",result[0].group)
        assertEquals("https://media.example.org/poster.jpg",result[0].logo)
        assertEquals("Spor",result[1].group)
    }

    @Test fun acceptsRootJsonArrayAndM3uButNotHtml() {
        assertEquals(1,UserMediaApiImporter.parse(
            """[{"title":"Demo","url":"https://example.org/live.m3u8"}]""").size)
        val m3u="#EXTM3U\n#EXTINF:-1 group-title=\"Dram\",Demo\nhttps://example.org/movie.m3u8\n"
        assertEquals(1,UserMediaApiImporter.parse(m3u).size)
        assertTrue(UserMediaApiImporter.parse("<html></html>").isEmpty())
    }

    @Test fun readsNestedSeriesAndMultiSourceJsonWithoutBlocking() {
        val response = """
        {"response":{"catalog":{"series":[
          {"title":"Bir Dizi","category":{"name":"Dram"},"seasons":[
            {"episodes":[{"title":"Birinci Bölüm","sources":[
              {"file":"https://media.example.org/s01e01.m3u8"},
              {"file":"http://unsafe.example.org/old.m3u8"}]}]}]}
        ]}}}
        """.trimIndent()
        val entries=UserMediaApiImporter.parse(response)
        assertEquals(1,entries.size)
        assertEquals("Birinci Bölüm",entries[0].name)
        assertEquals("Dram",entries[0].group)
    }

    @Test fun handlesNewlineDelimitedJsonAndExtensionlessJsonApi() {
        val lines = """
        {"name":"Film 1","url":"https://media.example.org/film1.mp4"}
        {"name":"Film 2","url":"https://media.example.org/film2.m3u8"}
        """.trimIndent()
        val parsed=UserMediaApiImporter.parse(lines)
        assertEquals(2,parsed.size)
        assertEquals("Film 2",parsed[1].name)
        assertTrue(UserMediaApiImporter.parse("<html>blocked</html>").isEmpty())
    }

}
