package com.evcockpit.mediahub

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * User-authorized HTTPS API / JSON / NDJSON / M3U adapter.
 *
 * File extensions (.json / .api) are not API protocols. Only readable and
 * public/documented media responses can be imported without provider-specific
 * credentials, authorization bypass, DRM circumvention or scraping.
 */
object UserMediaApiImporter {
    private const val MAX_ITEMS = 15000
    private const val MAX_NODES = 80000
    private const val MAX_DEPTH = 12
    private val nameFields = listOf("title", "name", "label", "stream_name",
        "streamName", "movie_name", "episode_name", "display_name")
    private val urlFields = listOf("url", "stream_url", "streamUrl", "play_url",
        "playUrl", "media_url", "mediaUrl", "source", "file", "video_url",
        "videoUrl", "src", "hls", "mp4", "link", "playbackUrl")
    private val groupFields = listOf("group", "group_title", "groupTitle",
        "category", "category_name", "genre", "type")
    private val logoFields = listOf("logo", "tvg_logo", "tvgLogo", "poster",
        "poster_url", "image", "thumbnail", "cover")
    private data class Node(val value: Any, val title: String, val group: String, val depth: Int)

    private fun field(json: JSONObject, keys: List<String>): String {
        for(key in keys) {
            val value=json.opt(key)
            if (value is String && value.isNotBlank()) return value.trim()
            if (value is JSONObject && keys===groupFields) {
                val name=value.optString("name", "").trim()
                if (name.isNotBlank()) return name
            }
        }
        return ""
    }

    fun parse(input: String): List<PlaylistEntry> {
        val text=input.trimStart('\uFEFF','\r','\n',' ','\t')
        if (text.startsWith("#EXTM3U",ignoreCase=true))
            return M3uParser.parse(text).filterNot { it.isAudio }
        if (text.startsWith("#EXT-X-",ignoreCase=true)) return emptyList()
        fun ndjson(): JSONArray = JSONArray().apply {
            text.lineSequence().take(MAX_ITEMS).forEach { item ->
                val line=item.trim()
                if(line.isNotBlank()) put(JSONObject(line))
            }
        }
        val ndjsonLines=text.lineSequence().filter { it.isNotBlank() }.take(3).toList()
        val isNdjson=ndjsonLines.size>=2 && ndjsonLines.all {
            val line=it.trim()
            line.startsWith("{") && line.endsWith("}")
        }
        val root = when {
            text.startsWith("[") -> JSONArray(text)
            isNdjson -> ndjson()
            text.startsWith("{") -> try {
                JSONObject(text)
            } catch (_: org.json.JSONException) {
                ndjson()
            }
            else -> return emptyList()
        }
        return extract(root)
    }

    /** Bounded iterative walk accepts common nested catalogue, season and source arrays. */
    fun extract(root: Any): List<PlaylistEntry> {
        val pending=ArrayDeque<Node>()
        pending.addLast(Node(root,"","API",0))
        val found=ArrayList<PlaylistEntry>()
        val seen=HashSet<String>()
        var visited=0
        while(!pending.isEmpty() && visited++ < MAX_NODES && found.size < MAX_ITEMS) {
            val node=pending.removeLast()
            if(node.depth>MAX_DEPTH)continue
            when(val value=node.value) {
                is JSONArray -> {
                    for(i in value.length()-1 downTo 0) {
                        val child=value.opt(i)
                        if(child is JSONObject || child is JSONArray)
                            pending.addLast(Node(child,node.title,node.group,node.depth+1))
                    }
                }
                is JSONObject -> {
                    val ownTitle=field(value,nameFields).ifBlank { node.title }.take(100)
                    val group=field(value,groupFields).ifBlank { node.group }.take(100)
                    val raw=field(value,urlFields)
                    // A video record must have both a name (or inherited movie
                    // title) AND a directly supplied HTTPS playback URL.
                    if(ownTitle.isNotBlank() && M3uParser.isHttpsUrl(raw) && seen.add(raw)) {
                        val logo=field(value,logoFields).takeIf(M3uParser::isHttpsUrl).orEmpty()
                        found.add(PlaylistEntry(ownTitle,raw,group,false,logo,
                            value.optString("tvg-id","").take(160)))
                    }
                    val keys=value.keys().asSequence().toList()
                    for(i in keys.indices.reversed()) {
                        val child=value.opt(keys[i])
                        if(child is JSONObject || child is JSONArray)
                            pending.addLast(Node(child,ownTitle,group,node.depth+1))
                    }
                }
            }
        }
        return found
    }

    /** Caller runs from an IO thread; exceptions never expose signed URLs to UI. */
    fun fetch(url:String):List<PlaylistEntry> {
        require(M3uParser.isHttpsUrl(url)) { "HTTPS API adresi gerekli" }
        return parse(PlaylistLoader.fromHttps(url))
    }
}
