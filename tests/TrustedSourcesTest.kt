package com.evcockpit.mediahub

fun main() {
    require(TrustedSources.playlists.size == 2)
    require(TrustedSources.TURKEY_PUBLIC.endsWith("/countries/tr.m3u"))
    require(TrustedSources.playlists.all { M3uParser.isHttpsUrl(it.url) })
    require(M3uParser.isHttpsUrl(TrustedSources.APPLE_HLS_TEST))
    require(M3uParser.isHttpsUrl(TrustedSources.TRT_HABER))
    require(TrustedSources.playlists.distinctBy { it.url }.size == TrustedSources.playlists.size)
    println("PASS: 2 public source presets TRT Haber URL and Apple HLS phone test stream pass URL checks")
}
