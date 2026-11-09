package com.evcockpit.mediahub

import java.util.Locale

/**
 * Country and genre indices for community-listed public streams.
 * This is a catalogue directory, not a promise of availability or broadcast rights.
 * No premium broadcaster is bundled, impersonated or circumvented.
 */
object WorldCatalog {
    data class Source(val name: String, val url: String, val kind: String)

    fun countryCodeUrl(code: String): String {
        require(code.matches(Regex("[A-Za-z]{2}"))) { "ISO 3166-1 alpha-2 country code required" }
        return "https://iptv-org.github.io/iptv/countries/" + code.lowercase(Locale.ROOT) + ".m3u"
    }

    fun countries(locale: Locale = Locale.forLanguageTag("tr-TR")): List<Source> =
        Locale.getISOCountries().map { code ->
            val nativeName = Locale("", code).getDisplayCountry(locale)
                .takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
                ?: Locale("", code).getDisplayCountry(Locale.ENGLISH)
            Source(nativeName, countryCodeUrl(code), "country")
        }.sortedBy { it.name.lowercase(locale) }

    val genres: List<Source> = listOf(
        Source("Spor (açık yayınlar)", "https://iptv-org.github.io/iptv/categories/sports.m3u", "genre"),
        Source("Haber", "https://iptv-org.github.io/iptv/categories/news.m3u", "genre"),
        Source("Belgesel", "https://iptv-org.github.io/iptv/categories/documentary.m3u", "genre"),
        Source("Müzik", "https://iptv-org.github.io/iptv/categories/music.m3u", "genre"),
        Source("Sinema", "https://iptv-org.github.io/iptv/categories/movies.m3u", "genre"),
        Source("Çocuk & Animasyon", "https://iptv-org.github.io/iptv/categories/animation.m3u", "genre"),
        Source("Eğitim", "https://iptv-org.github.io/iptv/categories/education.m3u", "genre"),
        Source("Kültür", "https://iptv-org.github.io/iptv/categories/culture.m3u", "genre"),
        Source("Eğlence", "https://iptv-org.github.io/iptv/categories/entertainment.m3u", "genre")
    )
}
