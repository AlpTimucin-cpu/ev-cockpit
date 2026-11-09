package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.app.Dialog
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.datasource.HttpDataSource

/** Phone-only IPTV. This UI never projects video into Android Auto. */
class TvPlayback(
    private val activity: Activity,
    private val catalog: () -> List<VideoChannel>,
    private val showLibrary: () -> Unit,
    private val onOpened: (VideoChannel) -> Unit
) {
    private var player: ExoPlayer? = null
    private var current: VideoChannel? = null
    private var maxQualityHeight = Int.MAX_VALUE
    private var fullScreen: Dialog? = null
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private lateinit var videoHost: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var title: TextView
    private lateinit var playbackState: TextView
    private lateinit var epgStatus: TextView
    private lateinit var catalogState: TextView
    private lateinit var quickAccess: LinearLayout
    private var initialized = false
    private val diagnostics = PlaybackDiagnostics()
    private val retries = StreamRetryBudget()
    private val stallGuard = PlaybackStallGuard()
    private val retryHandler = Handler(Looper.getMainLooper())
    private var scheduledRetry: Runnable? = null
    private var playGeneration = 0
    private var autoRecoveryAllowed = false
    // Unlike an HTTP error, a dead live-HLS CDN may stay BUFFERING forever.
    private val stallMonitor = object : Runnable {
        override fun run() {
            if (!initialized || !autoRecoveryAllowed || current == null ||
                activity.isFinishing || activity.isDestroyed) return
            if (stallGuard.shouldRecover(SystemClock.elapsedRealtime())) {
                diagnostics.problem("BUFFER_STALL")
                val delay = retries.nextDelay(true)
                if (delay == null) {
                    autoRecoveryAllowed = false
                    playbackState.text =
                        "●  Yayın 25 saniyedir veri göndermiyor; yeniden bağlanma sınırına ulaşıldı."
                    (overlay as? TextView)?.text =
                        "YAYIN YANIT VERMİYOR\n\nGüncel kanal seçmek için dokun"
                    overlay.visibility = View.VISIBLE
                    player?.pause()
                } else {
                    playbackState.text =
                        "●  Yayın 25 saniyedir yüklenemiyor; tekrar deneniyor (" +
                        retries.attempts + "/2)"
                    scheduleRecovery(delay)
                }
            }
            if (autoRecoveryAllowed) retryHandler.postDelayed(this, 5_000L)
        }
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun bg(color: Int, radius: Int = 14) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun label(content: String, size: Float = 14f, muted: Boolean = false): TextView =
        TextView(activity).apply {
            text = content
            textSize = size
            setTextColor(if (muted) 0xffa8bdd2.toInt() else Color.WHITE)
            setPadding(dp(5), dp(8), dp(5), dp(8))
        }
    private fun button(content: String, action: () -> Unit): Button = Button(activity).apply {
        text = content
        textSize = 13f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = bg(0xff244b66.toInt(), 10)
        setOnClickListener { action() }
    }

    fun view(): View {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(label("CANLI TV  •  CANLI YAYIN", 19f))
        root.addView(label("Kanal rehberi · Kalite · Tam ekran", 12f, true))
        videoHost = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        val screenWidth = activity.resources.displayMetrics.widthPixels - dp(32)
        root.addView(videoHost, LinearLayout.LayoutParams(-1, screenWidth * 9 / 16))
        playerView = PlayerView(activity).apply {
            // Live streams do not have a useful VOD seek timeline. Use our TV controls instead.
            useController = false
            controllerShowTimeoutMs = 3500
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.BLACK)
        }
        videoHost.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        overlay = label("▶   Kanal seçmek için dokun", 17f).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(0xdd0b1c31.toInt())
            setOnClickListener { showChannels() }
        }
        videoHost.addView(overlay, FrameLayout.LayoutParams(-1,-1))
        title = label("Kanal seçilmedi", 18f).apply { maxLines = 2 }
        root.addView(title)
        playbackState = label("Kanallar düğmesiyle yayını başlatabilirsin.", 12f, true)
        root.addView(playbackState)
        epgStatus = label("EPG: Program bilgisi için XMLTV kaynağı ekleyebilirsin.",12f,true)
        root.addView(epgStatus)

        val navigation = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        navigation.addView(button("◀") { next(-1) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        navigation.addView(button("▦  Kanallar") { showChannels() },
            LinearLayout.LayoutParams(0,dp(48),2.7f))
        navigation.addView(button("▶") { next(1) }, LinearLayout.LayoutParams(0,dp(48),1f))
        root.addView(navigation)
        val options = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val quality = button("⚙  Görüntü kalitesi") {}
        quality.setOnClickListener { showQuality(quality) }
        options.addView(quality, LinearLayout.LayoutParams(0,dp(49),1f))
        options.addView(button("⏯  Oynat / Duraklat") {
            player?.let {
                if (it.isPlaying || (scheduledRetry != null)) {
                    pause()
                } else {
                    autoRecoveryAllowed = true
                    it.play()
                }
            }
        }, LinearLayout.LayoutParams(0,dp(49),1.15f))
        options.addView(button("⛶  Tam ekran") { fullScreen() },
            LinearLayout.LayoutParams(0,dp(49),1f))
        root.addView(options)
        val streams = button("🔊  Ses dili / Altyazı seçenekleri") {}
        streams.setOnClickListener { showTrackOptions(streams) }
        root.addView(streams, LinearLayout.LayoutParams(-1, dp(46)))
        root.addView(button("↗  FermataX 2.2.6 ile telefonda aç") {
            FermataXInterop.offerVideo(activity, current)
        }, LinearLayout.LayoutParams(-1, dp(46)))
        root.addView(button("▶ VLC motoruyla oynat • MediaEngine") {
            MediaEngineVlcPlayerActivity.open(activity, current)
        }, LinearLayout.LayoutParams(-1, dp(46)))
        root.addView(button("📊  Yayın performansı ve bağlantı raporu") {
            showPlaybackDiagnostics()
        }, LinearLayout.LayoutParams(-1, dp(48)))

        catalogState = label("", 12f, true)
        root.addView(catalogState)
        root.addView(label("HIZLI KANAL SEÇ", 16f))
        val scroller=HorizontalScrollView(activity).apply{isHorizontalScrollBarEnabled=false}
        quickAccess=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
        scroller.addView(quickAccess)
        root.addView(scroller)
        root.addView(label("Otomatik kalite ve seçilen çözünürlük, yayının mevcut HLS varyantlarıyla sınırlıdır.",11f,true))
        initialized=true
        refresh()
        return root
    }

    @OptIn(UnstableApi::class)
    private fun player(): ExoPlayer {
        player?.let { return it }
        val instance = ExoPlayer.Builder(activity).build()
        instance.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long
            ) {
                diagnostics.dropped(droppedFrames)
            }
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long
            ) {
                diagnostics.videoDecoder(decoderName, initializationDurationMs)
            }
            override fun onVideoFrameProcessingOffset(
                eventTime: AnalyticsListener.EventTime,
                totalProcessingOffsetUs: Long, frameCount: Int
            ) {
                diagnostics.processedFrames(frameCount)
            }
        })
        instance.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                diagnostics.firstFrame(SystemClock.elapsedRealtime())
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                diagnostics.videoSize(videoSize.width, videoSize.height)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (!initialized) return
                if (state == Player.STATE_BUFFERING) {
                    val now=SystemClock.elapsedRealtime()
                    diagnostics.buffering(now)
                    stallGuard.buffering(now)
                }
                if (state == Player.STATE_READY) {
                    diagnostics.ready(SystemClock.elapsedRealtime())
                    stallGuard.ready()
                    overlay.visibility = View.GONE
                }
                playbackState.text = when(state) {
                    Player.STATE_BUFFERING -> "●  Yayın yükleniyor…"
                    Player.STATE_READY -> "●  Canlı yayın oynatılıyor"
                    Player.STATE_ENDED -> "●  Yayın bitti"
                    else -> "●  Bağlanılıyor"
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val message = playbackErrorExplanation(error)
                diagnostics.ready(SystemClock.elapsedRealtime())
                stallGuard.ready()
                // Store only our own error classification, never a URL or exception text.
                diagnostics.problem(error.errorCodeName)
                val mayRetry = autoRecoveryAllowed && initialized &&
                    !activity.isFinishing && !activity.isDestroyed && current != null
                val delay = if (mayRetry) retries.nextDelay(isTransient(error)) else null
                if (delay != null) {
                    scheduleRecovery(delay)
                    playbackState.text = "●  Geçici bağlantı sorunu · " +
                        (delay / 1000.0) + " sn sonra yeniden denenecek (" +
                        retries.attempts + "/2)"
                    overlay.visibility = View.GONE
                } else if (initialized) {
                    playbackState.text = "●  " + message
                    (overlay as? TextView)?.text = "YAYIN AÇILAMADI\n\n" + message +
                        "\n\nKanalları açmak için dokun"
                    overlay.visibility = View.VISIBLE
                }
            }
        })
        player=instance
        playerView.player=instance
        applyQuality(maxQualityHeight)
        return instance
    }

    fun play(channel: VideoChannel) {
        cancelRecovery()
        autoRecoveryAllowed = true
        retries.reset()
        stallGuard.restart(SystemClock.elapsedRealtime())
        diagnostics.start(SystemClock.elapsedRealtime())
        retryHandler.removeCallbacks(stallMonitor)
        retryHandler.postDelayed(stallMonitor, 5_000L)
        current=channel
        if (!initialized) return
        overlay.visibility=View.GONE
        title.text=M3uParser.displayTitle(channel.name)
        epgStatus.text = EpgGuide.currentLabel(activity, channel.tvgId)
        playbackState.text="●  Yayın başlatılıyor…"
        try {
            val item=MediaItem.Builder().setUri(Uri.parse(channel.url)).apply {
                when (StreamFormatDetector.detect(channel.url)) {
                    StreamFormat.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    StreamFormat.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
                    StreamFormat.AUTO -> { /* Media3 infers the type from the response. */ }
                }
            }.build()
            player().apply {
                setMediaItem(item)
                prepare()
                playWhenReady=true
            }
            onOpened(channel)
        } catch (_: Exception) {
            playbackState.text="Bu kanal başlatılamadı"
        }
        refresh()
    }

    private fun isTransient(error: PlaybackException): Boolean {
        var cause: Throwable? = error.cause
        repeat(6) {
            val next = cause ?: return@repeat
            if (next is HttpDataSource.InvalidResponseCodeException) {
                // 401/403/404/429 can mean authorization, denial or rate limiting:
                // no automatic retries for those responses.
                return next.responseCode in 500..599
            }
            cause = next.cause
        }
        return error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
    }

    private fun cancelRecovery() {
        scheduledRetry?.let { retryHandler.removeCallbacks(it) }
        scheduledRetry = null
        playGeneration++
    }

    private fun scheduleRecovery(delayMs: Long) {
        val capturedGeneration = playGeneration
        val item = player?.currentMediaItem ?: return
        val task = object : Runnable {
            override fun run() {
                scheduledRetry = null
                if (playGeneration != capturedGeneration || !initialized ||
                    !autoRecoveryAllowed || activity.isFinishing || activity.isDestroyed ||
                    current == null) return
                diagnostics.retry()
                stallGuard.restart(SystemClock.elapsedRealtime())
                try {
                    player?.apply {
                        setMediaItem(item)
                        prepare()
                        playWhenReady = true
                    }
                    playbackState.text = "●  Yayına yeniden bağlanılıyor…"
                } catch (_: Exception) {
                    playbackState.text = "●  Yayın yeniden başlatılamadı"
                }
            }
        }
        scheduledRetry?.let { retryHandler.removeCallbacks(it) }
        scheduledRetry = task
        retryHandler.postDelayed(task, delayMs)
    }

    private fun showPlaybackDiagnostics() {
        val report = diagnostics.report(SystemClock.elapsedRealtime())
        AlertDialog.Builder(activity)
            .setTitle("IPTV performans raporu")
            .setMessage(report)
            .setNegativeButton("Kapat", null)
            .setPositiveButton("Raporu kopyala") { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("EVCOCKPIT IPTV tanılama", report))
            }.show()
    }

    fun refresh() {
        if (!initialized) return
        val channels=catalog()
        current?.let { epgStatus.text = EpgGuide.currentLabel(activity, it.tvgId) }
        catalogState.text=channels.size.toString() + " kanal · " +
            channels.count { it.favorite }.toString() + " favori"
        quickAccess.removeAllViews()
        if (channels.isEmpty()) {
            quickAccess.addView(button("＋ Kitaplıktan kanal ekle") { showLibrary() },
                LinearLayout.LayoutParams(dp(230), dp(80)))
            return
        }
        val featured=channels.filter{it.favorite}.take(10).ifEmpty{channels.take(10)}
        featured.forEach { channel ->
            val tile=LinearLayout(activity).apply {
                orientation=LinearLayout.VERTICAL
                gravity=Gravity.CENTER
                background=bg(0xff1b3048.toInt())
                setPadding(dp(6),dp(6),dp(6),dp(6))
                setOnClickListener { play(channel) }
            }
            val image=ImageView(activity).apply {
                setImageResource(android.R.drawable.ic_media_play)
                scaleType=ImageView.ScaleType.FIT_CENTER
            }
            tile.addView(image, LinearLayout.LayoutParams(dp(48),dp(47)))
            if(channel.logo.isNotBlank())ChannelLogoLoader.into(channel.logo,image,activity)
            tile.addView(label(channel.name,11f).apply {
                maxLines=1
                ellipsize=android.text.TextUtils.TruncateAt.END
                gravity=Gravity.CENTER
            },LinearLayout.LayoutParams(-1,dp(38)))
            val lp=LinearLayout.LayoutParams(dp(126),dp(94))
            lp.setMargins(0,0,dp(7),0)
            quickAccess.addView(tile,lp)
        }
    }

    /** User-friendly playback error; no stream URL or subscriber token is logged. */
    private fun playbackErrorExplanation(error: PlaybackException): String {
        var currentCause: Throwable? = error.cause
        repeat(5) {
            val cause = currentCause ?: return@repeat
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return when (cause.responseCode) {
                    401, 403 -> "Yayın sunucusu erişimi engelledi (HTTP " + cause.responseCode +
                        "). Oturum, yayın hakkı veya bölge kısıtlaması olabilir."
                    404, 410 -> "Bu yayın adresi kaldırılmış veya değişmiş (HTTP " +
                        cause.responseCode + ")."
                    429 -> "Sunucu çok fazla bağlantı nedeniyle geçici olarak reddetti (HTTP 429)."
                    in 500..599 -> "Yayın sunucusunda hata var (HTTP " + cause.responseCode + ")."
                    else -> "Yayın sunucusu HTTP " + cause.responseCode + " yanıtını verdi."
                }
            }
            currentCause = cause.cause
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Yayın sunucusuna bağlanılamadı. İnternetini ve kanal kaynağını kontrol et."
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "Yayın sunucusu bu isteği kabul etmedi. Kanalın erişim izni değişmiş olabilir."
            PlaybackException.ERROR_CODE_DECODING_FAILED ->
                "Yayının ses/video biçimi bu telefonda çözümlenemedi."
            else -> "Yayın başlatılamadı (" + error.errorCodeName +
                "). Kanal başka bir bölgeye kapalı veya kaynak kullanım dışı olabilir."
        }
    }

    private fun next(direction: Int) {
        val channels=catalog()
        if (channels.isEmpty()) {showLibrary();return}
        val index=channels.indexOfFirst{it.id == current?.id}
        play(channels[if(index < 0) 0 else (index + direction + channels.size) % channels.size])
    }

    private fun applyQuality(height: Int) {
        maxQualityHeight=height
        val playing=player ?: return
        playing.trackSelectionParameters=playing.trackSelectionParameters
            .buildUpon().setMaxVideoSize(Int.MAX_VALUE,height).build()
    }

    private fun showQuality(anchor: View) {
        val options=listOf("Otomatik" to Int.MAX_VALUE,"360p" to 360,
            "480p" to 480,"720p" to 720,"1080p" to 1080)
        val popup=PopupMenu(activity,anchor)
        options.forEach { (title,height) ->
            popup.menu.add((if(height==maxQualityHeight)"✓  " else "")+title)
                .setOnMenuItemClickListener {
                    applyQuality(height)
                    playbackState.text = "Kalite tercihi: " + title +
                        " · Kaynağın desteklediği çözünürlük uygulanır"
                    true
                }
        }
        popup.show()
    }

    private fun showTrackOptions(anchor: View) {
        val playing = player
        if (playing == null) {
            Toast.makeText(activity, "Önce bir kanal başlat", Toast.LENGTH_SHORT).show()
            return
        }
        val popup = PopupMenu(activity, anchor)
        val formats = playing.currentTracks.groups.flatMap { group ->
            val type = group.type
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) {
                emptyList()
            } else {
                (0 until group.mediaTrackGroup.length).mapNotNull { index ->
                    val value = group.mediaTrackGroup.getFormat(index).language
                    if (value.isNullOrBlank() || value == "und") null else type to value
                }
            }
        }.distinct()
        popup.menu.add("Altyazı kapat").setOnMenuItemClickListener {
            playing.trackSelectionParameters = playing.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            true
        }
        if (formats.isEmpty()) {
            popup.menu.add("Yayında seçilebilir dil / altyazı bulunamadı").isEnabled = false
        }
        formats.forEach { (type, language) ->
            val label = if (type == C.TRACK_TYPE_AUDIO) "Ses" else "Altyazı"
            popup.menu.add(label + " · " + language.uppercase()).setOnMenuItemClickListener {
                val builder = playing.trackSelectionParameters.buildUpon()
                if (type == C.TRACK_TYPE_AUDIO) builder.setPreferredAudioLanguage(language)
                else builder.setPreferredTextLanguage(language)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                playing.trackSelectionParameters = builder.build()
                true
            }
        }
        popup.show()
    }

    private fun fullScreen() {
        if (!initialized || fullScreen != null) return
        val previousParent=playerView.parent as? ViewGroup ?: return
        previousParent.removeView(playerView)
        previousOrientation=activity.requestedOrientation
        val dialog=Dialog(activity,android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val frame=FrameLayout(activity).apply{setBackgroundColor(Color.BLACK)}
        frame.addView(playerView,FrameLayout.LayoutParams(-1,-1))
        val top=LinearLayout(activity).apply{
            orientation=LinearLayout.HORIZONTAL
            setBackgroundColor(0xb90b1728.toInt())
        }
        top.addView(button("×  Kapat"){dialog.dismiss()},LinearLayout.LayoutParams(0,dp(47),1f))
        top.addView(button("▦  Kanallar"){showChannels()},LinearLayout.LayoutParams(0,dp(47),1f))
        val quality=button("⚙  Kalite"){}
        quality.setOnClickListener{showQuality(quality)}
        top.addView(quality,LinearLayout.LayoutParams(0,dp(47),1f))
        val langs=button("🔊  Dil / altyazı"){}
        langs.setOnClickListener{showTrackOptions(langs)}
        top.addView(langs,LinearLayout.LayoutParams(0,dp(47),1f))
        frame.addView(top,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        dialog.setContentView(frame)
        dialog.setOnDismissListener {
            (playerView.parent as? ViewGroup)?.removeView(playerView)
            previousParent.addView(playerView,FrameLayout.LayoutParams(-1,-1))
            activity.requestedOrientation=previousOrientation
            fullScreen=null
        }
        fullScreen=dialog
        dialog.show()
        dialog.window?.decorView?.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    fun showChannels() {
        val all=catalog()
        if(all.isEmpty()){showLibrary();return}
        // Snapshot once per channel-picker opening; do not reload persisted EPG per grid tile.
        val epgIndex = EpgScheduleIndex(EpgGuide.load(activity), System.currentTimeMillis())
        val dialog=Dialog(activity,android.R.style.Theme_Material_NoActionBar)
        val root=LinearLayout(activity).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(0xff0b1728.toInt())
            setPadding(dp(13),dp(10),dp(13),dp(10))
        }
        val header=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
        header.addView(label("KANALLAR",22f),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(button("×"){dialog.dismiss()},LinearLayout.LayoutParams(dp(53),dp(47)))
        root.addView(header)
        val search=EditText(activity).apply{
            hint="Kanal veya kategori ara"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(0xffa4b7c9.toInt())
            background=bg(0xff243b53.toInt())
            setPadding(dp(14),dp(9),dp(14),dp(9))
        }
        root.addView(search,LinearLayout.LayoutParams(-1,dp(50)))
        val categories=listOf("Tümü","★ Favoriler","📺 Şimdi yayında")+
            all.map{it.group}.filter{it.isNotBlank()}.distinct().sorted().take(35)
        var selected="Tümü"
        val strip=HorizontalScrollView(activity).apply{isHorizontalScrollBarEnabled=false}
        val categoryRow=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
        strip.addView(categoryRow)
        root.addView(strip,LinearLayout.LayoutParams(-1,dp(60)))
        val count=label("",12f,true)
        root.addView(count)
        val grid=GridView(activity).apply{
            numColumns=if(activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)5 else 3
            horizontalSpacing=dp(7)
            verticalSpacing=dp(7)
            stretchMode=GridView.STRETCH_COLUMN_WIDTH
            clipToPadding=false
        }
        root.addView(grid,LinearLayout.LayoutParams(-1,0,1f))
        var shown:List<VideoChannel> = all
        val adapter=object:BaseAdapter(){
            override fun getCount()=shown.size
            override fun getItem(position:Int):Any=shown[position]
            override fun getItemId(position:Int)=position.toLong()
            override fun getView(position:Int,convertView:View?,parent:ViewGroup):View{
                val channel=shown[position]
                val tile=LinearLayout(activity).apply{
                    orientation=LinearLayout.VERTICAL
                    gravity=Gravity.CENTER
                    setPadding(dp(3),dp(5),dp(3),dp(4))
                    background=bg(0xff1a314a.toInt(),12)
                    setOnClickListener{dialog.dismiss();play(channel)}
                }
                val logo=ImageView(activity).apply {
                    setImageResource(android.R.drawable.ic_media_play)
                    scaleType=ImageView.ScaleType.FIT_CENTER
                }
                tile.addView(logo,LinearLayout.LayoutParams(dp(60),dp(58)))
                if(channel.logo.isNotBlank()) ChannelLogoLoader.into(channel.logo,logo,activity)
                tile.addView(label(channel.name,12f).apply{
                    maxLines=2
                    gravity=Gravity.CENTER
                    ellipsize=android.text.TextUtils.TruncateAt.END
                },LinearLayout.LayoutParams(-1,dp(49)))
                val programme = epgIndex.current(channel.tvgId)
                if(programme != null) {
                    tile.addView(label("ŞİMDİ",10f).apply {
                        setTextColor(0xff78dfd6.toInt())
                        gravity=Gravity.CENTER
                        setPadding(dp(2),dp(1),dp(2),0)
                    })
                    tile.addView(label(programme.title,10f,true).apply {
                        maxLines=2
                        gravity=Gravity.CENTER
                        ellipsize=android.text.TextUtils.TruncateAt.END
                        setPadding(dp(2),0,dp(2),dp(2))
                    },LinearLayout.LayoutParams(-1,dp(37)))
                    val progress=android.widget.ProgressBar(
                        activity,null,android.R.attr.progressBarStyleHorizontal
                    ).apply {
                        max=100
                        this.progress=epgIndex.progressPercent(channel.tvgId)
                        progressTintList=android.content.res.ColorStateList.valueOf(0xff69dcd2.toInt())
                    }
                    tile.addView(progress,LinearLayout.LayoutParams(-1,dp(4)))
                    epgIndex.next(channel.tvgId)?.let { upcoming ->
                        tile.addView(label("SONRA · " + upcoming.title,9f,true).apply {
                            maxLines=1
                            ellipsize=android.text.TextUtils.TruncateAt.END
                            gravity=Gravity.CENTER
                        },LinearLayout.LayoutParams(-1,dp(30)))
                    }
                }
                return tile
            }
        }
        grid.adapter=adapter
        fun update(){
            val q=search.text.toString().trim()
            shown=all.filter { channel ->
                (selected=="Tümü" || selected=="★ Favoriler" && channel.favorite ||
                    selected=="📺 Şimdi yayında" && epgIndex.hasCurrent(channel.tvgId) ||
                    channel.group==selected) &&
                (q.isBlank() || channel.name.contains(q,true) ||
                    channel.group.contains(q,true) || epgIndex.matchingProgramme(channel.tvgId,q))
            }
            count.text=shown.size.toString()+" kanal · " +
                shown.count { epgIndex.hasCurrent(it.tvgId) } +
                " program eşleşmesi · Oynatmak için seç"
            adapter.notifyDataSetChanged()
        }
        categories.forEach{ category ->
            val chip=button(category){selected=category;update()}
            val lp=LinearLayout.LayoutParams(-2,dp(43))
            lp.setMargins(0,dp(7),dp(7),dp(4))
            categoryRow.addView(chip,lp)
        }
        search.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){update()}
            override fun afterTextChanged(s:Editable?){}
        })
        update()
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(-1,(activity.resources.displayMetrics.heightPixels*0.86).toInt())
    }

    fun pause(){
        autoRecoveryAllowed = false
        cancelRecovery()
        retryHandler.removeCallbacks(stallMonitor)
        stallGuard.ready()
        diagnostics.ready(SystemClock.elapsedRealtime())
        player?.pause()
    }
    fun release(){
        autoRecoveryAllowed = false
        initialized = false
        cancelRecovery()
        retryHandler.removeCallbacks(stallMonitor)
        stallGuard.ready()
        diagnostics.ready(SystemClock.elapsedRealtime())
        fullScreen?.dismiss()
        fullScreen=null
        playerView.player=null
        player?.release()
        player=null
    }
}
