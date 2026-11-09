package com.evcockpit.presentation.media

import java.net.URI
import java.util.Locale

enum class MediaSite(val title: String, val url: String, val domains: Set<String>) {
    NETFLIX("Netflix", "https://www.netflix.com/", setOf("netflix.com")),
    DISNEY("Disney+", "https://www.disneyplus.com/", setOf("disneyplus.com", "disney.com", "mydisney.com")),
    PRIME("Prime Video", "https://www.primevideo.com/", setOf(
        "primevideo.com", "amazon.com", "amazon.de", "amazon.co.uk", "amazon.com.tr", "amazonvideo.com"
    )),
    YOUTUBE("YouTube", "https://m.youtube.com/", setOf("youtube.com", "google.com", "youtu.be"))
}

/** Pure policy shared by navigation, DRM permission and restored page checks. */
object MediaSitePolicy {
    private fun secureUri(raw: String?): URI? = runCatching {
        require(!raw.isNullOrBlank() && raw.length <= 8192 && raw.none(Char::isISOControl))
        URI(raw).also {
            require(it.scheme.equals("https", true) && !it.host.isNullOrBlank())
            require(it.rawUserInfo == null && it.port in setOf(-1, 443))
        }
    }.getOrNull()

    fun allows(site: MediaSite, url: String?): Boolean {
        val host = secureUri(url)?.host?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: return false
        return site.domains.any { host == it || host.endsWith(".$it") }
    }

    fun grantsProtectedMedia(site: MediaSite, page: String?, origin: String?): Boolean {
        if (!allows(site, page) || !allows(site, origin)) return false
        val a = secureUri(page) ?: return false
        val b = secureUri(origin) ?: return false
        return a.host.equals(b.host, true)
    }

    /** Retain the installed WebView's Chromium version rather than pinning an old browser. */
    fun userAgent(site: MediaSite, defaultAgent: String): String {
        if (site != MediaSite.NETFLIX) return defaultAgent
        return defaultAgent.replace(Regex("\\([^)]*\\)"), "(X11; Linux x86_64)")
            .replace("; wv", "").replace("Version/4.0 ", "").replace(" Mobile", "")
    }
}
