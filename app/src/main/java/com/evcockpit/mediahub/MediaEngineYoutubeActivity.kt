package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.net.URLEncoder
import java.util.Locale

/**
 * MediaEngine-style YouTube section: native search/navigation chrome and in-app page.
 * Public mobile YouTube website runs in a sandboxed WebView; this is not a
 * copied official client, a private API, or a substitute for the YouTube app.
 * Website login/playback/DRM behavior is owned by YouTube, not guaranteed.
 */
class MediaEngineYoutubeActivity : Activity() {
    private lateinit var header: MediaEngineSessionHeader
    private lateinit var web: WebView
    private lateinit var shell: LinearLayout
    private lateinit var search: EditText
    private lateinit var loading: ProgressBar
    private lateinit var overlay: FrameLayout
    private var custom: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var orientationBefore = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private val backdropColor=0xff0b1316.toInt()
    private val muted=0xff9fb1b6.toInt()
    private val mint=0xff76e2d4.toInt()

    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun shape(color:Int,r:Int=13)=GradientDrawable().apply{
        setColor(color);cornerRadius=dp(r).toFloat()
    }
    private fun label(name:String,n:Float=15f,color:Int=Color.WHITE,
                      bold:Boolean=false)=TextView(this).apply{
        text=name;textSize=n;setTextColor(color)
        gravity=Gravity.CENTER
        if(bold)setTypeface(typeface,Typeface.BOLD)
        isFocusable=true
    }
    private fun control(name:String,action:()->Unit)=label(name,18f).apply{
        background=shape(0xff243337.toInt(),14)
        isClickable=true;isFocusable=true
        setOnClickListener{action()}
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=backdropColor
        window.navigationBarColor=backdropColor
        val root=FrameLayout(this).apply{setBackgroundColor(backdropColor)}
        setContentView(root)
        val main=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(backdropColor)
        }
        root.addView(main,FrameLayout.LayoutParams(-1,-1))
        root.setOnApplyWindowInsetsListener{v,insets->
            if(android.os.Build.VERSION.SDK_INT>=30){
                val bars=insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0,bars.top,0,bars.bottom)
            }
            insets
        }
        header=MediaEngineSessionHeader(this)
        main.addView(header.view)
        val title=LinearLayout(this).apply{
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(12),dp(7),dp(12),dp(6))
        }
        title.addView(label("▶",25f,0xfffa1635.toInt(),true),
            LinearLayout.LayoutParams(dp(43),dp(45)))
        title.addView(label("YouTube",19f,Color.WHITE,true).apply{
            gravity=Gravity.CENTER_VERTICAL
        },LinearLayout.LayoutParams(0,dp(45),1f))
        title.addView(control("↗"){InstalledMediaApps.openYouTube(this)},
            LinearLayout.LayoutParams(dp(48),dp(46)).apply{rightMargin=dp(6)})
        title.addView(control("⌂"){finish()},LinearLayout.LayoutParams(dp(49),dp(46)))
        main.addView(title)

        val input=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            setPadding(dp(9),dp(4),dp(9),dp(5))
            gravity=Gravity.CENTER_VERTICAL
        }
        search=EditText(this).apply{
            setSingleLine(true)
            hint="YouTube'da ara"
            textSize=14f
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            inputType=android.text.InputType.TYPE_CLASS_TEXT
            imeOptions=EditorInfo.IME_ACTION_SEARCH
            background=shape(0xff242e32.toInt(),26)
            setPadding(dp(14),dp(8),dp(12),dp(8))
            setOnEditorActionListener{_,_,_->searchNow();true}
        }
        input.addView(search,LinearLayout.LayoutParams(0,dp(47),1f))
        input.addView(control("⌕"){searchNow()},
            LinearLayout.LayoutParams(dp(52),dp(47)).apply{leftMargin=dp(5)})
        main.addView(input)

        loading=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{
            visibility=View.GONE;max=100
            progressTintList=android.content.res.ColorStateList.valueOf(mint)
        }
        main.addView(loading,LinearLayout.LayoutParams(-1,dp(3)))
        val area=FrameLayout(this).apply{setBackgroundColor(Color.BLACK)}
        main.addView(area,LinearLayout.LayoutParams(-1,0,1f))
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
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false)
        web.webViewClient=object:WebViewClient(){
            override fun shouldOverrideUrlLoading(view:WebView?,request:WebResourceRequest):Boolean{
                // Subresource frames can legitimately use video delivery / authentication
                // endpoints. Only constrain the main navigation, not video assets.
                if(!request.isForMainFrame)return false
                val url=request.url.toString()
                return !allowed(url)
            }
            override fun doUpdateVisitedHistory(view:WebView?,url:String?,isReload:Boolean){
                super.doUpdateVisitedHistory(view,url,isReload)
                if(!isReload)remember(url,view?.title)
            }
            override fun onPageFinished(view:WebView?,url:String?){
                remember(url,view?.title)
                loading.visibility=View.GONE
            }
            override fun onReceivedSslError(view:WebView?,handler:SslErrorHandler,error:SslError){
                handler.cancel()
                Toast.makeText(this@MediaEngineYoutubeActivity,
                    "Güvenli bağlantı sertifikası doğrulanamadı",Toast.LENGTH_SHORT).show()
            }
            override fun onRenderProcessGone(view:WebView?,detail:RenderProcessGoneDetail):Boolean{
                area.removeView(web)
                Toast.makeText(this@MediaEngineYoutubeActivity,
                    "YouTube oynatıcısı kapandı. Yeniden aç.",Toast.LENGTH_LONG).show()
                finish()
                return true
            }
        }
        web.webChromeClient=object:WebChromeClient(){
            override fun onProgressChanged(view:WebView?,newProgress:Int){
                loading.progress=newProgress
                loading.visibility=if(newProgress>=100)View.GONE else View.VISIBLE
            }
            override fun onShowCustomView(view:View?,callback:CustomViewCallback?){
                if(view==null||callback==null||custom!=null){
                    callback?.onCustomViewHidden();return
                }
                custom=view
                customCallback=callback
                orientationBefore=requestedOrientation
                requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                overlay.removeAllViews()
                (view.parent as? ViewGroup)?.removeView(view)
                overlay.addView(view,FrameLayout.LayoutParams(-1,-1))
                overlay.visibility=View.VISIBLE
                main.visibility=View.GONE
            }
            override fun onHideCustomView(){exitFullscreen()}
        }
        area.addView(web,FrameLayout.LayoutParams(-1,-1))

        val navigate=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            setPadding(dp(9),dp(5),dp(9),dp(5))
        }
        listOf(
            "⌂ Ana sayfa" to "https://m.youtube.com/",
            "▶ Shorts" to "https://m.youtube.com/shorts",
            "◎ Siz" to "https://m.youtube.com/feed/you"
        ).forEach{(text,url)->
            val control=label(text,13f).apply{
                background=shape(0xff19272b.toInt())
                isClickable=true
                setOnClickListener{navigate(url)}
            }
            navigate.addView(control,LinearLayout.LayoutParams(0,dp(46),1f).apply{
                rightMargin=dp(5)
            })
        }
        main.addView(navigate)
        val footer=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            setPadding(dp(11),dp(4),dp(11),dp(8))
        }
        footer.addView(control("‹ Geri"){if(web.canGoBack())web.goBack() else finish()},
            LinearLayout.LayoutParams(0,dp(46),1f))
        footer.addView(control("◷ Geçmiş"){showHistory()},
            LinearLayout.LayoutParams(0,dp(46),1f).apply{leftMargin=dp(7)})
        footer.addView(control("⟳ Yenile"){web.reload()},
            LinearLayout.LayoutParams(0,dp(46),1f).apply{leftMargin=dp(7)})
        main.addView(footer)

        overlay=FrameLayout(this).apply{
            setBackgroundColor(Color.BLACK)
            visibility=View.GONE
        }
        root.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        navigate("https://m.youtube.com/")
    }
    private fun allowed(url:String):Boolean{
        val safe=BrowserStore.safeHttps(url)?:return false
        val host=try{java.net.URI(safe).host?.lowercase(Locale.ROOT)}catch(_:Exception){null}
        return host in setOf("m.youtube.com","www.youtube.com","youtube.com",
            "consent.youtube.com","www.youtube-nocookie.com",
            "youtube-nocookie.com")
    }
    private fun navigate(url:String){
        if(allowed(url)) web.loadUrl(url)
        else Toast.makeText(this,"Bu bağlantı YouTube dışında.",Toast.LENGTH_SHORT).show()
    }
    private fun searchNow(){
        val query=search.text.toString().trim().take(140)
        if(query.isEmpty())return
        val encoded=URLEncoder.encode(query,"UTF-8")
        navigate("https://m.youtube.com/results?search_query="+encoded)
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(search.windowToken,0)
        search.clearFocus()
    }
    private fun remember(url:String?,title:String?){
        if(url.isNullOrBlank())return
        YoutubeVideoStore.remember(this,title.orEmpty().take(80),url)
    }
    private fun showHistory(){
        val items=YoutubeVideoStore.load(this)
        if(items.isEmpty()){
            AlertDialog.Builder(this).setTitle("Son YouTube videoları")
                .setMessage("İzlediğin video sayfalarının bağlantıları burada görünecek. " +
                    "Kayıtlar yalnızca bu telefondadır.")
                .setPositiveButton("Tamam",null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Son YouTube videoları")
            .setItems(items.map{it.title}.toTypedArray()){_,i->
                navigate(items[i].url)
            }
            .setNeutralButton("Temizle"){_,_->
                AlertDialog.Builder(this).setTitle("YouTube geçmişi silinsin mi?")
                    .setNegativeButton("Vazgeç",null)
                    .setPositiveButton("Sil"){_,_->
                        YoutubeVideoStore.clear(this)
                    }.show()
            }
            .setNegativeButton("Kapat",null).show()
    }
    private fun exitFullscreen(){
        if(custom==null)return
        overlay.removeAllViews()
        custom=null
        overlay.visibility=View.GONE
        customCallback?.onCustomViewHidden()
        customCallback=null
        requestedOrientation=orientationBefore
        shellRestore()
    }
    private fun shellRestore(){
        // Only the main layout is a non-overlay direct child of the root.
        val frame=overlay.parent as? FrameLayout ?:return
        (0 until frame.childCount).forEach{i->
            val view=frame.getChildAt(i)
            if(view!==overlay)view.visibility=View.VISIBLE
        }
    }
    override fun onBackPressed(){
        when{
            custom!=null->exitFullscreen()
            ::web.isInitialized && web.canGoBack()->web.goBack()
            else->super.onBackPressed()
        }
    }
    override fun onPause(){
        if(::web.isInitialized)web.onPause()
        super.onPause()
    }
    override fun onResume(){
        super.onResume()
        if(::web.isInitialized)web.onResume()
    }
    override fun onDestroy(){
        if(custom!=null)exitFullscreen()
        if(::web.isInitialized){
            (web.parent as? ViewGroup)?.removeView(web)
            web.stopLoading();web.destroy()
        }
        if(::header.isInitialized)header.release()
        super.onDestroy()
    }
}
