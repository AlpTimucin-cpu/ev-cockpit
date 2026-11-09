package com.evcockpit.mediahub

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Radio experience on the phone; stations are shared with the Android Auto audio library. */
class RadioDashboard(
    private val activity: Activity,
    private val stations: () -> List<Station>,
    private val play: (Station) -> Unit,
    private val pauseOrPlay: () -> Unit,
    private val skip: (Int) -> Unit,
    private val sync: () -> Unit,
    private val favorite: (String) -> Unit
) {
    private var loaded = false
    private var page = 0
    private var selected = "Tümü"
    private var current: Station? = null
    private lateinit var cover: FrameLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var playButton: TextView
    private lateinit var syncStatus: TextView
    private lateinit var countLabel: TextView
    private lateinit var categories: LinearLayout
    private lateinit var cards: LinearLayout
    private lateinit var search: EditText
    private val dark = 0xff172941.toInt()
    private val accent = 0xff71eee1.toInt()
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 15) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun gradient(a: Int,b: Int) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(a,b)).apply {
            cornerRadius=dp(20).toFloat()
        }
    private fun label(value: String,size: Float,bold: Boolean = false): TextView =
        TextView(activity).apply {
            text=value; textSize=size
            setTextColor(if(bold)Color.WHITE else 0xffaec2d5.toInt())
            if(bold)setTypeface(Typeface.DEFAULT,Typeface.BOLD)
            setPadding(dp(5),dp(6),dp(5),dp(6))
        }
    private fun control(value:String,handler:()->Unit): TextView =
        label(value,14f,true).apply {
            gravity=Gravity.CENTER
            background=shape(0xff295273.toInt(),12)
            setOnClickListener{handler()}
        }

    fun view(): View {
        val root=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
        root.addView(label("RADYO STÜDYOSU",23f,true))
        root.addView(label("Türkiye · İnternet yayınları · Favoriler",12f))
        val hero=LinearLayout(activity).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(17),dp(14),dp(17),dp(15))
            background=gradient(0xff185d6a.toInt(),0xff192a55.toInt())
        }
        hero.addView(label("●  ŞİMDİ ÇALIYOR",12f,true).apply{setTextColor(accent)})
        val row=LinearLayout(activity).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        cover=FrameLayout(activity).apply{background=shape(0xff2c6176.toInt(),15)}
        row.addView(cover,LinearLayout.LayoutParams(dp(90),dp(90)))
        val info=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(12),0,0,0)
        }
        title=label("Radyo seç",21f,true).apply{maxLines=2}
        subtitle=label("Aşağıdan istasyon seç",13f)
        info.addView(title);info.addView(subtitle)
        row.addView(info,LinearLayout.LayoutParams(0,dp(100),1f))
        hero.addView(row)
        val buttons=LinearLayout(activity).apply{
            orientation=LinearLayout.HORIZONTAL
            setPadding(0,dp(12),0,0)
        }
        buttons.addView(control("◀◀"){skip(-1)},LinearLayout.LayoutParams(0,dp(49),1f))
        playButton=control("▶ OYNAT"){pauseOrPlay()}
        buttons.addView(playButton,LinearLayout.LayoutParams(0,dp(49),2.5f))
        buttons.addView(control("▶▶"){skip(1)},LinearLayout.LayoutParams(0,dp(49),1f))
        hero.addView(buttons)
        val heroLp=LinearLayout.LayoutParams(-1,-2)
        heroLp.setMargins(0,dp(10),0,dp(15))
        root.addView(hero,heroLp)
        val header=LinearLayout(activity).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        header.addView(label("İSTASYONLAR",19f,true),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(control("⟳ GÜNCELLE"){sync()},LinearLayout.LayoutParams(-2,dp(43)))
        root.addView(header)
        syncStatus=label("Katalog güncellemesi kontrol ediliyor…",12f)
        root.addView(syncStatus)
        search=EditText(activity).apply {
            hint="İstasyon veya müzik türü ara"
            setSingleLine(true)
            textSize=15f
            setTextColor(Color.WHITE)
            setHintTextColor(0xff9cb1c4.toInt())
            background=shape(dark)
            setPadding(dp(14),dp(8),dp(14),dp(8))
        }
        root.addView(search,LinearLayout.LayoutParams(-1,dp(51)))
        search.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){
                page=0;drawStations()
            }
            override fun afterTextChanged(s:Editable?){}
        })
        val scroller=HorizontalScrollView(activity).apply{isHorizontalScrollBarEnabled=false}
        categories=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
        scroller.addView(categories)
        root.addView(scroller,LinearLayout.LayoutParams(-1,dp(53)))
        countLabel=label("",12f)
        root.addView(countLabel)
        cards=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
        root.addView(cards)
        loaded=true
        setPlaying(null,false)
        refresh()
        return root
    }
    fun message(value: String) {
        if(::syncStatus.isInitialized) syncStatus.text=value
    }
    fun setPlaying(station: Station?,active:Boolean) {
        current=station
        if(!loaded)return
        title.text=station?.name ?: "Radyo seç"
        subtitle.text=station?.group ?: "Yayın seçimi bekleniyor"
        playButton.text=if(active)"Ⅱ DURAKLAT" else "▶ OYNAT"
        drawLogo(cover,station?.logo.orEmpty(),station?.name ?: "♫",27f,true)
    }
    fun refresh() {
        if(!loaded)return
        val genres=stations().map{it.group}.distinct().sorted()
        if(selected !in genres && selected != "Tümü" && selected != "★ Favoriler")selected="Tümü"
        categories.removeAllViews()
        (listOf("Tümü","★ Favoriler")+genres).forEach{category->
            val active=category==selected
            val chip=control(category){selected=category;page=0;refresh()}.apply{
                textSize=12f
                setTextColor(if(active)0xff123242.toInt()else Color.WHITE)
                background=shape(if(active)accent else 0xff29435c.toInt())
                setPadding(dp(15),dp(4),dp(15),dp(4))
            }
            val lp=LinearLayout.LayoutParams(-2,dp(40)).apply{
                setMargins(0,dp(6),dp(7),0)
            }
            categories.addView(chip,lp)
        }
        drawStations()
    }
    private fun drawStations(){
        if(!loaded)return
        val all=stations()
        val q=search.text.toString().trim()
        val filtered=all.filter {
            (selected=="Tümü" || selected=="★ Favoriler"&&it.favorite || it.group==selected) &&
            (q.isBlank() || it.name.contains(q,true) || it.group.contains(q,true))
        }
        val maxPage=((filtered.size-1).coerceAtLeast(0))/24
        page=page.coerceIn(0,maxPage)
        countLabel.text=filtered.size.toString()+" radyo · "+all.count{it.favorite}.toString()+
            " favori · "+(page+1).toString()+"/"+(maxPage+1).toString()
        cards.removeAllViews()
        if(filtered.isEmpty()){
            cards.addView(label("Bu kategoride kanal bulunamadı. Kataloğu güncelleyebilir veya filtreyi değiştirebilirsin.",14f))
            return
        }
        filtered.drop(page*24).take(24).chunked(2).forEach{chunk->
            val row=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
            chunk.forEach{station->
                val lp=LinearLayout.LayoutParams(0,dp(142),1f).apply{
                    setMargins(dp(2),dp(3),dp(5),dp(4))
                }
                row.addView(stationTile(station),lp)
            }
            if(chunk.size==1)row.addView(View(activity),LinearLayout.LayoutParams(0,dp(2),1f))
            cards.addView(row)
        }
        if(maxPage>0){
            val pager=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
            pager.addView(control("‹ Önceki"){page=(page-1).coerceAtLeast(0);drawStations()},
                LinearLayout.LayoutParams(0,dp(45),1f))
            pager.addView(label((page+1).toString()+"/"+(maxPage+1).toString(),14f).apply{
                gravity=Gravity.CENTER
            },LinearLayout.LayoutParams(0,dp(45),0.6f))
            pager.addView(control("Sonraki ›"){page=(page+1).coerceAtMost(maxPage);drawStations()},
                LinearLayout.LayoutParams(0,dp(45),1f))
            cards.addView(pager)
        }
    }
    private fun stationTile(station: Station):View{
        val card=LinearLayout(activity).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(10),dp(8),dp(10),dp(8))
            background=shape(if(current?.id==station.id)0xff27566a.toInt() else dark)
            setOnClickListener{play(station)}
        }
        val row=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
        val art=FrameLayout(activity).apply{background=shape(0xff245977.toInt(),11)}
        drawLogo(art,station.logo,station.name,16f,false)
        row.addView(art,LinearLayout.LayoutParams(dp(54),dp(54)))
        row.addView(label(if(station.favorite)"★"else"☆",24f,true).apply{
            gravity=Gravity.RIGHT
            setTextColor(if(station.favorite)0xffffcd6f.toInt()else 0xffaec2d5.toInt())
            setOnClickListener{favorite(station.id)}
        },LinearLayout.LayoutParams(0,dp(53),1f))
        card.addView(row)
        card.addView(label(station.name,14f,true).apply{
            maxLines=1
            ellipsize=android.text.TextUtils.TruncateAt.END
        })
        card.addView(label(station.group,11f).apply{
            maxLines=1
            ellipsize=android.text.TextUtils.TruncateAt.END
        })
        return card
    }
    private fun drawLogo(host:FrameLayout,url:String,name:String,fontSize:Float,reset:Boolean){
        if(reset)host.removeAllViews()
        val monogram=name.split(' ').filter{it.isNotBlank()}.take(2).joinToString(""){it.take(1)}
            .uppercase().ifBlank{"♫"}.take(2)
        host.addView(label(monogram,fontSize,true).apply{
            gravity=Gravity.CENTER
            setTextColor(accent)
        },FrameLayout.LayoutParams(-1,-1))
        if(url.isNotBlank()){
            val image=ImageView(activity).apply{
                scaleType=ImageView.ScaleType.FIT_CENTER
                alpha=0f
                setPadding(dp(4),dp(4),dp(4),dp(4))
            }
            host.addView(image,FrameLayout.LayoutParams(-1,-1))
            ChannelLogoLoader.into(url,image,activity)
        }
    }
}
