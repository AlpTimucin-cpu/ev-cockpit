package com.evcockpit.mediahub

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors

/**
 * Phone-only VOD with bounded reconnection and private diagnostic export.
 * Distinct from DiPlay's BYD/CarPlay projection receiver: no USB, MFi, or
 * Android Auto video access or proprietary code is imported.
 */
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private var videoView: PlayerView? = null
    private var streamUri: Uri? = null
    private var playbackInfo: TextView? = null
    private var channelId: String = ""
    private var isVod = false
    private var ended = false
    private var sessionActive = false
    private var isOfflineTest = false
    private val diagnostics = PlaybackDiagnostics()
    private val retries = StreamRetryBudget()
    private val stallGuard = PlaybackStallGuard()
    private val retryHandler = Handler(Looper.getMainLooper())
    private var scheduledRetry: Runnable? = null
    private var sessionGeneration = 0

    // A silent CDN/HLS stall may never raise onPlayerError. Probe only while
    // this Activity is running; never retry indefinitely.
    private val stallMonitor = object : Runnable {
        override fun run() {
            if (!sessionActive) return
            if (stallGuard.shouldRecover(SystemClock.elapsedRealtime())) {
                diagnostics.problem("BUFFER_STALL")
                val delay = retries.nextDelay(true)
                if (delay == null) {
                    playbackInfo?.text =
                        "Yayın sunucusu 25 saniyedir yanıt vermiyor. " +
                        "Otomatik deneme sınırına ulaşıldı; farklı veya güncel kaynak seç."
                    player?.pause()
                } else {
                    playbackInfo?.text =
                        "Yayın 25 saniyedir yüklenemedi. " +
                        "Bağlantı yeniden denenecek (" + retries.attempts + "/2)."
                    scheduleRecovery(delay)
                }
            }
            retryHandler.postDelayed(this, 5_000L)
        }
    }
    private val sourceInspector = Executors.newSingleThreadExecutor()
    private var sourceProbeGeneration = 0
    private var sourceProbeInProgress = false
    private var lastSourceProbe = "Kaynak henüz ayrı olarak kontrol edilmedi."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isOfflineTest = intent.getBooleanExtra(EXTRA_OFFLINE_TEST, false)
        val value = if (isOfflineTest) OFFLINE_TEST_URI
            else intent.getStringExtra(EXTRA_URL) ?: intent.dataString ?: ""
        val parsed = Uri.parse(value)
        if (value != OFFLINE_TEST_URI && parsed.scheme != "content" &&
            !M3uParser.isHttpsUrl(value)) {
            finish()
            return
        }
        streamUri = parsed
        channelId = intent.getStringExtra(EXTRA_ID).orEmpty()
        isVod = intent.getBooleanExtra(EXTRA_IS_VOD, false) && channelId.isNotBlank()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(10, 16, 27))
            // Android 15 edge-to-edge otherwise overlays the status bar on
            // the long film title (reported on POCO F5).
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        layout.addView(TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE) ?: "Video oynatıcı"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(24, 24, 24, 12)
        })
        playbackInfo = TextView(this).apply {
            text = "Yayın hazırlanıyor…"
            setTextColor(Color.rgb(180, 201, 226))
            textSize = 14f
            setPadding(24, 6, 24, 12)
        }.also { layout.addView(it) }
        videoView = PlayerView(this).apply { useController = true }
        layout.addView(videoView, LinearLayout.LayoutParams(-1, 0, 1f))
        layout.addView(TextView(this).apply {
            text = if (isOfflineTest)
                "ÇEVRİMDIŞI TEST • 2 saniyelik tekrar eden yerel H.264 video. " +
                "İnternet ve film kaynakları kullanılmıyor."
            else "Video yalnızca güvenli biçimde park halindeyken izlenmelidir. " +
                "Android Auto ekranına yansıtılmaz."
            setTextColor(Color.LTGRAY)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(22, 14, 22, 12)
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actions.addView(Button(this).apply {
            text = "Yeniden dene"
            isAllCaps = false
            setOnClickListener {
                cancelRecovery()
                retries.reset()
                stallGuard.restart(SystemClock.elapsedRealtime())
                diagnostics.start(SystemClock.elapsedRealtime())
                ended = false
                playbackInfo?.text = "Yayın yeniden deneniyor…"
                player?.apply { prepare(); playWhenReady = true }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply {
            text = "Tanı raporunu paylaş"
            isAllCaps = false
            setOnClickListener { shareDiagnostics() }
        }, LinearLayout.LayoutParams(0, -2, 1.2f))
        layout.addView(actions)
        if (!isOfflineTest) layout.addView(Button(this).apply {
            text = "Kaynağı ve interneti kontrol et"
            isAllCaps = false
            setOnClickListener { checkSourceOnDemand() }
        })
        setContentView(layout)
    }

    @OptIn(UnstableApi::class)
    override fun onStart() {
        super.onStart()
        val uri = streamUri ?: return
        cancelRecovery()
        retries.reset()
        stallGuard.ready()
        diagnostics.start(SystemClock.elapsedRealtime())
        ended = false
        sessionActive = true
        retryHandler.removeCallbacks(stallMonitor)
        retryHandler.postDelayed(stallMonitor, 5_000L)

        val instance = ExoPlayer.Builder(this).build()
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
                if (isOfflineTest) playbackInfo?.text =
                    "YEREL TEST BAŞARILI • Media3 ilk video karesini ekrana çizdi"
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                diagnostics.videoSize(videoSize.width, videoSize.height)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (!sessionActive) return
                when (state) {
                    Player.STATE_BUFFERING -> {
                        val now = SystemClock.elapsedRealtime()
                        diagnostics.buffering(now)
                        stallGuard.buffering(now)
                        playbackInfo?.text = "Yayın yükleniyor…"
                    }
                    Player.STATE_READY -> {
                        diagnostics.ready(SystemClock.elapsedRealtime())
                        stallGuard.ready()
                        playbackInfo?.text = if (isOfflineTest)
                            "Yerel video hazır • Karelerin ekranda hareket ettiğini kontrol et"
                        else "Yayın hazır • Oynatılıyor"
                    }
                    Player.STATE_ENDED -> {
                        diagnostics.ready(SystemClock.elapsedRealtime())
                        stallGuard.ready()
                        ended = true
                        if (isVod) VodProgressStore.clear(this@PlayerActivity, channelId)
                        playbackInfo?.text = "Video sona erdi"
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (!sessionActive) return
                diagnostics.ready(SystemClock.elapsedRealtime())
                stallGuard.ready()
                val httpCode = findHttpStatus(error)
                val failure = VodErrorPolicy.classify(httpCode, error.errorCodeName)
                // Only safe enum categories, never Throwable.message or URL.
                val transport = if (httpCode == null &&
                    failure.kind == VodFailureKind.NETWORK)
                    SafeConnectionFailure.classify(error) else null
                diagnostics.problem(failure.kind.name + " / " + error.errorCodeName +
                    (httpCode?.let { " / HTTP $it" } ?: "") +
                    (transport?.takeIf { it.kind != ConnectionIssueKind.UNKNOWN }
                        ?.let { " / " + it.kind.name } ?: ""))
                val delay = retries.nextDelay(failure.canRetry)
                if (delay == null) {
                    playbackInfo?.text = failure.message +
                        " Bağlantın yetkili ve güncelse Yeniden dene kullanılabilir."
                } else {
                    playbackInfo?.text = failure.message + " " +
                        (delay/1000.0) + " sn sonra otomatik deneme " +
                        "(${retries.attempts}/2)."
                    scheduleRecovery(delay)
                }
            }
        })
        val media = MediaItem.Builder().setUri(uri)
        when (StreamFormatDetector.detect(uri.toString())) {
            StreamFormat.HLS -> media.setMimeType(MimeTypes.APPLICATION_M3U8)
            StreamFormat.DASH -> media.setMimeType(MimeTypes.APPLICATION_MPD)
            StreamFormat.AUTO -> {}
        }
        instance.setMediaItem(media.build())
        if (isOfflineTest) instance.repeatMode = Player.REPEAT_MODE_ONE
        player = instance
        videoView?.player = instance
        instance.prepare()
        if (isVod) {
            val saved = VodProgressStore.position(this, channelId)
            if (saved > 0L) {
                instance.seekTo(saved)
                playbackInfo?.text = "Kaldığın yerden devam ediliyor: " +
                    (saved/60_000L) + ". dakika"
            }
        }
        instance.playWhenReady = true
    }

    @OptIn(UnstableApi::class)
    private fun findHttpStatus(error: PlaybackException): Int? {
        var cause: Throwable? = error
        repeat(8) {
            val current = cause ?: return null
            if (current is HttpDataSource.InvalidResponseCodeException)
                return current.responseCode
            cause = current.cause
        }
        return null
    }

    private fun cancelRecovery() {
        scheduledRetry?.let { retryHandler.removeCallbacks(it) }
        scheduledRetry = null
        sessionGeneration++
    }

    private fun scheduleRecovery(delayMs: Long) {
        val generation = sessionGeneration
        val task = Runnable {
            scheduledRetry = null
            if (!sessionActive || isFinishing || isDestroyed ||
                generation != sessionGeneration) return@Runnable
            diagnostics.retry()
            stallGuard.restart(SystemClock.elapsedRealtime())
            playbackInfo?.text = "Yayın sunucusuna yeniden bağlanılıyor…"
            player?.apply { prepare(); playWhenReady = true }
        }
        scheduledRetry?.let { retryHandler.removeCallbacks(it) }
        scheduledRetry = task
        retryHandler.postDelayed(task, delayMs)
    }

    /**
     * Inspired by receiver-style connection checks, not by DiPlay source code.
     * No request occurs until the user taps the button.
     */
    private fun checkSourceOnDemand() {
        if (sourceProbeInProgress) return
        val uri = streamUri ?: return
        if (isOfflineTest || uri.scheme == "content") {
            lastSourceProbe = "Yerel belge: ağ kontrolü uygulanamaz."
            playbackInfo?.text = lastSourceProbe
            return
        }
        val generation = ++sourceProbeGeneration
        sourceProbeInProgress = true
        playbackInfo?.text = "İnternet ve yayın sunucusu kontrol ediliyor…"
        val network = currentNetworkStatus()
        sourceInspector.execute {
            val source = SourceHealthProbe.check(uri.toString())
            runOnUiThread {
                if (generation != sourceProbeGeneration || isFinishing || isDestroyed)
                    return@runOnUiThread
                sourceProbeInProgress = false
                lastSourceProbe = network + " • " +
                    (source.status?.let { "HTTP " + it + " • " } ?: "") +
                    source.description
                playbackInfo?.text = lastSourceProbe
            }
        }
    }

    private fun currentNetworkStatus(): String {
        return try {
            val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = manager.activeNetwork ?: return "Telefon çevrimdışı görünüyor"
            val caps = manager.getNetworkCapabilities(active)
                ?: return "İnternet bağlantısı doğrulanamadı"
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobil veri"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Ağ bağlantısı"
            }
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                transport + " • internet doğrulandı"
            else transport + " • internet erişimi doğrulanamadı"
        } catch (_: Exception) {
            "Ağ durumu okunamadı"
        }
    }

    private fun shareDiagnostics() {
        // No URL, signed query token, title, filename, credentials or headers.
        val report = buildString {
            appendLine("EV Cockpit • Film oynatıcı tanı raporu")
            appendLine("Sürüm: " + runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "Bilinmiyor"
            }.getOrDefault("Bilinmiyor") + " Beta")
            appendLine("Kullanıcının başlattığı kaynak kontrolü: " + lastSourceProbe)
            appendLine("Android API seviyesi: " + android.os.Build.VERSION.SDK_INT)
            appendLine("Kaynak tipi: " +
                if (isOfflineTest) "Çevrimdışı paket içi H.264 test videosu"
                else if (streamUri?.scheme == "content") "Yerel belge" else "HTTPS")
            appendLine(diagnostics.report(SystemClock.elapsedRealtime()))
            appendLine("Rapor yalnızca kullanıcı paylaşmayı seçtiğinde oluşturulur.")
        }
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, report)
            }, "Özel bağlantı içermeyen tanı raporunu paylaş"))
        } catch (_: ActivityNotFoundException) {
            playbackInfo?.text = "Paylaşım uygulaması bulunamadı."
        }
    }

    override fun onStop() {
        sessionActive = false
        sourceProbeGeneration++
        sourceProbeInProgress = false
        cancelRecovery()
        retryHandler.removeCallbacks(stallMonitor)
        stallGuard.ready()
        diagnostics.ready(SystemClock.elapsedRealtime())
        if (isVod && !ended) player?.let {
            VodProgressStore.save(this, channelId, it.currentPosition, it.duration)
        }
        videoView?.player = null
        player?.release()
        player = null
        super.onStop()
    }

    override fun onDestroy() {
        sourceInspector.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "source_url"
        const val EXTRA_TITLE = "source_title"
        const val EXTRA_ID = "source_channel_id"
        const val EXTRA_IS_VOD = "source_is_vod"
        const val EXTRA_OFFLINE_TEST = "evcockpit_offline_playback_selftest"
        // Bundled 96x54 H.264/MP4, generated locally with FFmpeg testsrc.
        // No network, credentials, 3rd-party film or protected stream.
        private const val OFFLINE_TEST_URI = "asset:///evcockpit_selftest.mp4"
    }
}
