package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.http.SslError
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.webkit.PermissionRequest
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * Netflix and Prime Video receive the SAME MediaEngine-style entry, search, history,
 * website navigation and session-header layout as MediaEngineYoutubeActivity.
 *
 * Their official sites are shown in a WebView; licensed DRM streams may require
 * their official apps, launched only on explicit user action. This class does
 * NOT embed, impersonate, or capture those native applications.
 */
class MediaEngineSubscriptionActivity : Activity() {
    companion object { const val EXTRA_SERVICE = "subscription_service" }

    private lateinit var service: StreamingService
    private lateinit var header: MediaEngineSessionHeader
    private lateinit var web: WebView
    private lateinit var loading: ProgressBar
    private lateinit var searchField: EditText
    private lateinit var main: LinearLayout
    private lateinit var fullLayer: FrameLayout
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var originalOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private val backgroundColor=0xff0b1316.toInt()
    private val softColor=0xff9fb1b6.toInt()

    private val highlight:Int
        get()=if(service==StreamingService.NETFLIX)
            0xffe50914.toInt() else 0xff18a7d8.toInt()

    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun shape(color:Int,radius:Int=13)=GradientDrawable().apply{
        setColor(color);cornerRadius=dp(radius).toFloat()
    }
    private fun label(textValue:String,size:Float=14f,color:Int=Color.WHITE,
                      strong:Boolean=false)=TextView(this).apply{
        text=textValue;textSize=size;setTextColor(color);gravity=Gravity.CENTER
        if(strong)setTypeface(typeface,Typeface.BOLD)
    }
    private fun control(title:String,action:()->Unit)=label(title,16f).apply{
        background=shape(0xff243337.toInt(),14)
        isClickable=true;isFocusable=true
        setOnClickListener{action()}
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        service=if(intent.getStringExtra(EXTRA_SERVICE)==StreamingService.PRIME_VIDEO.name)
            StreamingService.PRIME_VIDEO else StreamingService.NETFLIX
        window.statusBarColor=backgroundColor
        window.navigationBarColor=backgroundColor
        val root=FrameLayout(this).apply{setBackgroundColor(backgroundColor)}
        setContentView(root)
        main=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setBackgroundColor(backgroundColor)
        }
        root.addView(main,FrameLayout.LayoutParams(-1,-1))
        root.setOnApplyWindowInsetsListener{view,insets->
            if(android.os.Build.VERSION.SDK_INT>=30){
                val bars=insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0,bars.top,0,bars.bottom)
            }
            insets
        }
        header=MediaEngineSessionHeader(this)
        main.addView(header.view)

        val top=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(10),dp(7),dp(10),dp(4))
        }
        val logo=if(service==StreamingService.NETFLIX)"N" else "prime"
        top.addView(label(logo,if(service==StreamingService.NETFLIX)26f else 19f,
            highlight,true),LinearLayout.LayoutParams(dp(76),dp(44)))
        top.addView(label(service.displayName,18f,Color.WHITE,true).apply{
            gravity=Gravity.CENTER_VERTICAL
        },LinearLayout.LayoutParams(0,dp(44),1f))
        top.addView(control("↗"){openCurrentInOfficial()},
            LinearLayout.LayoutParams(dp(48),dp(46)).apply{rightMargin=dp(6)})
        top.addView(control("⌂"){finish()},LinearLayout.LayoutParams(dp(48),dp(46)))
        main.addView(top)

        val entryRow=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(9),dp(5),dp(9),dp(6))
        }
        searchField=EditText(this).apply{
            hint=service.displayName+" içinde ara"
            setSingleLine(true);textSize=14f;setTextColor(Color.WHITE)
            setHintTextColor(softColor)
            inputType=android.text.InputType.TYPE_CLASS_TEXT
            imeOptions=EditorInfo.IME_ACTION_SEARCH
            background=shape(0xff242e32.toInt(),26)
            setPadding(dp(14),dp(7),dp(12),dp(7))
            setOnEditorActionListener{_,_,_->searchNow();true}
        }
        entryRow.addView(searchField,LinearLayout.LayoutParams(0,dp(47),1f))
        entryRow.addView(control("⌕"){searchNow()},
            LinearLayout.LayoutParams(dp(51),dp(47)).apply{leftMargin=dp(6)})
        main.addView(entryRow)

        val disclosure=label(
            "Resmî web kataloğu • Korumalı yayınlar için ↗ uygulamasını aç",
            10.5f,softColor)
        disclosure.setPadding(dp(8),0,dp(8),dp(5))
        main.addView(disclosure)

        loading=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{
            visibility=View.GONE;max=100
            progressTintList=android.content.res.ColorStateList.valueOf(highlight)
        }
        main.addView(loading,LinearLayout.LayoutParams(-1,dp(3)))
        val webHolder=FrameLayout(this).apply{setBackgroundColor(Color.BLACK)}
        main.addView(webHolder,LinearLayout.LayoutParams(-1,0,1f))
        web=WebView(this).apply{setBackgroundColor(Color.BLACK)}
        with(web.settings){
            javaScriptEnabled=true
            domStorageEnabled=true
            mediaPlaybackRequiresUserGesture=true
            allowFileAccess=false
            allowContentAccess=false
            mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically=false
            setSupportMultipleWindows(false)
            safeBrowsingEnabled=true
            useWideViewPort=true
            loadWithOverviewMode=true
            userAgentString=SubscriptionWebPermissions.userAgent(service,userAgentString)
        }
        // These in-app sessions are distinct from the installed native apps.
        // Third-party site cookies are not automatically shared with them.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,true)
        web.webViewClient=object:WebViewClient(){
            override fun shouldOverrideUrlLoading(view:WebView?,request:WebResourceRequest):Boolean{
                if(!request.isForMainFrame)return false
                val url=request.url.toString()
                if(SubscriptionPortalPolicy.isAllowed(service,url))return false
                Toast.makeText(this@MediaEngineSubscriptionActivity,
                    "Bu bağlantı "+service.displayName+" dışında. Resmî uygulamayı deneyebilirsin.",
                    Toast.LENGTH_SHORT).show()
                return true
            }
            override fun doUpdateVisitedHistory(view:WebView?,url:String?,isReload:Boolean){
                super.doUpdateVisitedHistory(view,url,isReload)
                if(!isReload)saveVisited(view?.title,url)
            }
            override fun onPageFinished(view:WebView?,url:String?){
                saveVisited(view?.title,url)
                loading.visibility=View.GONE
            }
            override fun onReceivedError(view:WebView?,request:WebResourceRequest,
                                         error:WebResourceError){
                if(request.isForMainFrame) {
                    Toast.makeText(this@MediaEngineSubscriptionActivity,
                        "Sayfa açılmadı. ↗ ile resmî uygulamayı açabilirsin.",
                        Toast.LENGTH_LONG).show()
                }
            }
            override fun onReceivedSslError(view:WebView?,handler:SslErrorHandler,error:SslError){
                handler.cancel()
                Toast.makeText(this@MediaEngineSubscriptionActivity,
                    "Site sertifikası doğrulanamadı",Toast.LENGTH_SHORT).show()
            }
            override fun onRenderProcessGone(view:WebView?,detail:RenderProcessGoneDetail):Boolean{
                webHolder.removeView(web)
                Toast.makeText(this@MediaEngineSubscriptionActivity,
                    "Web içeriği kapandı. Tekrar deneyebilir veya ↗ kullanabilirsin.",
                    Toast.LENGTH_LONG).show()
                finish()
                return true
            }
        }
        web.webChromeClient=object:WebChromeClient(){
            override fun onPermissionRequest(request:PermissionRequest){
                if(SubscriptionWebPermissions.allowsProtectedMedia(service,web.url,request.origin.toString()) &&
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in request.resources){
                    request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                }else request.deny()
            }

            override fun onProgressChanged(view:WebView?,progress:Int){
                loading.progress=progress
                loading.visibility=if(progress>=100)View.GONE else View.VISIBLE
            }
            override fun onShowCustomView(view:View?,callback:CustomViewCallback?){
                if(view==null||callback==null||customView!=null){
                    callback?.onCustomViewHidden();return
                }
                customView=view;customCallback=callback
                originalOrientation=requestedOrientation
                requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                (view.parent as? ViewGroup)?.removeView(view)
                fullLayer.removeAllViews()
                fullLayer.addView(view,FrameLayout.LayoutParams(-1,-1))
                fullLayer.visibility=View.VISIBLE
                main.visibility=View.GONE
            }
            override fun onHideCustomView(){exitFullscreen()}
        }
        webHolder.addView(web,FrameLayout.LayoutParams(-1,-1))

        val tabs=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            setPadding(dp(9),dp(4),dp(9),dp(4))
        }
        listOf(
            "⌂ Ana sayfa" to { go(SubscriptionPortalPolicy.homepage(service)) },
            "◷ Son içerikler" to { showHistory() },
            "↗ Uygulamada izle" to { openCurrentInOfficial() }
        ).forEach{(title,action)->
            val chip=control(title,action).apply{textSize=12f}
            tabs.addView(chip,LinearLayout.LayoutParams(0,dp(46),1f).apply{
                rightMargin=dp(5)
            })
        }
        main.addView(tabs)
        val footer=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            setPadding(dp(10),dp(4),dp(10),dp(8))
        }
        footer.addView(control("‹ Geri"){
            if(web.canGoBack())web.goBack() else finish()
        },LinearLayout.LayoutParams(0,dp(46),1f))
        footer.addView(control("⟳ Yenile"){web.reload()},
            LinearLayout.LayoutParams(0,dp(46),1f).apply{leftMargin=dp(7)})
        main.addView(footer)

        fullLayer=FrameLayout(this).apply{
            setBackgroundColor(Color.BLACK)
            visibility=View.GONE
        }
        root.addView(fullLayer,FrameLayout.LayoutParams(-1,-1))
        go(SubscriptionPortalPolicy.homepage(service))
    }

    private fun go(url:String){
        if(SubscriptionPortalPolicy.isAllowed(service,url))web.loadUrl(url)
        else Toast.makeText(this,"Bu adres izin verilen resmî siteye ait değil.",
            Toast.LENGTH_SHORT).show()
    }

    private fun searchNow(){
        val searchUrl=SubscriptionPortalPolicy.search(service,searchField.text.toString())
            ?:return
        go(searchUrl)
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(searchField.windowToken,0)
        searchField.clearFocus()
    }

    private fun saveVisited(name:String?,url:String?){
        if(!url.isNullOrBlank() && SubscriptionPortalPolicy.isAllowed(service,url)){
            val canonical=StreamingPagePolicy.canonical(url)
            if(canonical?.service==service)
                StreamingHistoryStore.remember(this,name.orEmpty().take(80),canonical.url)
        }
    }

    private fun showHistory(){
        val entries=StreamingHistoryStore.load(this,service)
        if(entries.isEmpty()){
            AlertDialog.Builder(this)
                .setTitle(service.displayName+" • Son içerikler")
                .setMessage("Açtığın film/dizi sayfaları burada görünecek. " +
                    "Gerçek izleme süresi veya hesap bilgileri kaydedilmez.")
                .setPositiveButton("Tamam",null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(service.displayName+" • Son içerikler")
            .setItems(entries.map{it.title}.toTypedArray()){_,index->
                go(entries[index].url)
            }
            .setNeutralButton("Geçmişi temizle"){_,_->
                AlertDialog.Builder(this)
                    .setTitle("Yerel geçmiş silinsin mi?")
                    .setNegativeButton("Vazgeç",null)
                    .setPositiveButton("Temizle"){_,_->
                        StreamingHistoryStore.clear(this,service)
                    }.show()
            }
            .setNegativeButton("Kapat",null).show()
    }

    private fun openCurrentInOfficial() {
        // Same-title handoff when the website is on a known title page,
        // otherwise fall back to the official native app's home screen.
        val current=if(::web.isInitialized)web.url.orEmpty() else ""
        InstalledMediaApps.openStreamingTitle(this,service,current)
    }

    private fun exitFullscreen(){
        if(customView==null)return
        fullLayer.removeAllViews()
        customView=null
        fullLayer.visibility=View.GONE
        customCallback?.onCustomViewHidden();customCallback=null
        requestedOrientation=originalOrientation
        main.visibility=View.VISIBLE
    }

    @Deprecated("Back presses use the activity API")
    override fun onBackPressed(){
        when{
            customView!=null->exitFullscreen()
            ::web.isInitialized && web.canGoBack()->web.goBack()
            else->super.onBackPressed()
        }
    }
    override fun onPause(){
        if(::web.isInitialized){
            web.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause();});",null)
            web.onPause()
        }
        CookieManager.getInstance().flush()
        super.onPause()
    }
    override fun onResume(){
        super.onResume()
        if(::web.isInitialized)web.onResume()
    }
    override fun onDestroy(){
        if(customView!=null)exitFullscreen()
        if(::web.isInitialized){
            (web.parent as? ViewGroup)?.removeView(web)
            web.stopLoading();web.destroy()
        }
        if(::header.isInitialized)header.release()
        super.onDestroy()
    }
}
