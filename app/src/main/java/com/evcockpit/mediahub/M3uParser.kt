package com.evcockpit.mediahub

/** Pure Kotlin parser for user-owned M3U IPTV lists, intentionally independent of Android. */
data class PlaylistEntry(
    val name: String,
    val url: String,
    val group: String,
    val isAudio: Boolean,
    val logo: String = "",
    val tvgId: String = ""
)

object M3uParser {
    private val attribute = Regex("([A-Za-z][A-Za-z0-9_-]*)=\"([^\"]*)\"")
    private val audioGroups = setOf("radio", "radyo", "internet radio", "internet radyo", "audio", "podcast", "podcasts")
    private val audioExtensions = setOf("mp3", "aac", "ogg", "opus", "flac", "m4a")
    // Local files can have thousands of individually licensed VOD entries.
    private const val MAX_ENTRIES = 15000
    /**
     * EXTINF labels are separated by the first comma OUTSIDE quoted attributes.
     * A user-agent value can contain its own comma ("like Gecko, ...").
     */
    private fun labelPart(line: String): String {
        var quoted = false
        var escaped = false
        for (index in line.indices) {
            val ch = line[index]
            if (escaped) { escaped = false; continue }
            if (ch == '\\' && quoted) { escaped = true; continue }
            if (ch == '"') quoted = !quoted
            if (ch == ',' && !quoted) {
                val candidate = line.substring(index + 1).trim()
                // Some lists contain malformed, unquoted metadata. Prefer the final name.
                return if (candidate.contains("group-title=", true) ||
                    candidate.contains("tvg-name=", true) ||
                    candidate.contains("user-agent=", true)) candidate.substringAfterLast(',').trim()
                    else candidate
            }
        }
        return ""
    }

    /** Clean previously saved titles from the old parser without destroying user-defined names. */
    fun displayTitle(raw: String): String {
        val contaminated = listOf("group-title=", "user-agent=", "tvg-name=").any {
            raw.contains(it, ignoreCase = true)
        }
        val candidate = if (contaminated && ',' in raw) raw.substringAfterLast(',') else raw
        return cleanName(candidate, "Kanal")
    }

    private fun cleanName(raw: String, fallback: String): String =
        raw.replace(Regex("[\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ").trim().take(100).ifBlank { fallback }


    /**
     * Discover a playlist-advertised XMLTV guide without fetching it or sending credentials.
     * Common providers embed x-tvg-url/url-tvg/tvg-url in the #EXTM3U header.
     * This is a SUGGESTION only; a user must approve the HTTPS download.
     */
    fun suggestedGuideUrl(text: String): String? {
        val header = text.lineSequence().take(6)
            .map { it.trim().removePrefix("\uFEFF") }
            .firstOrNull { it.startsWith("#EXTM3U", ignoreCase = true) }
            ?.take(4096) ?: return null
        val attributes = attribute.findAll(header).associate {
            it.groupValues[1].lowercase() to it.groupValues[2].trim()
        }
        return listOf("x-tvg-url", "url-tvg", "tvg-url")
            .asSequence()
            .mapNotNull { attributes[it] }
            .flatMap { it.split(',').asSequence() }
            .map { it.trim() }
            .firstOrNull(::isHttpsUrl)
    }

    fun parse(text: String): List<PlaylistEntry> {
        // HLS manifests describe stream segments, not an IPTV channel catalogue.
        if (text.lineSequence().any { it.trim().startsWith("#EXT-X-", ignoreCase = true) }) return emptyList()
        val result = mutableListOf<PlaylistEntry>()
        val used = mutableSetOf<String>()
        var title: String? = null
        var group = "Diğer"
        var audioHint = false
        var logo = ""
        var tvgId = ""
        var waiting = false
        for (line in text.lineSequence()) {
            val current = line.trim().removePrefix("\uFEFF")
            if (current.startsWith("#EXTINF:", ignoreCase = true)) {
                val attrs = attribute.findAll(current).associate {
                    it.groupValues[1].lowercase() to it.groupValues[2].trim()
                }
                group = attrs["group-title"].orEmpty().ifBlank { "Diğer" }.take(100)
                logo = attrs["tvg-logo"].orEmpty().takeIf { isHttpsUrl(it) }.orEmpty()
                tvgId = attrs["tvg-id"].orEmpty().trim().take(160)
                val label = labelPart(current)
                title = cleanName(attrs["tvg-name"].orEmpty().ifBlank { label }, "")
                audioHint = attrs["radio"].equals("true", true) || attrs["radio"] == "1" ||
                    attrs["type"].equals("radio", true) || attrs["tvg-type"].equals("radio", true)
                waiting = true
            } else if (current.startsWith("#EXTGRP:", ignoreCase = true) && waiting) {
                group = current.substringAfter(':').trim().ifBlank { group }.take(100)
            } else if (waiting && current.isNotEmpty() && !current.startsWith('#')) {
                val url = current
                if (isHttpsUrl(url) && used.add(url)) {
                    val path = url.substringBefore('?').substringBefore('#').lowercase()
                    val extension = path.substringAfterLast('.', "")
                    val normalizedGroup = group.trim().lowercase()
                    val groupIsAudio = audioGroups.any { normalizedGroup == it || normalizedGroup.startsWith("$it ") || normalizedGroup.startsWith("$it-") }
                    val clearAudio = audioHint || groupIsAudio || extension in audioExtensions
                    val clearlyVideo = extension in setOf("mp4", "mkv", "mov", "avi", "webm", "ts")
                    result.add(PlaylistEntry(title.orEmpty().ifBlank { "Kanal ${result.size + 1}" },
                        url, group, clearAudio && !clearlyVideo, logo, tvgId))
                }
                waiting = false
                title = null
                group = "Diğer"
                audioHint = false
                logo = ""
                tvgId = ""
                if (result.size >= MAX_ENTRIES) break
            }
        }
        return result
    }

    /** Network sources must be HTTPS and must have a hostname. */
    fun isHttpsUrl(url: String): Boolean = try {
        val address = java.net.URI(url)
        address.scheme.equals("https", ignoreCase = true) && !address.host.isNullOrBlank() &&
            address.rawUserInfo == null && url.length <= 2048
    } catch (_: Exception) { false }
}
