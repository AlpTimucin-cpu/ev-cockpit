package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/**
 * Map remains INSIDE EV Cockpit; never opens Google Maps or browser.
 *
 * - Local Leaflet page via AndroidX WebViewAssetLoader
 * - OSM map tiles / attribution (interactive personal beta only)
 * - Official EPDK public station pins loaded off UI thread, 55 per viewport
 * - User-initiated Turkish geocoding and user-initiated route preview
 * - No GPS permission, no heavy MapLibre, no hidden Android Auto navigation
 */
class EmbeddedMapActivity:Activity() {
    private val work=Executors.newSingleThreadExecutor()
    @Volatile private var active=true
    private var requestId=0
    private val bg=0xff0b202c.toInt()
    private val aqua=0xff7de3d0.toInt()
    private lateinit var web:WebView
    private lateinit var status:TextView
    private lateinit var search:EditText
    private val assetUrl="https://appassets.androidplatform.net/assets/map/index.html"
    private var epdk:EpdkPublicStations.Snapshot?=null
    private var pageReady=false

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=bg
        window.navigationBarColor=bg
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.setOnApplyWindowInsetsListener { view,insets ->
            if(android.os.Build.VERSION.SDK_INT>=30) {
                val bars=insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0,bars.top,0,bars.bottom)
            }
            insets
        }
        setContentView(root)
        val titleRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        titleRow.addView(button("‹"){finish()},LinearLayout.LayoutParams(dp(55),dp(55)))
        titleRow.addView(label("TÜRKİYE HARİTASI • EV Cockpit",19,true),
            LinearLayout.LayoutParams(0,dp(55),1f))
        root.addView(titleRow)
        root.addView(label("Uygulama içi harita, şarj noktaları ve rota önizlemesi",
            12,false))
        val lookup=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
        search=EditText(this).apply {
            hint="Türkiye adres, ilçe, sokak ara"
            textSize=13f;setSingleLine(true)
            setTextColor(Color.WHITE);setHintTextColor(0xffa3bbc8.toInt())
            setPadding(dp(12),dp(6),dp(8),dp(6))
            background=round(0xff1a3747.toInt())
        }
        lookup.addView(search,LinearLayout.LayoutParams(0,dp(52),1f))
        lookup.addView(button("Ara"){searchAddress()},
            LinearLayout.LayoutParams(dp(75),dp(52)))
        root.addView(lookup)
        val loader=WebViewAssetLoader.Builder()
            .addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web=WebView(this)
        web.setBackgroundColor(0xffecf1ee.toInt())
        web.settings.javaScriptEnabled=true
        web.settings.domStorageEnabled=false
        web.settings.allowFileAccess=false
        web.settings.allowContentAccess=false
        web.settings.cacheMode=WebSettings.LOAD_DEFAULT
        web.settings.javaScriptCanOpenWindowsAutomatically=false
        web.settings.setSupportMultipleWindows(false)
        web.settings.mediaPlaybackRequiresUserGesture=true
        web.settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.settings.userAgentString=web.settings.userAgentString+
            " EV-Cockpit-EmbeddedMap/1.2 (+https://github.com/AlpTimucin-cpu/ID4-Media-Hub)"
        web.webViewClient=object:WebViewClient(){
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse?{
                val u=request.url
                if(u.scheme!="https")return denied()
                if(u.host=="appassets.androidplatform.net")
                    return loader.shouldInterceptRequest(u)
                if(u.host in setOf("unpkg.com","tile.openstreetmap.org"))return null
                return denied()
            }
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                // Prevent popups/opening external maps/browser through WebView.
                return request.url.toString()!=assetUrl
            }
            override fun onPageFinished(view:WebView,url:String?) {
                if(url!=assetUrl)return
                web.evaluateJavascript("window.evcockpitIsReady && window.evcockpitIsReady();"){ready->
                    pageReady=ready=="true"
                    if(!active)return@evaluateJavascript
                    status.text=if(pageReady)
                        "Harita hazır • Hedef: dokun • Başlangıç: uzun bas"
                        else "Harita kütüphanesi yüklenemedi. İnternet bağlantını kontrol et."
                    if(pageReady) {
                        val lat=intent.getDoubleExtra(EXTRA_LAT,Double.NaN)
                        val lon=intent.getDoubleExtra(EXTRA_LON,Double.NaN)
                        if(valid(lat,lon)){
                            focus(lat,lon,intent.getStringExtra(EXTRA_LABEL).orEmpty())
                        }
                    }
                }
            }
        }
        root.addView(web,LinearLayout.LayoutParams(-1,0,1f))
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        actions.addView(button("⚡ İstasyonlar"){loadVisibleStations()},
            LinearLayout.LayoutParams(0,dp(58),1.1f))
        actions.addView(button("↗ Rota çiz"){drawRoute()},
            LinearLayout.LayoutParams(0,dp(58),1f))
        actions.addView(button("Temizle"){
            if(pageReady)web.evaluateJavascript("window.evcockpitClearRoute();",null)
            status.text="Rota çizimi temizlendi."
        },LinearLayout.LayoutParams(0,dp(58),.75f))
        root.addView(actions)
        status=label("Harita yükleniyor…",12,false)
        root.addView(status)
        root.addView(label("© OpenStreetMap katkıcıları. Harita verisi açık kaynaklıdır. "+
            "Rota yalnızca önizleme; canlı trafik, sesli dönüş komutu ve konum takibi yok.",
            11,false))
        web.loadUrl(assetUrl)
    }

    private fun loadVisibleStations(){
        if(!pageReady){message("Harita yükleniyor","Önce haritanın açılmasını bekle.");return}
        web.evaluateJavascript("window.evcockpitMapBounds();"){ raw ->
            val bounds=try{
                val a=JSONArray(raw)
                listOf(a.getDouble(0),a.getDouble(1),a.getDouble(2),a.getDouble(3))
            }catch(_:Exception){null}
            if(bounds==null || bounds.any{!it.isFinite()}){
                status.text="Harita bölgesi okunamadı."
                return@evaluateJavascript
            }
            val id=++requestId
            status.text="Görünen alandaki EPDK istasyonları hazırlanıyor…"
            work.execute{
                val output=try {
                    val data=epdk?.takeIf{it.fresh()} ?: EpdkPublicStations.load(applicationContext)
                    require(data.fresh())
                    epdk=data
                    val south=bounds[0];val west=bounds[1]
                    val north=bounds[2];val east=bounds[3]
                    // Large Turkey view: no mass marker creation.
                    val items=data.stations.asSequence()
                        .filter{it.lat>=south && it.lat<=north &&
                            it.lon>=west && it.lon<=east}
                        .take(55).toList()
                    val array=JSONArray()
                    for(e in items){
                        val connector=buildString {
                            val ccs=e.sockets.count{it.type.contains("CCS",true) ||
                                it.type.contains("COMBO",true)}
                            val ac=e.sockets.count{it.type.contains("TYPE 2",true) ||
                                it.type.contains("TYPE2",true)}
                            if(ccs>0)append("CCS: $ccs ")
                            if(ac>0)append("Type 2: $ac")
                        }
                        array.put(JSONObject()
                            .put("lat",e.lat).put("lon",e.lon)
                            .put("title",e.name.take(95))
                            .put("operator",e.operator.take(70))
                            .put("sockets",connector))
                    }
                    array.toString() to data.verifiedUtc
                }catch(_:Exception){null}
                runOnUiThread{
                    if(!active||isFinishing||id!=requestId)return@runOnUiThread
                    if(output==null){
                        status.text="EPDK verisi okunamadı. İnternet veya veri güncelliğini kontrol et."
                        return@runOnUiThread
                    }
                    web.evaluateJavascript("window.evcockpitShowStations("+output.first+");"){count->
                        status.text="Haritada "+count+
                            " EPDK halka açık şarj noktası • Veriler: "+
                            output.second.take(16)+" UTC"
                    }
                }
            }
        }
    }

    private fun searchAddress(){
        val query=search.text.toString().trim().take(160)
        if(query.length<3){message("Adres eksik","En az 3 karakter gir.");return}
        val id=++requestId
        status.text="Türkiye adres araması sürüyor…"
        work.execute {
            val found=try{ EmbeddedMapServices.lookup(query) }catch(_:Exception){null}
            runOnUiThread{
                if(!active||isFinishing||id!=requestId)return@runOnUiThread
                if(found==null){status.text="Adres servisine ulaşılamadı veya hız sınırına takıldı.";return@runOnUiThread}
                if(found.isEmpty()){status.text="Adres bulunamadı.";return@runOnUiThread}
                AlertDialog.Builder(this).setTitle("Adres sonuçları")
                    .setItems(found.map{it.name}.toTypedArray()){_,i->
                        focus(found[i].lat,found[i].lon,found[i].name)
                    }.setNegativeButton("Vazgeç",null).show()
                status.text=found.size.toString()+" adres bulundu."
            }
        }
    }

    private fun drawRoute(){
        if(!pageReady){message("Harita yükleniyor","Biraz bekle.");return}
        web.evaluateJavascript("window.evcockpitGetRouteEndpoints();"){raw->
            val coords=try {
                val arr=JSONArray(raw)
                DoubleArray(4){arr.getDouble(it)}
            }catch(_:Exception){null}
            if(coords==null || !valid(coords[1],coords[0]) || !valid(coords[3],coords[2])){
                message("Rota seçimi","Haritaya uzun basıp başlangıcı, sonra hedefi seç.")
                return@evaluateJavascript
            }
            val id=++requestId
            status.text="Türkiye yol ağı üzerinde rota hesaplanıyor…"
            work.execute {
                val line=try { EmbeddedMapServices.route(
                    coords[0],coords[1],coords[2],coords[3])
                }catch(_:Exception){null}
                runOnUiThread{
                    if(!active||id!=requestId||isFinishing)return@runOnUiThread
                    if(line==null){
                        status.text="Rota servisi yanıt vermedi. Harita ve istasyonlar açık kalır."
                        return@runOnUiThread
                    }
                    web.evaluateJavascript("window.evcockpitShowRoute("+line.toString()+");"){ ok ->
                        status.text=if(ok=="true")
                            "Rota önizlemesi: "+line.optString("distanceKm")+
                                " km • "+line.optString("durationMin")+" dk"
                            else "Rota çizilemedi. Başka başlangıç/hedef seç."
                    }
                }
            }
        }
    }

    private fun focus(lat:Double,lon:Double,label:String){
        if(!valid(lat,lon)||!pageReady)return
        web.evaluateJavascript("window.evcockpitFocus("+
            String.format(Locale.US,"%.7f,%.7f,",lat,lon)+
            JSONObject.quote(label.take(90))+");",null)
    }
    private fun valid(lat:Double,lon:Double)=lat.isFinite()&&lon.isFinite()&&
        lat in 35.0..43.0 && lon in 25.0..46.0

    private fun denied():WebResourceResponse =
        WebResourceResponse("text/plain","utf-8",403,"Forbidden",
            mapOf("Cache-Control" to "no-store"),ByteArrayInputStream(ByteArray(0)))

    private fun dp(x:Int)=(x*resources.displayMetrics.density).toInt()
    private fun round(color:Int)=GradientDrawable().apply{
        setColor(color);cornerRadius=dp(10).toFloat()
    }
    private fun label(value:String,size:Int,bold:Boolean):TextView=
        TextView(this).apply{
            text=value;textSize=size.toFloat()
            setTextColor(if(bold)aqua else 0xffb9d0db.toInt())
            if(bold)setTypeface(typeface,Typeface.BOLD)
            setPadding(dp(8),dp(7),dp(8),dp(7))
        }
    private fun button(value:String,fn:()->Unit):Button=Button(this).apply{
        text=value;textSize=12f;isAllCaps=false
        setTextColor(Color.WHITE);background=round(0xff20566c.toInt())
        setOnClickListener{fn()}
    }
    private fun message(title:String,detail:String){
        AlertDialog.Builder(this).setTitle(title).setMessage(detail)
            .setPositiveButton("Tamam",null).show()
    }
    override fun onPause(){
        if(::web.isInitialized) web.onPause()
        super.onPause()
    }
    override fun onResume(){
        super.onResume()
        if(::web.isInitialized)web.onResume()
    }
    override fun onDestroy(){
        active=false;requestId++
        work.shutdownNow()
        if(::web.isInitialized){
            web.stopLoading()
            web.loadUrl("about:blank")
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }
    companion object {
        const val EXTRA_LAT="com.evcockpit.evguide.map.lat"
        const val EXTRA_LON="com.evcockpit.evguide.map.lon"
        const val EXTRA_LABEL="com.evcockpit.evguide.map.label"
        fun open(activity:Activity,lat:Double,lon:Double,label:String){
            activity.startActivity(Intent(activity,EmbeddedMapActivity::class.java)
                .putExtra(EXTRA_LAT,lat).putExtra(EXTRA_LON,lon)
                .putExtra(EXTRA_LABEL,label.take(100)))
        }
    }
}
