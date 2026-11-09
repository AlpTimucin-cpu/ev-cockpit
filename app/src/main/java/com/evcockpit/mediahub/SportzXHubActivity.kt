package com.evcockpit.mediahub

import android.app.Activity
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
 * MediaEngine-like phone-side SportzX website view.
 *
 * IMPORTANT: sportzxlive.com is a public APK / information website, not a
 * verified playable web streaming catalogue. No SportzX app code, servers,
 * channel playlists or private playback endpoints are reused.
 */
class SportzXHubActivity : Activity() {
    private lateinit var mediaHeader: MediaEngineSessionHeader
    private lateinit var page: WebView
    private lateinit var main: LinearLayout
    private lateinit var root: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var fullscreen: FrameLayout
    private var expanded: View? = null
    private var expandedCallback: WebChromeClient.CustomViewCallback? = null
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun chip(text: String, action: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(0xff243b40.toInt())
            cornerRadius = dp(12).toFloat()
        }
        setOnClickListener { action() }
        isClickable = true
        isFocusable = true
    }
    private fun message(value: String) {
        status.text = value
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xff0b1316.toInt()
        window.navigationBarColor = 0xff0b1316.toInt()
        root = FrameLayout(this).apply { setBackgroundColor(0xff0b1316.toInt()) }
        main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(main, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
            }
            insets
        }
        setContentView(root)
        mediaHeader = MediaEngineSessionHeader(this)
        main.addView(mediaHeader.view)

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(6))
        }
        toolbar.addView(TextView(this).apply {
            text = "◉ SportzX"
            textSize = 20f
            setTextColor(0xff76e2d4.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        toolbar.addView(chip("↗ APK uygulaması") { SportzXLauncher.openIfInstalled(this) },
            LinearLayout.LayoutParams(dp(130), dp(47)))
        toolbar.addView(chip("⌂") { finish() },
            LinearLayout.LayoutParams(dp(48), dp(47)).apply { leftMargin = dp(7) })
        main.addView(toolbar)

        status = TextView(this).apply {
            text = "SportzX sitesi uygulama içinde açılıyor. Bu sayfa bir APK tanıtım sitesidir; " +
                "canlı maç oynatımı sunacağı garanti değildir."
            setTextColor(0xffc6d4d8.toInt())
            textSize = 12f
            setPadding(dp(15), dp(7), dp(15), dp(9))
        }
        main.addView(status)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(9), dp(4), dp(9), dp(6))
        }
        search = EditText(this).apply {
            hint = "Bu sayfada ara"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(0xffa5b5b9.toInt())
            textSize = 14f
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> searchInPage(); true }
        }
        bar.addView(search, LinearLayout.LayoutParams(0, dp(50), 1f))
        bar.addView(chip("⌕ Ara") { searchInPage() },
            LinearLayout.LayoutParams(dp(73), dp(48)))
        main.addView(bar)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
        }
        main.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        val surface = FrameLayout(this)
        main.addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))
        page = WebView(this).apply { setBackgroundColor(Color.WHITE) }
        with(page.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            safeBrowsingEnabled = true
        }
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(page, false)
        page.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (allowed(request.url.toString())) return false
                message("Site dışı bağlantı bu dahili ekranda engellendi.")
                return true
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                message("Sertifika doğrulanamadı. Bağlantı engellendi.")
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest,
                                         error: WebResourceError) {
                if (request.isForMainFrame)
                    message("SportzX web sitesi yüklenemedi. Kurulu Android uygulamasını açabilirsin.")
            }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail): Boolean {
                surface.removeView(page)
                Toast.makeText(this@SportzXHubActivity,
                    "Web görüntüleyicisi kapandı. Ekranı yeniden aç.", Toast.LENGTH_LONG).show()
                finish()
                return true
            }
        }
        page.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, value: Int) {
                progress.progress = value
                progress.visibility = if (value >= 100) View.GONE else View.VISIBLE
            }
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || callback == null || expanded != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                expanded = view
                expandedCallback = callback
                previousOrientation = requestedOrientation
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                (view.parent as? ViewGroup)?.removeView(view)
                fullscreen.addView(view, FrameLayout.LayoutParams(-1, -1))
                fullscreen.visibility = View.VISIBLE
                main.visibility = View.GONE
            }
            override fun onHideCustomView() { leaveFullscreen() }
        }
        surface.addView(page, FrameLayout.LayoutParams(-1, -1))

        val controls = LinearLayout(this).apply { setPadding(dp(8), dp(5), dp(8), dp(6)) }
        listOf(
            "⌂ Site" to { page.loadUrl(URL) },
            "‹ Geri" to { if (page.canGoBack()) page.goBack() else finish() },
            "⟳ Yenile" to { page.reload() }
        ).forEach { (name, action) ->
            controls.addView(chip(name, action), LinearLayout.LayoutParams(
                0, dp(46), 1f).apply { rightMargin = dp(6) })
        }
        main.addView(controls)
        fullscreen = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }
        root.addView(fullscreen, FrameLayout.LayoutParams(-1, -1))
        page.loadUrl(URL)
    }

    private fun allowed(candidate: String): Boolean = SportzXSitePolicy.allow(candidate)

    private fun searchInPage() {
        val phrase = search.text.toString().trim().take(80)
        if (phrase.isBlank()) { page.clearMatches(); return }
        page.findAllAsync(phrase)
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }
    private fun leaveFullscreen() {
        if (expanded == null) return
        fullscreen.removeAllViews()
        expanded = null
        expandedCallback?.onCustomViewHidden()
        expandedCallback = null
        fullscreen.visibility = View.GONE
        main.visibility = View.VISIBLE
        requestedOrientation = previousOrientation
    }
    @Deprecated("System back navigation")
    override fun onBackPressed() {
        when {
            expanded != null -> leaveFullscreen()
            ::page.isInitialized && page.canGoBack() -> page.goBack()
            else -> super.onBackPressed()
        }
    }
    override fun onPause() {
        if (::page.isInitialized) page.onPause()
        super.onPause()
    }
    override fun onResume() {
        super.onResume()
        if (::page.isInitialized) page.onResume()
    }
    override fun onDestroy() {
        if (expanded != null) leaveFullscreen()
        if (::page.isInitialized) {
            (page.parent as? ViewGroup)?.removeView(page)
            page.stopLoading()
            page.destroy()
        }
        if (::mediaHeader.isInitialized) mediaHeader.release()
        super.onDestroy()
    }
    companion object { const val URL = SportzXSitePolicy.HOME }
}
