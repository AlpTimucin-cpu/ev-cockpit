package com.evcockpit.mediahub

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/**
 * Fetches the publicly published player URL from Blender's own PeerTube instance.
 * No private API, video scraping, DRM circumvention or third-party service tokens.
 */
object BlenderOpenVideo {
    private const val BASE="https://video.blender.org"
    private const val MAX_BYTES=500_000
    private val idRegex=Regex("[a-zA-Z0-9-]{12,64}")
    private val executor=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())

    fun apiPath(id: String): String? =
        if (idRegex.matches(id)) "/api/v1/videos/" + id else null

    private fun safeStream(raw:String): String? = try {
        val uri=URI(raw)
        val hostname=uri.host?.lowercase()
        // The official PeerTube instance hosts media files as well as its API.
        if(uri.scheme=="https" && hostname=="video.blender.org" &&
            uri.userInfo==null && uri.port in listOf(-1,443) &&
            uri.rawPath.orEmpty().length in 1..600) uri.toASCIIString() else null
    }catch (_:Exception){null}

    /**
     * Preference: adaptive HLS master playlist, otherwise a direct MP4.
     * The server may change its API or CDN host; failure is shown to the user.
     */
    fun extractPlaybackUrl(payload: JSONObject): String? {
        val channel=payload.optJSONObject("channel")
        if (!channel?.optString("displayName").orEmpty()
                .contains("Blender Open Movies",ignoreCase=true)) return null
        val playlist=payload.optJSONArray("streamingPlaylists")
        if (playlist!=null) for (i in 0 until playlist.length()) {
            val candidate=safeStream(playlist.optJSONObject(i)?.optString("playlistUrl").orEmpty())
            if(candidate!=null && candidate.substringBefore('?').endsWith(".m3u8")) return candidate
        }
        val files=payload.optJSONArray("files")
        if(files!=null) for(i in 0 until files.length()) {
            val candidate=safeStream(files.optJSONObject(i)?.optString("fileUrl").orEmpty())
            if(candidate!=null && candidate.substringBefore('?').endsWith(".mp4")) return candidate
        }
        return null
    }

    fun resolve(id:String,done:(Result<String>)->Unit){
        val route=apiPath(id)
        if(route==null) {
            main.post{done(Result.failure(IllegalArgumentException("Video kimliği geçersiz")))}
            return
        }
        executor.execute{
            val result=try {
                val c=(URL(BASE+route).openConnection() as HttpsURLConnection).apply{
                    connectTimeout=8_000
                    readTimeout=12_000
                    instanceFollowRedirects=false
                    setRequestProperty("Accept","application/json")
                }
                try {
                    if(c.responseCode!=200) throw IllegalStateException("Blender video hizmeti yanıt vermedi")
                    val bytes=c.inputStream.use{it.readNBytes(MAX_BYTES+1)}
                    if(bytes.size>MAX_BYTES)throw IllegalStateException("Video verisi çok büyük")
                    val stream=extractPlaybackUrl(JSONObject(String(bytes,Charsets.UTF_8)))
                        ?: throw IllegalStateException("Açık video oynatma adresi bulunamadı")
                    Result.success(stream)
                }finally {c.disconnect()}
            } catch (_:Exception) {
                Result.failure(IllegalStateException("Blender video bağlantısı kurulamadı"))
            }
            main.post{done(result)}
        }
    }

    fun officialPage(id:String): String? =
        apiPath(id)?.let{BASE+"/videos/watch/"+id}
}
