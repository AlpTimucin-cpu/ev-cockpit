package com.evcockpit.mediahub

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.HorizontalScrollView

/**
 * Original, responsive phone dashboard modeled on the user-supplied 69-second
 * MediaEngine UX video. No MediaEngine source, art, APIs or protected streaming content.
 */
object MediaEngineHomeDashboard {
    private const val BG=0xff0b1719.toInt()
    private const val MINT=0xff70d8cb.toInt()
    private const val MUTED=0xff9daeb2.toInt()
    private fun dp(a:Activity,v:Int)=(v*a.resources.displayMetrics.density).toInt()
    private fun gradient(a:Activity,c:Int,d:Int,r:Int=18,border:Int=0)=
        GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(c,d)).apply{
            cornerRadius=dp(a,r).toFloat()
            if(border!=0)setStroke(dp(a,1),border)
        }
    private fun text(a:Activity,s:String,size:Float=14f,c:Int=Color.WHITE,
                     bold:Boolean=false)=TextView(a).apply{
        text=s;textSize=size;setTextColor(c)
        if(bold)setTypeface(typeface,Typeface.BOLD)
        includeFontPadding=true
    }
    private fun vspace(a:Activity,n:Int)=View(a).apply{
        layoutParams=LinearLayout.LayoutParams(1,dp(a,n))
    }
    private fun vertical(a:Activity)=LinearLayout(a).apply{
        orientation=LinearLayout.VERTICAL
    }
    private fun horizontal(a:Activity)=LinearLayout(a).apply{
        orientation=LinearLayout.HORIZONTAL
        gravity=Gravity.CENTER_VERTICAL
    }
    private fun flatCard(a:Activity,title:String,desc:String,symbol:String,
                         color:Int,onClick:()->Unit):LinearLayout {
        val row=horizontal(a).apply{
            setPadding(dp(a,13),dp(a,14),dp(a,13),dp(a,14))
            background=gradient(a,0xff1d3338.toInt(),0xff101d25.toInt(),17,
                0xff42565c.toInt())
            isClickable=true;isFocusable=true
            contentDescription=title+", "+desc
            setOnClickListener{onClick()}
        }
        row.addView(text(a,symbol,29f,color,true),
            LinearLayout.LayoutParams(dp(a,48),dp(a,52)))
        val labels=vertical(a)
        labels.addView(text(a,title,16f,Color.WHITE,true))
        labels.addView(text(a,desc,12f,MUTED))
        row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(text(a,"›",29f,color,true),
            LinearLayout.LayoutParams(dp(a,24),-2))
        return row
    }
    private fun section(a:Activity,title:String,subtitle:String):LinearLayout=
        vertical(a).apply{
            addView(text(a,title,16f,Color.WHITE,true))
            addView(text(a,subtitle,12f,MUTED))
        }

    /** Locally drawn TV promo and real action, no borrowed bitmap. */
    private fun tvCard(a:Activity,channelCount:Int,onClick:()->Unit):LinearLayout {
        val card=vertical(a).apply {
            background=gradient(a,0xff0d3162.toInt(),0xff091a2c.toInt(),20,
                0xff3273a5.toInt())
            clipToOutline=true
            isClickable=true;isFocusable=true
            contentDescription="TV, Canlı TV ve IPTV; "+channelCount+" kayıtlı kanal"
            setOnClickListener{onClick()}
        }
        val artwork=horizontal(a).apply{
            background=gradient(a,0xff073873.toInt(),0xff121f45.toInt(),0)
            setPadding(dp(a,18),dp(a,10),dp(a,16),dp(a,9))
        }
        val left=vertical(a).apply{gravity=Gravity.CENTER_VERTICAL}
        left.addView(text(a,"● LIVE",15f,0xffff5361.toInt(),true))
        left.addView(vspace(a,9))
        left.addView(text(a,"▣",60f,0xff44b7ff.toInt(),true))
        artwork.addView(left,LinearLayout.LayoutParams(0,-1,1f))
        val monitors=vertical(a).apply{gravity=Gravity.CENTER_VERTICAL}
        repeat(3){i->
            val row=horizontal(a)
            repeat(4){j->
                val screen=TextView(a).apply{
                    text=if((i+j)%4==0)"▶" else "▣"
                    gravity=Gravity.CENTER
                    textSize=11f;setTextColor(0xffd3e8fa.toInt())
                    background=gradient(a,
                        if((i+j)%2==0)0xff245eab.toInt() else 0xff174475.toInt(),
                        0xff101d38.toInt(),3)
                }
                row.addView(screen,LinearLayout.LayoutParams(dp(a,27),dp(a,26)).apply{
                    rightMargin=dp(a,2);bottomMargin=dp(a,3)
                })
            }
            monitors.addView(row)
        }
        artwork.addView(monitors)
        card.addView(artwork,LinearLayout.LayoutParams(-1,dp(a,135)))
        val bar=horizontal(a).apply{setPadding(dp(a,14),dp(a,10),dp(a,14),dp(a,11))}
        val words=vertical(a)
        words.addView(text(a,"TV",24f,Color.WHITE,true))
        words.addView(text(a,"Canlı TV ve IPTV",12f,MUTED))
        bar.addView(words,LinearLayout.LayoutParams(0,-2,1f))
        bar.addView(text(a,"›",32f,Color.WHITE,true).apply {
            gravity=Gravity.CENTER
            background=gradient(a,0xff226a93.toInt(),0xff163e63.toInt(),40)
        },LinearLayout.LayoutParams(dp(a,51),dp(a,51)))
        card.addView(bar)
        return card
    }

    fun render(
        activity:Activity,
        overview:CockpitOverview,
        epg:EpgScheduleIndex,
        actions:CockpitActions,
        shortcuts:List<UserQuickLink>,
        onOpenShortcut:(String)->Unit,
        onAddShortcut:()->Unit,
        onRemoveShortcut:(String)->Unit
    ):LinearLayout {
        val root=vertical(activity).apply {
            setPadding(0,dp(activity,4),0,dp(activity,20))
            setBackgroundColor(BG)
        }
        val bar=horizontal(activity).apply{
            setPadding(dp(activity,7),dp(activity,3),dp(activity,4),dp(activity,11))
        }
        bar.addView(text(activity,"◧",26f,MINT,true))
        bar.addView(text(activity,"  EV Cockpit",19f,Color.WHITE,true),
            LinearLayout.LayoutParams(0,-2,1f))
        bar.addView(text(activity,"⌂",25f,MINT,true).apply{
            gravity=Gravity.CENTER
            isClickable=true;isFocusable=true
            contentDescription="Ana sayfa"
        },LinearLayout.LayoutParams(dp(activity,43),dp(activity,43)))
        root.addView(bar)
        root.addView(tvCard(activity,overview.tvCount,actions.onTv))
        root.addView(vspace(activity,12))
        root.addView(StreamingServiceCards.youtube(activity,actions.onYoutube))
        root.addView(vspace(activity,11))
        root.addView(section(activity,"FİLM · DİZİ · MAÇ",
            "Açık filmler ve resmî diziler; kendi M3U kitaplığını da ekle"))
        root.addView(vspace(activity,8))
        root.addView(flatCard(activity,"▣ Filmler",
            "Açık filmler ve kişisel film kitaplığım","▶",
            0xffde769d.toInt(),actions.onMovies))
        root.addView(vspace(activity,7))
        root.addView(flatCard(activity,"▤ Diziler",
            "Resmî diziler, bölüm ve kişisel kütüphane","▤",
            0xffb7a1f4.toInt(),actions.onSeries))
        root.addView(vspace(activity,7))
        root.addView(flatCard(activity,"⚽ Maçlar",
            "Resmî spor yayınları ve erişim hakkın olan kaynaklar","⚽",
            0xff65d4bd.toInt(),actions.onSports))
        root.addView(vspace(activity,7))
        root.addView(flatCard(activity,"SportzX",
            "Uygulama içi web sayfası ve SportzX uygulamasına geçiş","◉",
            0xff72d8ab.toInt()) {
            activity.startActivity(android.content.Intent(activity,
                SportzXHubActivity::class.java))
        })
        root.addView(vspace(activity,12))
        root.addView(flatCard(activity,"Ekran Yansıtma",
            "Bağlantı ve park halinde ekran seçenekleri","▣",
            0xffc287fa.toInt(),actions.onCompanions))
        root.addView(vspace(activity,9))
        root.addView(flatCard(activity,"Radyo","İnternet radyoları ve kategoriler","▤",
            MINT,actions.onRadio))
        root.addView(vspace(activity,9))
        root.addView(flatCard(activity,"Podcastlar","RSS ve podcast bölümleri","◉",
            0xffbd82f3.toInt(),actions.onPodcast))
        root.addView(vspace(activity,9))
        root.addView(section(activity,"YAYIN PLATFORMLARI",
            "Kurulu resmî Android uygulamalarını aç"))
        root.addView(vspace(activity,8))
        root.addView(StreamingServiceCards.subscriptions(activity,
            actions.onNetflix,actions.onPrimeVideo))
        root.addView(vspace(activity,14))

        root.addView(section(activity,"KISAYOLLAR",
            "Kendi güvenli web bağlantılarını ekleyebilirsin"))
        root.addView(vspace(activity,8))
        shortcuts.forEach{link->
            val item=flatCard(activity,link.title,"Kaydedilen HTTPS adresi","◎",
                MINT,{onOpenShortcut(link.url)})
            item.setOnLongClickListener{onRemoveShortcut(link.url);true}
            root.addView(item,LinearLayout.LayoutParams(-1,-2).apply{
                bottomMargin=dp(activity,7)
            })
        }
        root.addView(flatCard(activity,"＋ Kısayol ekle",
            "Kendi web siteni kaydet","＋",MINT,onAddShortcut))
        root.addView(vspace(activity,13))

        if(overview.favourites.isNotEmpty()) {
            root.addView(section(activity,"FAVORİ KANALLAR","Yıldızladığın kanallar"))
            root.addView(vspace(activity,8))
            root.addView(mediaRail(activity,overview.favourites,epg,actions))
            root.addView(vspace(activity,14))
        }
        if(overview.recents.isNotEmpty()) {
            root.addView(section(activity,"SON İÇERİKLER","Son açtığın yayınlar"))
            root.addView(vspace(activity,8))
            root.addView(mediaRail(activity,overview.recents,epg,actions))
            root.addView(vspace(activity,12))
        }
        root.addView(flatCard(activity,"Kaynaklar ve ayarlar",
            "M3U listeleri, XMLTV, araç durumu","⚙",MINT,actions.onLibrary))
        root.addView(vspace(activity,10))
        root.addView(text(activity,
            "Android Auto'da sürücünün ön ekranı için yalnızca desteklenen ses/radyo arayüzü. "+
            "Video oynatma telefon ve izinli park ekranları içindir.",11f,MUTED))
        return root
    }

    private fun mediaRail(activity:Activity,items:List<CockpitEntry>,
                          epg:EpgScheduleIndex,actions:CockpitActions):
        HorizontalScrollView=HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled=false
        val list=horizontal(activity).apply{gravity=Gravity.TOP}
        items.take(12).forEach{item->
            val block=vertical(activity).apply{
                setPadding(dp(activity,12),dp(activity,11),dp(activity,12),dp(activity,11))
                background=gradient(activity,0xff234b50.toInt(),0xff13292f.toInt(),16)
                isClickable=true;isFocusable=true
                contentDescription=item.title+" oynat"
                setOnClickListener{actions.onPlay(item)}
            }
            val logo=ImageView(activity).apply{
                setImageResource(android.R.drawable.ic_media_play)
                scaleType=ImageView.ScaleType.FIT_CENTER
            }
            if(item.logo.isNotBlank())ChannelLogoLoader.into(item.logo,logo,activity)
            block.addView(logo,LinearLayout.LayoutParams(dp(activity,55),dp(activity,55)))
            block.addView(vspace(activity,5))
            block.addView(text(activity,item.title.take(18),13f,Color.WHITE,true).apply{
                maxLines=2
            })
            block.addView(text(activity,if(item.kind=="radio")"Radyo" else "TV",
                11f,MINT))
            list.addView(block,LinearLayout.LayoutParams(dp(activity,147),dp(activity,127)).apply{
                rightMargin=dp(activity,8)
            })
        }
        addView(list,ViewGroup.LayoutParams(-2,-2))
    }
}
