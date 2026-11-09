package com.evcockpit.mediahub

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** MediaEngine-inspired in-app IPTV navigator for the user's own authorized playlists. */
class MediaEngineTvBrowseActivity : Activity() {
    private val bg=0xff0d191b.toInt()
    private val mint=0xff78dcd3.toInt()
    private val blue=0xff45b6f7.toInt()
    private val muted=0xffa5babd.toInt()
    private var group:String?=null
    private var favoritesOnly=false
    private var grid=false
    private lateinit var heading:TextView
    private lateinit var search:EditText
    private lateinit var content:LinearLayout
    private lateinit var filters:LinearLayout
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun shape(a:Int,b:Int,r:Int=15)=
        GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(a,b)).apply {
            cornerRadius=dp(r).toFloat()
            setStroke(dp(1),0xff35545b.toInt())
        }
    private fun label(v:String,size:Float=14f,color:Int=Color.WHITE,
                      bold:Boolean=false)=TextView(this).apply {
        text=v;textSize=size;setTextColor(color)
        if(bold)setTypeface(typeface,Typeface.BOLD)
        maxLines=2;gravity=Gravity.CENTER_VERTICAL
    }
    private fun click(v:String,onClick:()->Unit)=label(v,15f,Color.WHITE,true).apply {
        setPadding(dp(12),dp(7),dp(12),dp(7))
        isClickable=true;isFocusable=true
        background=shape(0xff263f47.toInt(),0xff192b30.toInt(),13)
        setOnClickListener{onClick()}
    }
    private lateinit var sessionHeader: MediaEngineSessionHeader
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=bg;window.navigationBarColor=bg
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(12),dp(10),dp(12),dp(10))
        }
        setContentView(root)
        sessionHeader=MediaEngineSessionHeader(this)
        root.addView(sessionHeader.view)
        if(android.os.Build.VERSION.SDK_INT>=30) {
            root.setOnApplyWindowInsetsListener{v,insets->
                val x=insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(dp(12),x.top+dp(8),dp(12),x.bottom+dp(9))
                insets
            }
        }
        val bar=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        bar.addView(click("‹"){back()},LinearLayout.LayoutParams(dp(53),dp(49)))
        heading=label("TV Kategorileri",18f,Color.WHITE,true)
        heading.setPadding(dp(9),0,0,0)
        bar.addView(heading,LinearLayout.LayoutParams(0,dp(51),1f))
        bar.addView(click("⌂"){finish()},LinearLayout.LayoutParams(dp(53),dp(49)))
        root.addView(bar)
        root.addView(View(this),LinearLayout.LayoutParams(1,dp(13)))
        search=EditText(this).apply {
            hint="Kanal veya kategori ara"
            setTextColor(Color.WHITE);setHintTextColor(muted)
            textSize=14f;setSingleLine(true)
            setPadding(dp(12),dp(5),dp(12),dp(5))
            background=shape(0xff20343a.toInt(),0xff15282e.toInt())
            addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,af:Int){}
                override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int){
                    if(::content.isInitialized)renderList()
                }
                override fun afterTextChanged(s:Editable?){}
            })
        }
        root.addView(search,LinearLayout.LayoutParams(-1,dp(52)))
        root.addView(View(this),LinearLayout.LayoutParams(1,dp(9)))
        filters=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
        root.addView(filters)
        root.addView(View(this),LinearLayout.LayoutParams(1,dp(9)))
        val scroll=ScrollView(this).apply{isFillViewport=true}
        content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        scroll.addView(content)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val footer=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        footer.addView(click("＋ Kaynaklar"){openMainLibrary()},
            LinearLayout.LayoutParams(0,dp(49),1f))
        footer.addView(click("⟳ Yenile"){renderList()},
            LinearLayout.LayoutParams(0,dp(49),.6f).apply{leftMargin=dp(8)})
        root.addView(footer)
        renderList()
    }
    override fun onResume(){super.onResume();if(::content.isInitialized)renderList()}
    private fun renderList(){
        val all=VideoStore.load(this)
        val query=search.text.toString().trim()
        heading.text=group ?: "TV Kategorileri"
        filters.removeAllViews()
        filters.addView(click(if(group==null)"Kategoriler" else "‹ Gruplar"){
            group=null;search.setText("");renderList()
        },LinearLayout.LayoutParams(0,dp(43),1.2f))
        filters.addView(click(if(favoritesOnly)"★ Favoriler" else "☆ Favoriler"){
            favoritesOnly=!favoritesOnly;renderList()
        },LinearLayout.LayoutParams(0,dp(43),1f).apply{leftMargin=dp(5)})
        filters.addView(click(if(grid)"☷ Liste" else "▦ Izgara"){
            grid=!grid;renderList()
        },LinearLayout.LayoutParams(0,dp(43),.8f).apply{leftMargin=dp(5)})
        content.removeAllViews()
        if(group==null) {
            val groups=MediaEngineTvPlanner.categories(if(favoritesOnly)
                all.filter{it.favorite} else all).filter{
                query.isEmpty() || it.title.contains(query,true)
            }
            content.addView(label("KATEGORİLER • "+groups.size+" grup • "+
                all.size+" kanal",12f,mint,true).apply{
                setPadding(dp(4),dp(8),0,dp(13))
            })
            if(groups.isEmpty())empty(all.isEmpty())
            groups.take(200).forEachIndexed{index,cat->
                val tile=LinearLayout(this).apply{
                    orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
                    setPadding(dp(12),dp(12),dp(12),dp(12))
                    background=shape(0xff203944.toInt(),0xff14252b.toInt(),18)
                    isClickable=true;isFocusable=true
                    contentDescription=cat.title+" kategorisi, "+cat.count+" kanal"
                    setOnClickListener{group=cat.title;search.setText("");renderList()}
                }
                tile.addView(label("▣",27f,blue,true),
                    LinearLayout.LayoutParams(dp(45),dp(49)))
                val info=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
                info.addView(label((index+1).toString()+". "+cat.title,
                    16f,Color.WHITE,true))
                info.addView(label("Kanallar: "+cat.count,12f,muted))
                tile.addView(info,LinearLayout.LayoutParams(0,-2,1f))
                tile.addView(label("›",28f,blue,true))
                content.addView(tile,LinearLayout.LayoutParams(-1,-2).apply{
                    bottomMargin=dp(9)
                })
            }
        } else {
            val channels=MediaEngineTvPlanner.list(all,group,query,favoritesOnly)
            content.addView(label((group ?: "")+" • "+channels.size+" kanal",
                13f,mint,true).apply{setPadding(dp(4),dp(7),0,dp(13))})
            if(channels.isEmpty())empty(all.isEmpty())
            channels.take(450).chunked(if(grid)2 else 1).forEach{chunk->
                val line=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
                chunk.forEach{ch->
                    line.addView(channelTile(ch),LinearLayout.LayoutParams(0,
                        if(grid)dp(149) else dp(76),1f).apply{
                        rightMargin=dp(6);bottomMargin=dp(7)
                    })
                }
                if(grid&&chunk.size==1)
                    line.addView(View(this),LinearLayout.LayoutParams(0,dp(1),1f))
                content.addView(line)
            }
            if(channels.size>450)content.addView(label(
                "İlk 450 kayıt gösteriliyor. Arama ile daralt.",12f,muted))
        }
    }
    private fun channelTile(ch:VideoChannel):LinearLayout{
        val tile=LinearLayout(this).apply{
            orientation=if(grid)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(9),dp(7),dp(9),dp(7))
            background=shape(0xff1d3440.toInt(),0xff15242a.toInt(),13)
            isClickable=true;isFocusable=true
            contentDescription=ch.name+" kanalı aç"
            setOnClickListener{play(ch)}
            setOnLongClickListener{
                VideoStore.toggleFavorite(this@MediaEngineTvBrowseActivity,ch.id)
                renderList();true
            }
        }
        val image=ImageView(this).apply{
            setImageResource(android.R.drawable.ic_media_play)
            scaleType=ImageView.ScaleType.FIT_CENTER
        }
        if(ch.logo.isNotBlank())ChannelLogoLoader.into(ch.logo,image,this)
        tile.addView(image,LinearLayout.LayoutParams(
            if(grid)dp(58) else dp(50),if(grid)dp(65) else dp(48)))
        tile.addView(label(ch.name,if(grid)13f else 15f,Color.WHITE,true).apply{
            maxLines=if(grid)2 else 1
            ellipsize=android.text.TextUtils.TruncateAt.END
            if(!grid)setPadding(dp(10),0,0,0)
        },LinearLayout.LayoutParams(if(grid)-1 else 0,-2,
            if(grid)0f else 1f))
        if(!grid)tile.addView(click(if(ch.favorite)"★" else "☆"){
            VideoStore.toggleFavorite(this,ch.id);renderList()
        },LinearLayout.LayoutParams(dp(48),dp(43)))
        return tile
    }
    private fun empty(isEmpty:Boolean){
        content.addView(label(if(isEmpty)
            "Henüz TV listen yok.\n\nAşağıdan resmî TV uygulamalarına "+
                "geçebilir veya izinli açık yayınları ekleyebilirsin."
            else "Filtreye uyan kanal bulunamadı.",15f,muted).apply{
            setPadding(dp(12),dp(23),dp(12),dp(16))
        })
        if(isEmpty){
            content.addView(click("▶ TRT Haber • açık yayını ekle"){
                VideoStore.add(this,"TRT Haber",TrustedSources.TRT_HABER,"TRT / Haber")
                renderList()
            },LinearLayout.LayoutParams(-1,dp(52)))
            content.addView(click("⚽ TRT Spor • resmî uygulama"){
                InstalledMediaApps.open(this,"TRT Spor",
                    OfficialMediaDirectory.TRT_SPOR_PACKAGE)
            },LinearLayout.LayoutParams(-1,dp(52)))
            content.addView(click("▣ tabii • TV ve spor yayınları"){
                InstalledMediaApps.open(this,"tabii",
                    OfficialMediaDirectory.TABII_PACKAGE)
            },LinearLayout.LayoutParams(-1,dp(52)))
            content.addView(click("＋ Açık spor kanalları listesini getir"){
                addPublicSportsSources()
            },LinearLayout.LayoutParams(-1,dp(52)))
        }
    }
    private fun addPublicSportsSources() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Açık spor kanallarını getir")
            .setMessage("iptv-org topluluk listesindeki kaynaklar değişebilir ve "+
                "her yayın için erişim hakkı doğrulanmış değildir. "+
                "Yalnızca kullanım izni bulunan açık yayınları kullan. "+
                "Liste otomatik oynatılmaz, önce içeri aktarılır.")
            .setNegativeButton("İptal",null)
            .setPositiveButton("Listeyi içe aktar"){_,_->
                val waiting=android.app.AlertDialog.Builder(this)
                    .setMessage("Açık spor kanalları indiriliyor…")
                    .setCancelable(false).create()
                waiting.show()
                Thread{
                    val imported=try{
                        val data=PlaylistLoader.fromHttps(TrustedSources.SPORTS_PUBLIC)
                        val items=M3uParser.parse(data).filter{!it.isAudio}
                        VideoStore.addAll(this,items)
                    }catch(_:Exception){-1}
                    runOnUiThread{
                        waiting.dismiss()
                        if(!isFinishing && !isDestroyed){
                            android.widget.Toast.makeText(this,
                                if(imported<0)"Listeye erişilemedi."
                                else imported.toString()+" kanal eklendi. Yayınlar tek tek denenmeli.",
                                android.widget.Toast.LENGTH_LONG).show()
                            renderList()
                        }
                    }
                }.start()
            }.show()
    }

    private fun play(ch:VideoChannel){
        RecentStore.remember(this,ch.id,"tv")
        startActivity(Intent(this,PlayerActivity::class.java).apply{
            putExtra(PlayerActivity.EXTRA_URL,ch.url)
            putExtra(PlayerActivity.EXTRA_TITLE,ch.name)
            putExtra(PlayerActivity.EXTRA_ID,ch.id)
            putExtra(PlayerActivity.EXTRA_IS_VOD,false)
        })
    }
    private fun openMainLibrary(){
        startActivity(Intent(this,MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_SELECT_TAB,3)
        })
    }
    private fun back(){if(group!=null){group=null;search.setText("");renderList()}else finish()}
    @Deprecated("Activity navigation")
    override fun onBackPressed(){back()}
    override fun onDestroy(){
        if(::sessionHeader.isInitialized)sessionHeader.release()
        super.onDestroy()
    }
}
