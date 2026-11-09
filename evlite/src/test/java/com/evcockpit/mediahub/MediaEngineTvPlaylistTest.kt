package com.evcockpit.mediahub

import org.junit.Assert.*
import org.junit.Test

class MediaEngineTvPlaylistTest {
    @Test fun parsesGroupedHttpsTvOnly() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-name="Haber 1" group-title="Haber",Haber HD
            https://stream.example.org/live/a.m3u8
            #EXTINF:-1 group-title="Spor",Spor
            http://unsafe.example.org/live.m3u8
            #EXTINF:-1,Canlı
            https://stream.example.org/live/c.m3u8
        """.trimIndent()
        val r=MediaEngineTvPlaylist.parse(m3u)
        assertEquals(2,r.channels.size)
        assertEquals("Haber",r.channels[0].group)
        assertEquals("Haber HD",r.channels[0].title)
        assertEquals(1,r.unsafeUrlsSkipped)
        assertEquals(3,r.totalEntries)
        assertEquals(1,MediaEngineTvPlaylist.filter(r.channels,"haber").size)
    }
    @Test fun rejectsDangerousContentAndEmbeddedCredentials() {
        val input="""
            #EXTINF:-1,Bad
            javascript:alert(1)
            #EXTINF:-1,Bad
            https://user:password@example.com/live
            #EXTINF:-1,OK
            https://example.com/live?id=100
        """.trimIndent()
        val r=MediaEngineTvPlaylist.parse(input)
        assertEquals(1,r.channels.size)
        assertEquals(2,r.unsafeUrlsSkipped)
    }
    @Test fun handlesDuplicateAndEmpty() {
        assertTrue(MediaEngineTvPlaylist.parse("").channels.isEmpty())
        val r=MediaEngineTvPlaylist.parse("https://example.com/abc\nhttps://example.com/abc")
        assertEquals(1,r.channels.size)
    }
}
