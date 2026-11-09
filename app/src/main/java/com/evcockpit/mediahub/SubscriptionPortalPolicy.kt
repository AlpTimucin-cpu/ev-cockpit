package com.evcockpit.mediahub

import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * Official-site navigation for the phone-side, MediaEngine-style Netflix / Prime Video
 * portals. No APIs, DRM, credentials, or streaming content are intercepted.
 */
object SubscriptionPortalPolicy {
    fun homepage(service: StreamingService): String = when (service) {
        StreamingService.NETFLIX -> "https://www.netflix.com/tr/"
        StreamingService.PRIME_VIDEO -> "https://www.primevideo.com/"
    }

    fun search(service: StreamingService, query: String): String? {
        val q = query.trim().take(120)
        if (q.isEmpty() || q.any { it.isISOControl() }) return null
        val encoded = URLEncoder.encode(q, "UTF-8")
        return when (service) {
            StreamingService.NETFLIX -> "https://www.netflix.com/search?q=" + encoded
            // Prime's search page is a web navigation hint, not a documented API.
            StreamingService.PRIME_VIDEO -> "https://www.primevideo.com/search?phrase=" + encoded
        }
    }

    fun isAllowed(service: StreamingService, raw: String): Boolean {
        if (raw.isBlank() || raw.length > 2048 || raw.any(Char::isISOControl)) return false
        val uri = try { URI(raw) } catch (_: Exception) { return false }
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.port !in listOf(-1, 443)) return false
        val host = uri.host.lowercase(Locale.ROOT).removeSuffix(".")
        return when (service) {
            StreamingService.NETFLIX ->
                host == "netflix.com" || host.endsWith(".netflix.com")
            StreamingService.PRIME_VIDEO ->
                host == "primevideo.com" || host.endsWith(".primevideo.com") ||
                    host in setOf(
                        "amazon.com", "www.amazon.com", "amazon.com.tr", "www.amazon.com.tr",
                        "amazon.de", "www.amazon.de", "amazon.co.uk", "www.amazon.co.uk",
                        "amazon.fr", "www.amazon.fr", "amazon.it", "www.amazon.it",
                        "amazon.es", "www.amazon.es"
                    )
        }
    }

    fun officialPackage(service: StreamingService): String = when (service) {
        StreamingService.NETFLIX -> InstalledMediaApps.NETFLIX_PACKAGE
        StreamingService.PRIME_VIDEO -> InstalledMediaApps.PRIME_VIDEO_PACKAGE
    }
}
