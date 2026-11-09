package com.evcockpit.mediahub

import java.net.URI
import java.util.Locale

/**
 * YouTube page recognition without proprietary JavaScript or playback hooks.
 *
 * Handles normal watch, Shorts, Live and share links. Saves only the 11-character
 * video ID, never tracking parameters, account/cookie data or redirect URLs.
 */
object YoutubePagePolicy {
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val youtubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com")

    fun canonicalWatchUrl(raw: String): String? {
        if (raw.length !in 1..2048 || raw.any { it.isISOControl() || it.isWhitespace() }) {
            return null
        }
        val url = try { URI(raw) } catch (_: Exception) { return null }
        val host = url.host?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: return null
        if (!url.scheme.equals("https", ignoreCase = true) ||
            url.rawUserInfo != null || url.port !in listOf(-1, 443)) return null

        val segments = url.rawPath.orEmpty().split('/').filter { it.isNotBlank() }
        val id = when {
            host == "youtu.be" && segments.size == 1 -> segments.first()
            host in youtubeHosts && segments.size == 1 && segments[0] == "watch" ->
                url.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { keyValue ->
                    keyValue.substringAfter('=', "").takeIf {
                        keyValue.substringBefore('=') == "v"
                    }
                }
            host in youtubeHosts && segments.size == 2 &&
                segments[0] in setOf("shorts", "live", "embed") -> segments[1]
            else -> null
        }
        if (id == null || !videoId.matches(id)) return null
        return "https://www.youtube.com/watch?v=$id"
    }

    fun isRecognizedVideo(raw: String) = canonicalWatchUrl(raw) != null
}
