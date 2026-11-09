package com.evcockpit.mediahub

/**
 * Lightweight, local M3U TV channel list. Only HTTPS user-authorized
 * streams are accepted. The playlist is opened from Android's document picker;
 * this class never scrapes external providers or downloads unknown catalogues.
 */
object MediaEngineTvPlaylist {
    data class Channel(val title:String, val group:String, val url:String)
    data class Result(val channels:List<Channel>, val unsafeUrlsSkipped:Int,
                      val totalEntries:Int)

    fun parse(input:String, maxEntries:Int=3500):Result {
        require(input.length <= 4_000_000) { "Liste 4 MB sınırını aşıyor" }
        val result=ArrayList<Channel>()
        val known=HashSet<String>()
        var title=""
        var group="Genel"
        var skipped=0
        var total=0
        val groupMatch=Regex("""group-title\s*=\s*"([^"]{0,130})"""",RegexOption.IGNORE_CASE)
        val nameMatch=Regex("""tvg-name\s*=\s*"([^"]{0,130})"""",RegexOption.IGNORE_CASE)
        for(line in input.lineSequence()) {
            val trimmed=line.trim()
            if(trimmed.startsWith("#EXTINF:",ignoreCase=true)) {
                title=trimmed.substringAfterLast(',',"").trim().take(140)
                    .ifBlank { nameMatch.find(trimmed)?.groupValues?.get(1).orEmpty() }
                group=groupMatch.find(trimmed)?.groupValues?.get(1)?.trim()
                    .orEmpty().ifBlank { "Genel" }.take(75)
                continue
            }
            if(trimmed.isBlank() || trimmed.startsWith('#'))continue
            // Only interpret an entry as a stream URL; reject unsafe schemes.
            total++
            val lower=trimmed.lowercase(java.util.Locale.ROOT)
            if(!lower.startsWith("https://") ||
                trimmed.any{it=='\u0000'||it=='\r'||it=='\n'}) {
                skipped++
                title=""
                group="Genel"
                continue
            }
            val uri=try{java.net.URI(trimmed)}catch(_:Exception){null}
            if(uri==null || !uri.scheme.equals("https",ignoreCase=true) ||
                uri.host.isNullOrBlank() || uri.userInfo!=null ||
                trimmed.length>2500) {
                skipped++
                title=""
                group="Genel"
                continue
            }
            if(known.add(trimmed) && result.size<maxEntries) {
                result.add(Channel(
                    title.ifBlank{"TV kanalı "+(result.size+1)},
                    group,trimmed))
            }
            title=""
            group="Genel"
            if(total>=maxEntries*2)break
        }
        return Result(result,skipped,total)
    }

    fun filter(channels:List<Channel>,term:String,limit:Int=70):List<Channel>{
        val q=term.trim()
        return channels.asSequence()
            .filter{q.isEmpty() || it.title.contains(q,true) ||
                it.group.contains(q,true)}
            .take(limit.coerceIn(1,150)).toList()
    }
}
