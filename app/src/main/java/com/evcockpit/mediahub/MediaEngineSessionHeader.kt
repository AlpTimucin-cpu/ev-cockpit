package com.evcockpit.mediahub

import android.app.Activity
import android.content.ComponentName
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * Persistent MediaEngine-style radio controls on each in-app media page.
 * Uses our real Media3 service. Does NOT invent an AA connection or project video.
 */
class MediaEngineSessionHeader(private val activity: Activity) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val header=MediaEngineAudioHeader(
        activity,
        {changeTrack(false)},
        {toggle()},
        {changeTrack(true)},
        {CarCompatibility.show(activity)}
    )
    val view get() = header.view

    init {
        header.status.text="● Android Auto bağlantısı doğrulanmadı"
        val token=SessionToken(activity,ComponentName(activity,AudioLibraryService::class.java))
        future=MediaController.Builder(activity,token).buildAsync().also{f->
            f.addListener({
                try {
                    val c=f.get()
                    if(activity.isFinishing || activity.isDestroyed) return@addListener
                    controller=c
                    c.addListener(object:Player.Listener {
                        override fun onIsPlayingChanged(isPlaying:Boolean){render()}
                        override fun onMediaItemTransition(item: MediaItem?,reason:Int){render()}
                        override fun onPlaybackStateChanged(state:Int){render()}
                    })
                    render()
                }catch(_:Exception){
                    header.render(null,false)
                }
            },activity.mainExecutor)
        }
    }

    private fun render(){
        val item=controller?.currentMediaItem
        header.render(item?.mediaMetadata?.title?.toString(),controller?.isPlaying==true)
    }
    private fun toggle(){
        val c=controller
        if(c==null || c.currentMediaItem==null) {
            Toast.makeText(activity,"Önce radyo seç.",Toast.LENGTH_SHORT).show()
            return
        }
        if(c.isPlaying)c.pause() else c.play()
        render()
    }
    private fun changeTrack(next:Boolean){
        val c=controller
        if(c==null || c.currentMediaItem==null){
            Toast.makeText(activity,"Önce radyo seç.",Toast.LENGTH_SHORT).show()
            return
        }
        val available=if(next)c.hasNextMediaItem() else c.hasPreviousMediaItem()
        if(!available){
            Toast.makeText(activity,"Sıradaki radyo için ana ekranı kullan.",Toast.LENGTH_SHORT).show()
            return
        }
        if(next)c.seekToNextMediaItem() else c.seekToPreviousMediaItem()
    }
    fun release(){
        future?.let { MediaController.releaseFuture(it) }
        future=null;controller=null
    }
}
