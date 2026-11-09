package com.evcockpit.mediahub

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/**
 * Open-Movies metadata from Blender's official public PeerTube instance.
 * This is a metadata feed; video links are resolved separately on user tap.
 */
object BlenderMovieFeed {
    private const val API="https://video.blender.org/api/v1/search/videos"
    private val executor=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private const val MAX_BYTES=1_300_000

    private fun thumbnail(path:String):String {
        return if(path.startsWith("/") && path.length < 500 &&
            !path.contains("..") && !path.any{it.isISOControl()})
            "https://video.blender.org"+path else ""
    }

    private fun classify(title:String):VideoLibraryKind =
        if(title.contains("Caminandes",true) ||
            title.contains("Laundromat",true)) VideoLibraryKind.SERIES
        else VideoLibraryKind.MOVIE

    fun parse(json:JSONObject):List<OfficialMediaCard> {
        val raw=json.optJSONArray("data")?:return emptyList()
        val results=mutableListOf<OfficialMediaCard>()
        val used=mutableSetOf<String>()
        for(i in 0 until minOf(40,raw.length())){
            val obj=raw.optJSONObject(i)?:continue
            val channel=obj.optJSONObject("channel")?.optString("displayName").orEmpty()
            if(!channel.contains("Blender Open Movies",ignoreCase=true))continue
            val id=obj.optString("uuid")
            if(BlenderOpenVideo.apiPath(id)==null || !used.add(id))continue
            val name=obj.optString("name").take(100).trim()
            if(name.isBlank())continue
            val duration=obj.optInt("duration",0)
            results.add(OfficialMediaCard(
                id="blender:"+id,
                kind=classify(name),
                title=name,
                detail="Blender açık film • "+(duration/60)+" dk",
                provider="Blender Open Movies",
                mode=OfficialMediaMode.OPEN_MOVIE,
                target=id,
                rightNote="Lisans ve film sonu künyeleri korunur",
                imageUrl=thumbnail(obj.optString("thumbnailPath"))
            ))
        }
        return results
    }

    fun load(callback:(List<OfficialMediaCard>)->Unit){
        executor.execute {
            val outcome=try{
                val u=URL(API+"?search="+URLEncoder.encode("Blender Open Movies","UTF-8")+
                    "&count=30&start=0")
                val c=u.openConnection() as HttpsURLConnection
                c.connectTimeout=8_000;c.readTimeout=12_000;c.instanceFollowRedirects=false
                c.setRequestProperty("Accept","application/json")
                try{
                    if(c.responseCode!=200)emptyList() else{
                        val bytes=c.inputStream.use{it.readNBytes(MAX_BYTES+1)}
                        if(bytes.size>MAX_BYTES)emptyList()
                        else parse(JSONObject(String(bytes,Charsets.UTF_8)))
                    }
                }finally{c.disconnect()}
            }catch(_:Exception){emptyList()}
            main.post{callback(outcome)}
        }
    }
}
