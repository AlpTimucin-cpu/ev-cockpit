package com.evcockpit.mediahub

/** User-selectable public playlists. Availability and broadcaster rights vary by country. */
data class PublicPlaylistSource(val name: String, val url: String, val description: String)

object TrustedSources {
    const val TURKEY_PUBLIC = "https://iptv-org.github.io/iptv/countries/tr.m3u"
    const val NEWS_PUBLIC = "https://iptv-org.github.io/iptv/categories/news.m3u"
    // Apple's own HLS test asset; confirms decoder/network on the phone, NOT a live TV broadcast.
    // Public HLS endpoint served from TRT's own CDN; availability may be regional or change.
    // Confirm playback on the phone; we cannot live-probe the stream from this environment.
    const val TRT_HABER = "https://tv-trthaber.medya.trt.com.tr/master_720.m3u8"
    const val APPLE_HLS_TEST = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8"
    const val SPORTS_PUBLIC = "https://iptv-org.github.io/iptv/categories/sports.m3u"
    val playlists = listOf(
        PublicPlaylistSource("Türkiye açık yayın listesi", TURKEY_PUBLIC,
            "iptv-org topluluk listesi; tüm kanalların çalışması veya kullanım hakkı garanti edilmez"),
        PublicPlaylistSource("Açık haber kanalları", NEWS_PUBLIC,
            "iptv-org topluluk listesi; bölge ve telif koşullarını yayın bazında kontrol edin"),
        PublicPlaylistSource("Spor kategorisi", SPORTS_PUBLIC,
            "Yalnızca iptv-org kamu listesi; yayınların çalışması veya tüm hakları doğrulanmış değildir")
    )
}
