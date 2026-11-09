package com.evcockpit.mediahub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URL
import java.io.ByteArrayOutputStream
import javax.net.ssl.HttpsURLConnection
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Only subscribed user-supplied HTTPS RSS feeds; no podcast search API or private keys. */
data class PodcastFeed(val title:String,val url:String)
data class PodcastEpisode(val title:String,val link:String,val source:String, val mimeType:String = "")

object PodcastSources {
    private const val PREFS="evcockpit_mediaengine_podcast_feeds"
    private const val KEY="sources"
    fun feeds(context:Context):List<PodcastFeed> {
        return try {
            val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            val a=JSONArray(p.getString(KEY,"[]"))
            (0 until minOf(100,a.length())).mapNotNull{i->
                val obj=a.optJSONObject(i) ?: return@mapNotNull null
                val url=obj.optString("url")
                if(!M3uParser.isHttpsUrl(url))null
                else PodcastFeed(obj.optString("name","Podcast").take(80),url)
            }.distinctBy{it.url}
        }catch(_:Exception){emptyList()}
    }
    fun add(context:Context,title:String,url:String):Boolean {
        val clean=url.trim()
        if(!M3uParser.isHttpsUrl(clean))return false
        val items=feeds(context)
        if(items.size>=100||items.any{it.url==clean})return false
        save(context,items+PodcastFeed(title.trim().take(80).ifBlank{"Podcast"},clean))
        return true
    }
    fun remove(context:Context,url:String){
        save(context,feeds(context).filterNot{it.url==url})
    }
    private fun save(context:Context,items:List<PodcastFeed>){
        val a=JSONArray()
        items.take(100).forEach{
            a.put(JSONObject().put("name",it.title).put("url",it.url))
        }
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putString(KEY,a.toString()).apply()
        // Notify Android Auto that the browsable podcast subscriptions changed.
        context.sendBroadcast(android.content.Intent(
            AudioLibraryService.ACTION_CATALOG_UPDATED).setPackage(context.packageName))
    }

    private fun secureDocument(bytes:ByteArray):org.w3c.dom.Document {
        val factory=DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false)
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true)
        factory.isXIncludeAware=false
        factory.isExpandEntityReferences=false
        // Android's XMLConstants lacks the Java 9 property constants.
        // The parser already forbids DTDs and both external entity types.
        // Apply optional JAXP access restrictions when its provider supports them.
        try {
            factory.setAttribute(
                "http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            factory.setAttribute(
                "http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        } catch (_: IllegalArgumentException) {
            // Android parser versions can reject these optional attributes.
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    fun parseRss(bytes:ByteArray,source:String):List<PodcastEpisode> {
        if(bytes.size>2_000_000)return emptyList()
        return try {
            val xml=secureDocument(bytes)
            val nodes=xml.getElementsByTagName("item")
            buildList {
                for(i in 0 until minOf(nodes.length,120)){
                    val el=nodes.item(i) as? Element ?: continue
                    val title=el.getElementsByTagName("title").item(0)
                        ?.textContent?.trim()?.take(180).orEmpty()
                    val encl=el.getElementsByTagName("enclosure").item(0) as? Element
                    val url=encl?.getAttribute("url")?.trim().orEmpty()
                    if(title.isNotBlank()&&M3uParser.isHttpsUrl(url))
                        add(PodcastEpisode(title,url,source, encl?.getAttribute("type").orEmpty()))
                }
            }
        }catch(_:Exception){emptyList()}
    }
    fun parseOpml(bytes:ByteArray):List<PodcastFeed> {
        if(bytes.size>1_000_000)return emptyList()
        return try {
            val xml=secureDocument(bytes)
            val nodes=xml.getElementsByTagName("outline")
            buildList {
                for(i in 0 until minOf(nodes.length,200)){
                    val node=nodes.item(i) as? Element ?: continue
                    val url=node.getAttribute("xmlUrl").trim()
                    if(M3uParser.isHttpsUrl(url))
                        add(PodcastFeed(node.getAttribute("title").ifBlank{
                            node.getAttribute("text")
                        }.take(80).ifBlank{"Podcast"},url))
                }
            }.distinctBy{it.url}.take(100)
        }catch(_:Exception){emptyList()}
    }
    fun exportOpml(feeds:List<PodcastFeed>):String {
        fun esc(s:String)=s.replace("&","&amp;").replace("<","&lt;")
            .replace(">","&gt;").replace("\"","&quot;")
        val x=StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"+
            "<opml version=\"2.0\"><head><title>EV Cockpit Podcastlar</title></head><body>\n")
        feeds.take(100).forEach{
            x.append("<outline text=\"").append(esc(it.title))
                .append("\" title=\"").append(esc(it.title))
                .append("\" type=\"rss\" xmlUrl=\"").append(esc(it.url))
                .append("\" />\n")
        }
        return x.append("</body></opml>").toString()
    }

    /** Limit downloads, HTTPS redirects and response bodies. Never fetch podcast audio here. */
    fun fetch(url:String):ByteArray {
        require(M3uParser.isHttpsUrl(url))
        var location=URL(url)
        repeat(4){
            val cn=(location.openConnection() as HttpsURLConnection).apply{
                instanceFollowRedirects=false
                connectTimeout=10000
                readTimeout=15000
                setRequestProperty("Accept","application/rss+xml,application/xml,text/xml")
            }
            try {
                val code=cn.responseCode
                if(code in 300..399){
                    val path=cn.getHeaderField("Location")?:error("Yönlendirme yok")
                    val next=URL(location,path)
                    require(next.protocol.equals("https",true)&&next.userInfo==null)
                    location=next
                }else{
                    require(code==200){"RSS yanıtı HTTP "+code}
                    return cn.inputStream.use{stream->
                        val out=ByteArrayOutputStream()
                        val buf=ByteArray(8192)
                        while(true){
                            val n=stream.read(buf);if(n<0)break
                            require(out.size()+n<=2_000_000){"RSS çok büyük"}
                            out.write(buf,0,n)
                        }
                        out.toByteArray()
                    }
                }
            }finally{cn.disconnect()}
        }
        error("Çok fazla yönlendirme")
    }
}
