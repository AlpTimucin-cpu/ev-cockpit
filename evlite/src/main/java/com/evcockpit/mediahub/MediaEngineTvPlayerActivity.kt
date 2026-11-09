package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView

/**
 * Optional Android platform TV playback: no Media3/LibVLC dependencies.
 * Some HLS streams/codecs will not be supported by the OS player; users can
 * open an independently installed MediaEngine app instead.
 *
 * Never auto-plays in the background or on Android Auto. Intended for phone
 * use while parked only.
 */
class MediaEngineTvPlayerActivity:Activity() {
    private var video:VideoView?=null
    private var info:TextView?=null
    private val bg=0xff081923.toInt()

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=bg
        window.navigationBarColor=bg
        val address=intent.getStringExtra(EXTRA_URL)
        val title=intent.getStringExtra(EXTRA_NAME).orEmpty().take(100)
        if(address==null || !address.startsWith("https://",ignoreCase=true)) {
            AlertDialog.Builder(this)
                .setTitle("Güvenli yayın bulunamadı")
                .setMessage("Bu oynatıcı yalnızca seçtiğin HTTPS yayınını açar.")
                .setPositiveButton("Kapat"){_,_->finish()}.show()
            return
        }
        val container=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(0,24,0,18)
        }
        setContentView(container)
        container.addView(Button(this).apply{
            text="‹ MediaEngine TV • "+title
            isAllCaps=false
            setOnClickListener{finish()}
        },LinearLayout.LayoutParams(-1,60))
        val frame=FrameLayout(this).apply{setBackgroundColor(Color.BLACK)}
        container.addView(frame,LinearLayout.LayoutParams(-1,0,1f))
        val player=VideoView(this)
        video=player
        frame.addView(player,FrameLayout.LayoutParams(-1,-1))
        val controller=MediaController(this).apply {
            setAnchorView(player)
        }
        player.setMediaController(controller)
        val status=TextView(this).apply{
            text="TV yayını bağlanıyor…"
            textSize=13f
            setTextColor(0xffa5e4d6.toInt())
            gravity=Gravity.CENTER
            setPadding(8,12,8,12)
        }
        info=status
        container.addView(status)
        container.addView(TextView(this).apply{
            text="Yalnızca park hâlindeyken izle. " +
                "Bu hafif sistem oynatıcısı tüm IPTV kodlayıcılarını desteklemeyebilir."
            textSize=12f
            setTextColor(Color.WHITE)
            gravity=Gravity.CENTER
            setPadding(12,5,12,15)
        })
        player.setOnPreparedListener{
            status.text="Yayın hazır • Android sistem oynatıcısı"
            controller.show(3500)
        }
        player.setOnErrorListener{_,_,_->
            status.text="Bu TV yayını sistem oynatıcısı tarafından açılamadı. " +
                "MediaEngine'yı ayrı uygulama olarak deneyebilirsin."
            true
        }
        player.setOnCompletionListener{
            status.text="TV yayını sona erdi."
        }
        try{
            player.setVideoURI(android.net.Uri.parse(address))
            player.requestFocus()
            player.start()
        }catch(_:Exception){
            status.text="Yayın açılamadı. Bağlantıyı ve oynatıcı uyumluluğunu kontrol et."
        }
    }

    override fun onPause() {
        try{video?.stopPlayback()}catch(_:Exception){}
        super.onPause()
    }
    override fun onDestroy() {
        try{video?.stopPlayback()}catch(_:Exception){}
        video=null
        info=null
        super.onDestroy()
    }
    companion object {
        const val EXTRA_URL="com.evcockpit.evguide.tv.url"
        const val EXTRA_NAME="com.evcockpit.evguide.tv.name"
    }
}
