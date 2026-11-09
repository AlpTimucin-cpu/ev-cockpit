package com.evcockpit.mediahub

/** Portable IPTV backup including XMLTV channel IDs; no local credentials exported. */
object M3uExporter {
    private fun clean(value: String, limit: Int): String = value
        .replace(Regex("[\r\n\u0000-\u001F]+"), " ")
        .replace('"', '\'')
        .trim().take(limit)

    fun export(items: List<PlaylistEntry>): String = buildString {
        append("#EXTM3U\n")
        items.filter { M3uParser.isHttpsUrl(it.url) }.distinctBy { it.url }.forEach { item ->
            val title = clean(item.name, 160).ifBlank { "Adsız kanal" }
            val group = clean(item.group, 100)
            val id = clean(item.tvgId, 160)
            val logo = item.logo.takeIf { M3uParser.isHttpsUrl(it) }.orEmpty()
            append("#EXTINF:-1 tvg-name=\"").append(title)
                .append("\" tvg-id=\"").append(id)
                .append("\" group-title=\"").append(group)
                .append("\" tvg-logo=\"").append(logo)
                .append("\" radio=\"").append(if (item.isAudio) "true" else "false")
                .append("\",").append(title).append('\n')
            append(item.url.trim()).append('\n')
        }
    }
}
