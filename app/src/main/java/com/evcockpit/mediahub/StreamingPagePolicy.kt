package com.evcockpit.mediahub

import java.net.URI
import java.util.Locale

/**
 * Recognizes only explicit Netflix / Prime Video title pages.
 * Query strings, fragments, cookies and tracking tokens are never persisted.
 * This is bookmark/history navigation, NOT a playback position or DRM interface.
 */
enum class StreamingService(val displayName: String) {
    NETFLIX("Netflix"), PRIME_VIDEO("Prime Video")
}

data class StreamingPage(val service: StreamingService, val url: String)

object StreamingPagePolicy {
    private val titleId = Regex("[A-Za-z0-9_-]{6,96}")
    private val digits = Regex("[0-9]{4,15}")
    private val netflixHosts = setOf("netflix.com", "www.netflix.com")
    private val primeHosts = setOf("primevideo.com", "www.primevideo.com")
    private val amazonHosts = setOf(
        "amazon.com", "www.amazon.com",
        "amazon.com.tr", "www.amazon.com.tr",
        "amazon.de", "www.amazon.de", "amazon.co.uk", "www.amazon.co.uk"
    )
    private val subscriptionHosts = netflixHosts + primeHosts + amazonHosts

    /** Exact hosts only; no lookalike subdomains or credentials/ports. */
    fun isSubscriptionHost(raw: String): Boolean = try {
        val uri = URI(raw)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null && uri.port in listOf(-1, 443) &&
            uri.host?.lowercase(Locale.ROOT)?.removeSuffix(".") in subscriptionHosts
    } catch (_: Exception) { false }

    fun canonical(raw: String): StreamingPage? {
        if (raw.length !in 1..2048 || raw.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = try { URI(raw) } catch (_: Exception) { return null }
        val host = uri.host?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) return null
        val parts = uri.rawPath.orEmpty().split('/').filter(String::isNotBlank)
        // Netflix often uses a locale prefix such as /tr/title/12345678.
        // History saves only the content ID, never locale, tracking or session params.
        val netflixParts = when {
            parts.size == 2 -> parts
            parts.size == 3 &&
                parts[0].matches(Regex("[a-zA-Z]{2}(-[a-zA-Z]{2})?")) -> parts.drop(1)
            else -> emptyList()
        }
        if (host in netflixHosts && netflixParts.size == 2 &&
            netflixParts[0] in setOf("watch", "title") &&
            digits.matches(netflixParts[1])) {
            return StreamingPage(StreamingService.NETFLIX,
                "https://www.netflix.com/" + netflixParts[0] + "/" + netflixParts[1])
        }
        if (host in primeHosts) {
            // /detail/ASIN and localized /-/tr/detail/ASIN forms.
            val pos = parts.indexOf("detail")
            if (pos >= 0 && pos == parts.lastIndex - 1 &&
                parts.size <= 5 && titleId.matches(parts.last())) {
                return StreamingPage(StreamingService.PRIME_VIDEO,
                    "https://www.primevideo.com/detail/" + parts.last())
            }
        }
        if (host in amazonHosts && parts.size == 4 &&
            parts.take(3) == listOf("gp", "video", "detail") &&
            titleId.matches(parts[3])) {
            // Preserve the user's exact regional Amazon domain, but no query.
            return StreamingPage(StreamingService.PRIME_VIDEO,
                "https://" + host + "/gp/video/detail/" + parts[3])
        }
        return null
    }
}
