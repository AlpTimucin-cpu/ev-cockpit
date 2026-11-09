package com.evcockpit.mediahub

fun main() {
    val original = listOf(
        PlaylistEntry("Radyo \"Bir\"", "https://radio.example.com/live.mp3", "Radyo Türkiye", true),
        PlaylistEntry("Haber TV", "https://tv.example.com/live.m3u8?token=a", "Haber", false),
        PlaylistEntry("Invalid", "http://invalid.example.com/a", "Bad", false),
        PlaylistEntry("Duplicate", "https://radio.example.com/live.mp3", "Bad", true),
        PlaylistEntry("Satır\nEnjeksiyon", "https://safe.example.com/stream.mp3", "Radyo\nBad", true)
    )
    val exported = M3uExporter.export(original)
    check(exported.startsWith("#EXTM3U\n"))
    check(!exported.contains("http://invalid"))
    check(!exported.contains("Bad\n"))
    val parsed = M3uParser.parse(exported)
    check(parsed.size == 3) { "3 unique HTTPS entries expected, got ${parsed.size}" }
    check(parsed[0].isAudio && parsed[0].name == "Radyo 'Bir'")
    check(!parsed[1].isAudio && parsed[1].name == "Haber TV")
    check(parsed[2].isAudio)
    println("PASS: round-trip M3U export/import, deduplication, security, metadata escaping")
}
