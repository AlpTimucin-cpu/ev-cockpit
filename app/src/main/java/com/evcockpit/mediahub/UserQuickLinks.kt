package com.evcockpit.mediahub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class UserQuickLink(val title:String,val url:String)

/** User-defined HTTPS shortcut titles without account credentials or external app APKs. */
object UserQuickLinks {
    private const val PREF="evcockpit_mediaengine_quicklinks"
    private const val KEY="links"
    private const val MAX=16

    fun load(context:Context):List<UserQuickLink> {
        val p=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
        return try {
            val a=JSONArray(p.getString(KEY,"[]"))
            (0 until minOf(a.length(),MAX)).mapNotNull{i->
                val obj=a.optJSONObject(i) ?: return@mapNotNull null
                val title=obj.optString("title").trim().take(42)
                val raw=obj.optString("url")
                val url=BrowserStore.safeHttps(raw)
                if(title.isEmpty()||url==null||url!=raw) null
                else UserQuickLink(title,url)
            }.distinctBy{it.url}
        }catch(_:Exception){emptyList()}
    }

    fun add(context:Context,title:String,url:String):Boolean {
        val name=title.trim().replace(Regex("[\\p{Cntrl}]")," ").take(42)
        val safe=BrowserStore.safeHttps(url.trim()) ?: return false
        if(name.isEmpty()||safe!=url.trim())return false
        val previous=load(context)
        if(previous.any{it.url==safe}||previous.size>=MAX)return false
        save(context,previous+UserQuickLink(name,safe))
        return true
    }

    fun remove(context:Context,url:String) {
        save(context,load(context).filterNot{it.url==url})
    }
    private fun save(context:Context,entries:List<UserQuickLink>) {
        val a=JSONArray()
        entries.take(MAX).forEach{a.put(JSONObject()
            .put("title",it.title).put("url",it.url))}
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit()
            .putString(KEY,a.toString()).apply()
    }
}
