package com.evcockpit.mediahub

import java.net.URI
import java.util.Locale

/** Limit in-app SportzX web navigation to its informational HTTPS site. */
object SportzXSitePolicy {
    const val HOME = "https://sportzxlive.com/"
    private val HOSTS = setOf("sportzxlive.com", "www.sportzxlive.com")

    fun allow(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.rawUserInfo == null && uri.port in listOf(-1, 443) &&
            uri.host?.lowercase(Locale.ROOT) in HOSTS
    } catch (_: Exception) {
        false
    }
}
