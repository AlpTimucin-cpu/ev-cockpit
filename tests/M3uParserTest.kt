package com.evcockpit.mediahub

fun main() {
    val content = """
        #EXTM3U
        #EXTINF:-1 tvg-name="Radyo Bir" group-title="Radyo" radio="true",Station One
        https://radio.example.org/live.m3u8?token=abc
        #EXTINF:-1 tvg-name="Haber TV" group-title="Haber",Other Name
        https://tv.example.org/live.m3u8
        #EXTINF:-1 group-title="Diğer",Bad insecure source
        http://tv.example.org/insecure.m3u8
        #EXTINF:-1 group-title="TV",Duplicate
        https://tv.example.org/live.m3u8
        #EXTINF:-1 tvg-name="Doğa sesi" group-title="Doğa",Nature
        https://audio.example.org/ambient.mp3
    """.trimIndent()
    val result = M3uParser.parse(content)
    check(result.size == 3) { "Expected 3 unique HTTPS items, got ${result.size}" }
    check(result[0].name == "Radyo Bir" && result[0].isAudio)
    check(result[1].name == "Haber TV" && !result[1].isAudio)
    check(result[2].isAudio && result[2].group == "Doğa")
    check(M3uParser.parse("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts").isEmpty())
    check(!M3uParser.isHttpsUrl("https://username:password@example.org/path"))
    check(!M3uParser.isHttpsUrl("file:///secret") && !M3uParser.isHttpsUrl("http://foo.org/a"))
    check(M3uParser.parse("#EXTM3U\n#EXTINF:-1,No Url\n#NOTURL").isEmpty())
    println("PASS: M3U metadata, audio/video, duplicate handling, HTTPS security, HLS rejection (7 groups)")
}
