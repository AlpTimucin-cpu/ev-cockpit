package com.evcockpit.mediahub

import android.app.Activity
import android.content.ActivityNotFoundException
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.http.SslError
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebStorage
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * Independent multi-tab WebView browser inspired by the UX of AABrowser.
 * Phone-side activity: never claims Android Auto video or bypasses driver locks.
 */
class MediaWebActivity : Activity() {
    companion object {
        const val EXTRA_URL = "web_media_url"
        const val YOUTUBE_URL = "https://m.youtube.com"
        const val NETFLIX_URL = "https://www.netflix.com"
        const val PRIME_VIDEO_URL = "https://www.primevideo.com"
    }
    private data class Tab(val web: WebView, var title: String, var url: String)
    private val tabs = mutableListOf<Tab>()
    private var active = -1
    private var desktop = false
    private lateinit var main: LinearLayout
    private lateinit var pages: FrameLayout
    private lateinit var fullLayer: FrameLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var address: EditText
    private lateinit var heading: TextView
    private lateinit var progress: ProgressBar
    private lateinit var bookmark: Button
    private lateinit var desktopControl: Button
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    private var oldOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private fun dp(n:Int) = (n * resources.displayMetrics.density).toInt()
    private fun bg(color:Int) = GradientDrawable().apply {
        setColor(color);cornerRadius=dp(11).toFloat()
    }
    private fun button(value:String, action:()->Unit):Button = Button(this).apply {
        text=value; textSize=12f; isAllCaps=false
        setTextColor(Color.WHITE); background=bg(0xff27516a.toInt())
        setOnClickListener { action() }
    }
    private fun toast(t:String)=Toast.makeText(this,t,Toast.LENGTH_SHORT).show()
    private fun selected()=tabs.getOrNull(active)

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=0xff091427.toInt()
        window.navigationBarColor=0xff091427.toInt()
        desktop=getSharedPreferences("evcockpit_browser_settings",MODE_PRIVATE).getBoolean("desktop",false)
        buildUi()
        val incoming=BrowserStore.safeHttps(intent.getStringExtra(EXTRA_URL).orEmpty())
        val restored=BrowserStore.tabs(this)
        if (restored.isEmpty()) {
            addTab(incoming ?: YOUTUBE_URL)
        } else {
            restored.take(6).forEach { addTab(it.url,it.title,false) }
            selectTab(0)
            if (incoming != null && incoming != selected()?.url) {
                if(tabs.size<6) addTab(incoming) else open(incoming)
            }
        }
    }

    private fun buildUi() {
        val root=FrameLayout(this).apply { setBackgroundColor(0xff0b1627.toInt()) }
        window.decorView.setBackgroundColor(0xff0b1627.toInt())
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        }
        main=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setBackgroundColor(0xff0b1627.toInt())
        }
        fullLayer=FrameLayout(this).apply {
            visibility=View.GONE;setBackgroundColor(Color.BLACK)
        }
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            root.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        root.addView(main,FrameLayout.LayoutParams(-1,-1))
        root.addView(fullLayer,FrameLayout.LayoutParams(-1,-1))
        val header=LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(4),dp(8),dp(4))
        }
        heading=TextView(this).apply {
            text="EV Cockpit  WEB MEDYA";textSize=16f;setTextColor(0xff75e5da.toInt())
            maxLines=1;gravity=Gravity.CENTER_VERTICAL
        }
        header.addView(heading,LinearLayout.LayoutParams(0,dp(48),1f))
        header.addView(button("▶ YouTube"){open(YOUTUBE_URL)},
            LinearLayout.LayoutParams(dp(107),dp(48)))
        header.addView(button("TV'ye dön"){finish()},
            LinearLayout.LayoutParams(dp(102),dp(48)))
        main.addView(header)

        val strip=HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=false;setBackgroundColor(0xff182941.toInt())
        }
        tabBar=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
        strip.addView(tabBar)
        main.addView(strip)

        val inputRow=LinearLayout(this).apply {setPadding(dp(8),dp(6),dp(8),dp(6))}
        address=EditText(this).apply {
            setSingleLine(true);hint="https://..."; textSize=13f
            inputType=android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions=EditorInfo.IME_ACTION_GO
            setTextColor(Color.WHITE);setHintTextColor(0xff9eafc4.toInt())
            background=bg(0xff1a334e.toInt())
            setPadding(dp(12),dp(10),dp(12),dp(10))
            setOnEditorActionListener{_,_,_->open(text.toString());true}
        }
        inputRow.addView(address,LinearLayout.LayoutParams(0,dp(48),1f))
        inputRow.addView(button("Aç"){open(address.text.toString())},
            LinearLayout.LayoutParams(dp(61),dp(48)))
        main.addView(inputRow)

        val tools=LinearLayout(this).apply {setPadding(dp(7),0,dp(7),dp(6))}
        fun addTool(value:String,action:()->Unit) {
            tools.addView(button(value,action),LinearLayout.LayoutParams(0,dp(45),1f))
        }
        addTool("‹"){selected()?.web?.let{if(it.canGoBack())it.goBack()}}
        addTool("›"){selected()?.web?.let{if(it.canGoForward())it.goForward()}}
        addTool("↻"){selected()?.web?.reload()}
        bookmark=button("☆"){toggleBookmark()}
        tools.addView(bookmark,LinearLayout.LayoutParams(0,dp(45),1f))
        addTool("☷"){bookmarks()}
        addTool("YT◷"){youtubeHistory()}
        addTool("V◷"){streamingHistory(StreamingService.NETFLIX)}
        addTool("P◷"){streamingHistory(StreamingService.PRIME_VIDEO)}
        addTool("⋮"){options()}
        main.addView(tools)
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=100;visibility=View.INVISIBLE
        }
        main.addView(progress,LinearLayout.LayoutParams(-1,dp(3)))
        pages=FrameLayout(this).apply{setBackgroundColor(Color.BLACK)}
        main.addView(pages,LinearLayout.LayoutParams(-1,0,1f))
        val lower=LinearLayout(this).apply{setPadding(dp(7),dp(4),dp(7),dp(4))}
        lower.addView(button("Netflix Web"){open(NETFLIX_URL)},
            LinearLayout.LayoutParams(0,dp(47),1f))
        desktopControl=button("Mobil"){toggleDesktop()}
        lower.addView(desktopControl,LinearLayout.LayoutParams(0,dp(47),1f))
        lower.addView(button("🚘 AABrowser"){handoffToAABrowser()},
            LinearLayout.LayoutParams(0,dp(47),1.2f))
        lower.addView(button("⛶ Tam ekran"){immersive()},
            LinearLayout.LayoutParams(0,dp(47),1f))
        main.addView(lower)
        val streamServices = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(7), dp(2), dp(7), dp(4))
        }
        streamServices.addView(button("Netflix"){open(NETFLIX_URL)},
            LinearLayout.LayoutParams(0,dp(43),1f))
        streamServices.addView(button("Prime Video"){open(PRIME_VIDEO_URL)},
            LinearLayout.LayoutParams(0,dp(43),1f).apply { leftMargin = dp(6) })
        main.addView(streamServices)
        desktopControl.text=if(desktop)"Masaüstü ✓" else "Mobil"
    }

    private fun addTab(raw:String=YOUTUBE_URL,name:String="",activate:Boolean=true,
                       loadImmediately:Boolean=true):WebView? {
        if(tabs.size>=6){toast("En fazla 6 sekme açılabilir");return null}
        val url=BrowserStore.safeHttps(raw) ?: YOUTUBE_URL
        val web=WebView(this).apply {
            visibility=View.GONE;overScrollMode=View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.BLACK)
        }
        with(web.settings) {
            javaScriptEnabled=true;domStorageEnabled=true
            mediaPlaybackRequiresUserGesture=true
            allowFileAccess=false;allowContentAccess=false
            javaScriptCanOpenWindowsAutomatically=false
            setSupportMultipleWindows(true)
            mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            builtInZoomControls=true;displayZoomControls=false
            safeBrowsingEnabled=true
        }
        // Some signed-in video sites require third-party session cookies.
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,true)
        updateAgent(web)
        val tab=Tab(web,name.ifBlank{"Yeni sekme"},url)
        web.webViewClient=object:WebViewClient() {
            override fun shouldOverrideUrlLoading(view:WebView?,request:WebResourceRequest):Boolean =
                BrowserStore.safeHttps(request.url.toString())==null
            // YouTube uses in-page (SPA) navigation: onPageFinished alone misses videos.
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view,url,isReload)
                val safe=BrowserStore.safeHttps(url.orEmpty()) ?: return
                tab.url=safe
                if (!isReload) {
                    YoutubeVideoStore.remember(this@MediaWebActivity,tab.title,safe)
                    StreamingHistoryStore.remember(this@MediaWebActivity,tab.title,safe)
                }
                if (selected()===tab) updateHeader()
            }
            override fun onPageFinished(view:WebView?,rawUrl:String?) {
                val safe=BrowserStore.safeHttps(rawUrl.orEmpty())?:return
                tab.url=safe
                tab.title=view?.title?.take(32)?.ifBlank{"Web"}?:"Web"
                val canonical = StreamingPagePolicy.canonical(safe)?.url
                    ?: YoutubePagePolicy.canonicalWatchUrl(safe)
                // Do not store login, search or authentication pages from
                // paid streaming services in the general browser history.
                if (canonical != null || !StreamingPagePolicy.isSubscriptionHost(safe)) {
                    BrowserStore.remember(this@MediaWebActivity,tab.title,canonical ?: safe)
                }
                YoutubeVideoStore.remember(this@MediaWebActivity,tab.title,safe)
                StreamingHistoryStore.remember(this@MediaWebActivity,tab.title,safe)
                if(selected()===tab) updateHeader()
                renderTabs();persist()
            }
            override fun onReceivedError(view:WebView?,request:WebResourceRequest,
                error:WebResourceError){
                if(request.isForMainFrame && selected()===tab) {
                    toast("Web sayfası yüklenemedi: "+error.description.toString().take(80))
                }
            }
            override fun onRenderProcessGone(view:WebView?,
                detail:RenderProcessGoneDetail):Boolean {
                val index=tabs.indexOfFirst{it.web===view}
                if(index>=0){
                    val failed=tabs.removeAt(index)
                    pages.removeView(failed.web)
                    failed.web.destroy()
                    active=-1
                    if(tabs.isEmpty())addTab(failed.url)
                    else selectTab(0)
                    toast("Web oynatıcı işlemi yeniden başlatıldı")
                }
                return true
            }
            override fun onReceivedSslError(view:WebView?,handler:SslErrorHandler,error:SslError){
                handler.cancel();toast("Sertifika hatası: sayfa engellendi")
            }
        }
        web.webChromeClient=object:WebChromeClient(){
            override fun onProgressChanged(view:WebView?,value:Int){
                if(selected()===tab){
                    progress.progress=value
                    progress.visibility=if(value>=100)View.INVISIBLE else View.VISIBLE
                }
            }
            override fun onReceivedTitle(view:WebView?,title:String?){
                if(!title.isNullOrBlank()) tab.title=title.take(32)
                // Update saved video titles when YouTube finishes SPA metadata updates.
                YoutubeVideoStore.remember(this@MediaWebActivity,tab.title,tab.url)
                StreamingHistoryStore.remember(this@MediaWebActivity,tab.title,tab.url)
                if(selected()===tab)updateHeader()
                renderTabs()
            }
            override fun onShowCustomView(view:View?,callback:CustomViewCallback?){
                if(view==null||callback==null||selected()!==tab||fullView!=null){
                    callback?.onCustomViewHidden();return
                }
                fullView=view;fullCallback=callback
                fullLayer.addView(view,FrameLayout.LayoutParams(-1,-1))
                fullLayer.visibility=View.VISIBLE;main.visibility=View.GONE
                oldOrientation=requestedOrientation
                requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                fullLayer.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
            override fun onCreateWindow(view:WebView?, isDialog:Boolean,
                isUserGesture:Boolean, resultMsg:android.os.Message?):Boolean {
                if(!isUserGesture || resultMsg==null || tabs.size>=6) return false
                val transport=resultMsg.obj as? WebView.WebViewTransport ?: return false
                val child=addTab(YOUTUBE_URL,"Yeni sekme",true,false) ?: return false
                transport.webView=child
                resultMsg.sendToTarget()
                return true
            }

            override fun onPermissionRequest(request:android.webkit.PermissionRequest?) {
                if(request==null)return
                val id=android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                val valid=BrowserStore.safeHttps(request.origin.toString())!=null &&
                    request.resources.size==1 && request.resources[0]==id
                if(!valid){request.deny();return}
                runOnUiThread {
                    if(isFinishing || isDestroyed){request.deny();return@runOnUiThread}
                    AlertDialog.Builder(this@MediaWebActivity)
                        .setTitle("Korumalı medya izni")
                        .setMessage("Bu HTTPS sitesi korumalı medya kimliği kullanmak istiyor. " +
                            "Bu izin içeriğin lisanslı oynatılmasını sağlayabilir, " +
                            "ancak Netflix gibi hizmetlerin görüntü vermesi garanti değildir.")
                        .setNegativeButton("Reddet"){_,_->request.deny()}
                        .setPositiveButton("İzin ver"){_,_->request.grant(arrayOf(id))}
                        .setOnCancelListener{request.deny()}
                        .show()
                }
            }

            override fun onHideCustomView(){exitVideoFullscreen()}
        }
        tabs.add(tab)
        pages.addView(web,FrameLayout.LayoutParams(-1,-1))
        if(activate) selectTab(tabs.lastIndex)
        if(loadImmediately)web.loadUrl(url)
        renderTabs();persist()
        return web
    }

    private fun selectTab(i:Int){
        if(i !in tabs.indices)return
        if(fullView!=null)exitVideoFullscreen()
        selected()?.web?.onPause()
        active=i
        tabs.forEachIndexed{index,tab->
            tab.web.visibility=if(index==i)View.VISIBLE else View.GONE
        }
        selected()?.web?.onResume()
        updateHeader();renderTabs();persist()
    }

    private fun renderTabs(){
        if(!::tabBar.isInitialized)return
        tabBar.removeAllViews()
        tabs.forEachIndexed{i,tab->
            val b=button((i+1).toString()+" "+tab.title.take(14)){selectTab(i)}
            b.background=bg(if(i==active)0xff2a7770.toInt() else 0xff30465d.toInt())
            val lp=LinearLayout.LayoutParams(dp(144),dp(42))
            lp.setMargins(dp(3),dp(3),0,dp(3))
            tabBar.addView(b,lp)
            b.setOnLongClickListener{closeTab(i);true}
        }
        tabBar.addView(button("＋"){addTab()},
            LinearLayout.LayoutParams(dp(53),dp(47)))
    }

    private fun closeTab(i:Int){
        if(i !in tabs.indices)return
        if(tabs.size==1){open(YOUTUBE_URL);return}
        if(fullView!=null)exitVideoFullscreen()
        val tab=tabs.removeAt(i)
        pages.removeView(tab.web);tab.web.stopLoading();tab.web.destroy()
        active=-1
        selectTab(minOf(i,tabs.lastIndex))
    }

    private fun open(raw:String){
        val safe=BrowserStore.safeHttps(raw)
        if(safe==null){
            AlertDialog.Builder(this).setTitle("HTTPS bağlantısı gerekli")
                .setMessage("Yalnızca güvenli HTTPS adresleri açılır.")
                .setPositiveButton("Tamam",null).show()
            return
        }
        selected()?.web?.loadUrl(safe)?:addTab(safe)
        address.clearFocus()
    }

    /**
     * User-initiated handoff of the active HTTPS web page to the separately
     * installed AABrowser. It has an ACTION_VIEW endpoint; whether the page
     * appears on an actual parked head unit MUST be checked on the vehicle.
     * No driver-distraction restrictions or Android Auto app categories change.
     */
    private fun handoffToAABrowser() {
        val safe = BrowserStore.safeHttps(selected()?.url.orEmpty())
        if (safe == null) {
            toast("Yalnızca güvenli HTTPS web sayfaları aktarılabilir.")
            return
        }
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(safe)).apply {
            setPackage("com.kododake.aabrowser")
        }
        try {
            startActivity(view)
            toast("Sayfa AABrowser'a gönderildi. EV Cockpit ekranını ayrıca kontrol et.")
        } catch (_: ActivityNotFoundException) {
            AlertDialog.Builder(this)
                .setTitle("AABrowser bulunamadı")
                .setMessage("AABrowser kurulu değil ya da web adresi açma isteğini kabul etmiyor. " +
                    "Bu özellik yalnızca park halinde denenmelidir.")
                .setPositiveButton("Tamam", null)
                .show()
        } catch (_: SecurityException) {
            toast("AABrowser bağlantı isteğine Android izin vermedi.")
        }
    }

    private fun updateHeader(){
        val tab=selected()?:return
        heading.text="EV Cockpit • "+tab.title.take(22)
        address.setText(tab.url)
        bookmark.text=if(BrowserStore.isBookmarked(this,tab.url))"★" else "☆"
    }

    private fun toggleBookmark(){
        val tab=selected()?:return
        val added=BrowserStore.toggleBookmark(this,tab.title,tab.url)
        toast(if(added)"Favorilere eklendi" else "Favorilerden kaldırıldı")
        updateHeader()
    }

    private fun bookmarks(){
        val saved=BrowserStore.bookmarks(this)
        if(saved.isEmpty()){
            toast("Favori eklemek için ☆ işaretine dokun");return
        }
        AlertDialog.Builder(this).setTitle("Web favorileri")
            .setItems(saved.map{it.title+" · "+it.url}.toTypedArray()){_,i->open(saved[i].url)}
            .setNegativeButton("Kapat",null).show()
    }

    private fun history(){
        val saved=BrowserStore.history(this)
        AlertDialog.Builder(this).setTitle("Geçmiş")
            .setItems(saved.map{it.title}.toTypedArray()){_,i->open(saved[i].url)}
            .setNeutralButton("Temizle"){_,_->BrowserStore.clearHistory(this)}
            .setNegativeButton("Kapat",null).show()
    }

    private fun youtubeHistory() {
        val saved = YoutubeVideoStore.load(this)
        if (saved.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("YouTube • Son videolar")
                .setMessage("İzlediğin YouTube video sayfaları burada görünecek. " +
                    "Yalnızca video kimliği kaydedilir; takip parametreleri saklanmaz.")
                .setPositiveButton("Tamam", null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("YouTube • Son videolar")
            .setItems(saved.map { it.title }.toTypedArray()) { _, index ->
                open(saved[index].url)
            }
            .setNeutralButton("Listeyi temizle") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("YouTube geçmişini temizle")
                    .setMessage("Kaydedilen video geçmişi silinsin mi? " +
                        "Web favorilerin ve TV/radyo listelerin değişmeyecek.")
                    .setNegativeButton("İptal", null)
                    .setPositiveButton("Temizle") { _, _ ->
                        YoutubeVideoStore.clear(this)
                        toast("YouTube video geçmişi temizlendi")
                    }.show()
            }
            .setNegativeButton("Kapat", null).show()
    }

    private fun streamingHistory(service: StreamingService) {
        val saved = StreamingHistoryStore.load(this, service)
        val appName = service.displayName
        val dialog = AlertDialog.Builder(this)
            .setTitle(appName + " • Son içerikler")
            .setMessage(if (saved.isEmpty())
                "İçerik sayfaları açıldığında geçmiş burada görünür. " +
                    "Oynatma süresi veya hesap bilgileri izlenmez. " +
                    "DRM korumalı videolar WebView içinde oynatılamayabilir."
                else null)
        if (saved.isNotEmpty()) {
            dialog.setItems(saved.map { it.title }.toTypedArray()) { _, index ->
                open(saved[index].url)
            }
        }
        dialog.setPositiveButton("Resmî uygulamayı aç") { _, _ ->
            when (service) {
                StreamingService.NETFLIX -> InstalledMediaApps.openNetflix(this)
                StreamingService.PRIME_VIDEO -> InstalledMediaApps.openPrimeVideo(this)
            }
        }
        if (saved.isNotEmpty()) {
            dialog.setNeutralButton("Geçmişi temizle") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(appName + " geçmişi silinsin mi?")
                    .setMessage("Yalnızca bu hizmete ait yerel içerik bağlantıları silinir.")
                    .setNegativeButton("Vazgeç",null)
                    .setPositiveButton("Temizle") { _, _ ->
                        StreamingHistoryStore.clear(this,service)
                        toast(appName + " geçmişi temizlendi")
                    }.show()
            }
        }
        dialog.setNegativeButton("Kapat",null).show()
    }

    private fun options(){
        val opts=arrayOf("Yeni sekme","Sekmeyi kapat","Favoriler","Geçmiş",
            "YouTube • Son videolar", "Netflix • Son içerikler",
            "Prime Video • Son içerikler", "Netflix Web","Prime Video Web",
            "Netflix uygulamasını aç", "Prime Video uygulamasını aç",
            "Masaüstü / Mobil","Tam ekran","Site verilerini temizle")
        AlertDialog.Builder(this).setTitle("Web Medya")
            .setItems(opts){_,index->when(index){
                0->addTab()
                1->closeTab(active)
                2->bookmarks()
                3->history()
                4->youtubeHistory()
                5->streamingHistory(StreamingService.NETFLIX)
                6->streamingHistory(StreamingService.PRIME_VIDEO)
                7->open(NETFLIX_URL)
                8->open(PRIME_VIDEO_URL)
                9->InstalledMediaApps.openNetflix(this)
                10->InstalledMediaApps.openPrimeVideo(this)
                11->toggleDesktop()
                12->immersive()
                13->clearSiteData()
            }}.setNegativeButton("Kapat",null).show()
    }

    private fun clearSiteData() {
        AlertDialog.Builder(this)
            .setTitle("Site verilerini sil")
            .setMessage("Giriş çerezleri ve web depolaması silinecek. " +
                "Web favorilerin ve kanal listelerin kalacak. Devam edilsin mi?")
            .setNegativeButton("İptal", null)
            .setPositiveButton("Temizle") { _, _ ->
                CookieManager.getInstance().removeAllCookies { }
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                tabs.forEach { it.web.clearCache(true);it.web.reload() }
                toast("Site oturumları ve önbellek temizlendi")
            }.show()
    }

    private fun updateAgent(web:WebView){
        web.settings.userAgentString=if(desktop)
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "+
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36" else null
    }

    private fun toggleDesktop(){
        desktop=!desktop
        getSharedPreferences("evcockpit_browser_settings",MODE_PRIVATE).edit()
            .putBoolean("desktop",desktop).apply()
        tabs.forEach{updateAgent(it.web);it.web.reload()}
        desktopControl.text=if(desktop)"Masaüstü ✓" else "Mobil"
    }

    private fun immersive(){
        val decor=window.decorView
        val flags=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        decor.systemUiVisibility=if(decor.systemUiVisibility and
            View.SYSTEM_UI_FLAG_FULLSCREEN==0)flags else View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun exitVideoFullscreen(){
        val view=fullView?:return
        (view.parent as? ViewGroup)?.removeView(view)
        fullView=null;fullLayer.visibility=View.GONE;main.visibility=View.VISIBLE
        requestedOrientation=oldOrientation
        fullCallback?.onCustomViewHidden();fullCallback=null
    }

    private fun persist(){
        BrowserStore.saveTabs(this,tabs.map{StoredWebTab(it.title,it.url)})
    }

    override fun onPause(){
        persist();selected()?.web?.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }
    override fun onResume(){super.onResume();selected()?.web?.onResume()}

    @Deprecated("Activity back handling")
    override fun onBackPressed(){
        when{
            fullView!=null->exitVideoFullscreen()
            selected()?.web?.canGoBack()==true->selected()?.web?.goBack()
            else->super.onBackPressed()
        }
    }

    override fun onDestroy(){
        exitVideoFullscreen();persist()
        tabs.forEach{pages.removeView(it.web);it.web.stopLoading();it.web.destroy()}
        tabs.clear()
        super.onDestroy()
    }
}
