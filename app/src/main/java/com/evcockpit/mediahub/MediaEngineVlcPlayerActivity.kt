package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.content.Intent
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import android.net.Uri

/**
 * Native LibVLC player inside EV Cockpit Yol Arkadasim. Used only on the phone and
 * for the user's individually selected HTTPS stream. Never captures the
 * screen or transmits a catalogue to other applications.
 *
 * The 3.7.2 version is the one declared in MediaEngine's public Gradle catalogue.
 * Playback still depends on the source, device codecs and licensing.
 */
class MediaEngineVlcPlayerActivity : Activity() {
    private var vlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private lateinit var picture: VLCVideoLayout
    private lateinit var status: TextView
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val id = intent.getStringExtra(EXTRA_CHANNEL_ID).orEmpty()
        val channel = VideoStore.load(this).firstOrNull { it.id == id }
        if (channel == null || !M3uParser.isHttpsUrl(channel.url)) {
            AlertDialog.Builder(this)
                .setTitle("Video bulunamadı")
                .setMessage("Kayıtlı HTTPS video kaynağı bulunamadı.")
                .setPositiveButton("Kapat") { _, _ -> finish() }.show()
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
            }
            insets
        }
        setContentView(root)
        root.addView(TextView(this).apply {
            text = "‹  " + channel.name.take(85) + "  •  VLC"
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { finish() }
        })
        picture = VLCVideoLayout(this)
        root.addView(picture, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply {
            text = "VLC hazırlanıyor…"
            textSize = 13f
            setTextColor(0xffaac8d0.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(status)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(text: String, run: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { run() }
        }
        row.addView(action("⏯ Oynat/Duraklat") {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        row.addView(action("⟳ Yeniden bağlan") { player?.stop(); player?.play() },
            LinearLayout.LayoutParams(0, dp(56), 1f))
        root.addView(row)

        try {
            // Network playlist Lua scripts and unsafe local protocols are not needed.
            val instance = LibVLC(this, arrayListOf("--no-lua", "--network-caching=1500"))
            val p = MediaPlayer(instance)
            vlc = instance
            player = p
            p.attachViews(picture, null, false, false)
            p.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Playing -> update("● VLC oynatıyor")
                    MediaPlayer.Event.Buffering -> update("● VLC yükleniyor…")
                    MediaPlayer.Event.EncounteredError -> update(
                        "● VLC akışı oynatamadı. Ağ, codec veya sunucu erişimini kontrol et.")
                    MediaPlayer.Event.EndReached -> update("● Yayın bitti")
                }
            }
            val media = Media(instance, Uri.parse(channel.url))
            media.setHWDecoderEnabled(true, false)
            media.addOption(":network-caching=1500")
            p.media = media
            media.release()
            p.play()
            started = true
        } catch (_: Exception) {
            update("VLC başlatılamadı. Derleme ve telefon desteği kontrol edilmeli.")
        } catch (_: LinkageError) {
            update("VLC native kütüphanesi yüklenemedi.")
        }
    }

    private fun update(value: String) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed && ::status.isInitialized) status.text = value
        }
    }

    override fun onStop() {
        if (started) player?.pause()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (started && player?.isPlaying == false) player?.play()
    }

    override fun onDestroy() {
        try {
            player?.stop()
            player?.detachViews()
            player?.release()
            vlc?.release()
        } catch (_: Exception) { }
        player = null
        vlc = null
        super.onDestroy()
    }

    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()

    companion object {
        const val EXTRA_CHANNEL_ID = "com.evcockpit.mediahub.vlc.channel"
        fun open(activity: Activity, channel: VideoChannel?) {
            if (channel == null) {
                AlertDialog.Builder(activity).setTitle("Önce kanal seç")
                    .setMessage("VLC ile açılacak HTTPS kanalı seç.")
                    .setPositiveButton("Tamam", null).show()
                return
            }
            activity.startActivity(Intent(activity, MediaEngineVlcPlayerActivity::class.java)
                .putExtra(EXTRA_CHANNEL_ID, channel.id))
        }
    }
}
