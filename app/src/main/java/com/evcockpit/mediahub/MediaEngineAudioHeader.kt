package com.evcockpit.mediahub

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View

/**
 * Persistent compact media controls based on the supplied MediaEngine navigation flow.
 * Actual status comes from our Media3 session; never invent AA connected state.
 */
class MediaEngineAudioHeader(
    private val activity:Activity,
    private val onPrevious:()->Unit,
    private val onToggle:()->Unit,
    private val onNext:()->Unit,
    private val onConnection:()->Unit
){
    val status:TextView
    private val title:TextView
    private val subtitle:TextView
    private val play:TextView
    val view:LinearLayout
    private fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun txt(t:String,n:Float,c:Int=Color.WHITE,b:Boolean=false)=TextView(activity).apply{
        text=t;textSize=n;setTextColor(c)
        if(b)setTypeface(typeface,Typeface.BOLD)
        gravity=Gravity.CENTER_VERTICAL
        maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
    }
    init{
        view=LinearLayout(activity).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(0xff0c1a1b.toInt())
            setPadding(dp(12),dp(3),dp(12),dp(7))
        }
        val statusRow=LinearLayout(activity).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        status=txt("● AA bağlantısı doğrulanmadı",12f,0xffd7a1a1.toInt()).apply{
            isClickable=true
            setOnClickListener{onConnection()}
        }
        statusRow.addView(status,LinearLayout.LayoutParams(0,dp(37),1f))
        statusRow.addView(txt("Araç durumu  ›",12f,0xffb9d4d3.toInt()).apply{
            isClickable=true;setOnClickListener{onConnection()}
        })
        view.addView(statusRow)
        val track=LinearLayout(activity).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        }
        val note=txt("♫",35f,0xff72d4c2.toInt(),true).apply{
            gravity=Gravity.CENTER
            setBackgroundColor(0xff1e3638.toInt())
        }
        track.addView(note,LinearLayout.LayoutParams(dp(63),dp(65)))
        val labels=LinearLayout(activity).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(13),0,0,0)
            gravity=Gravity.CENTER_VERTICAL
        }
        title=txt("Oynatılan içerik yok",19f,Color.WHITE,true)
        subtitle=txt("Dinlemek için radyo seç",12f,0xffa5b8bb.toInt())
        labels.addView(title);labels.addView(subtitle)
        track.addView(labels,LinearLayout.LayoutParams(0,dp(65),1f))
        view.addView(track)
        view.addView(View(activity).apply{setBackgroundColor(0xff315154.toInt())},
            LinearLayout.LayoutParams(-1,dp(2)).apply{
                topMargin=dp(7);bottomMargin=dp(9)
            })
        val actions=LinearLayout(activity).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER
        }
        actions.addView(txt("◀◀",20f,0xffaec1c3.toInt()).apply{
            gravity=Gravity.CENTER;isClickable=true
            setOnClickListener{onPrevious()}
        },LinearLayout.LayoutParams(0,dp(50),1f))
        play=txt("▶",23f,0xff143536.toInt(),true).apply{
            gravity=Gravity.CENTER
            background=GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xff83e8cf.toInt(),0xff53baa9.toInt())
            ).apply{cornerRadius=dp(39).toFloat()}
            isClickable=true;isFocusable=true
            setOnClickListener{onToggle()}
        }
        actions.addView(play,LinearLayout.LayoutParams(dp(64),dp(57)))
        actions.addView(txt("▶▶",20f,0xffaec1c3.toInt()).apply{
            gravity=Gravity.CENTER;isClickable=true
            setOnClickListener{onNext()}
        },LinearLayout.LayoutParams(0,dp(50),1f))
        view.addView(actions)
    }
    fun render(name:String?,isPlaying:Boolean) {
        title.text=name?.takeIf{it.isNotBlank()} ?: "Oynatılan içerik yok"
        subtitle.text=when {
            name.isNullOrBlank()->"Kontrol etmek için bir radyo seç"
            isPlaying->"● Şu anda oynatılıyor"
            else->"Ⅱ Duraklatıldı"
        }
        play.text=if(isPlaying)"Ⅱ" else "▶"
    }
}
