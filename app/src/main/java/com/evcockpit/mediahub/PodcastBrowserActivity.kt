package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** User-controlled podcast RSS directory with OPML import/export. */
class PodcastBrowserActivity: Activity() {
    private val bg=0xff0c181b.toInt()
    private val accent=0xffbe8bf4.toInt()
    private lateinit var body:LinearLayout
    private lateinit var header:TextView
    private var selected:PodcastFeed?=null
    private var episodes=emptyList<PodcastEpisode>()

    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun shape(a:Int,b:Int)=GradientDrawable(
        GradientDrawable.Orientation.TL_BR,intArrayOf(a,b)).apply {
        cornerRadius=dp(15).toFloat()
        setStroke(dp(1),0xff40535c.toInt())
    }
    private fun label(s:String,size:Float=14f,bold:Boolean=false)=TextView(this).apply{
        text=s;textSize=size;setTextColor(Color.WHITE)
        if(bold)setTypeface(typeface,Typeface.BOLD)
        maxLines=3
    }
    private fun action(s:String,fn:()->Unit)=label(s,16f,true).apply{
        setPadding(dp(16),dp(16),dp(16),dp(16))
        background=shape(0xff27343e.toInt(),0xff161f2c.toInt())
        isClickable=true;isFocusable=true
        setOnClickListener{fn()}
    }
    private lateinit var sessionHeader: MediaEngineSessionHeader
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=bg;window.navigationBarColor=bg
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(13),dp(10),dp(13),dp(9))
        }
        setContentView(root)
        sessionHeader=MediaEngineSessionHeader(this)
        root.addView(sessionHeader.view)
        if(android.os.Build.VERSION.SDK_INT>=30){
            root.setOnApplyWindowInsetsListener{v,i->
                val b=i.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(dp(13),b.top+dp(8),dp(13),b.bottom+dp(9))
                i
            }
        }
        val top=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        top.addView(action("‹"){if(selected!=null){selected=null;render()}else finish()},
            LinearLayout.LayoutParams(dp(54),dp(52)))
        header=label("Podcastlar",22f,true).apply{setPadding(dp(12),dp(5),0,0)}
        top.addView(header,LinearLayout.LayoutParams(0,-2,1f))
        top.addView(action("⌂"){finish()},LinearLayout.LayoutParams(dp(54),dp(52)))
        root.addView(top)
        root.addView(View(this),LinearLayout.LayoutParams(1,dp(14)))
        val scroll=ScrollView(this).apply{isFillViewport=true}
        body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        scroll.addView(body)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        render()
    }
    override fun onResume(){super.onResume();if(::body.isInitialized)render()}
    private fun line(v:View){
        body.addView(v,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)})
    }
    private fun render(){
        body.removeAllViews()
        val feed=selected
        header.text=feed?.title ?: "Podcastlar"
        if(feed==null){
            line(action("⌕  Podcast ara / kaynakları göster"){
                showSources()
            })
            line(action("⊕  RSS akışı ekle"){addFeed()})
            line(action("⇩  OPML içe aktar"){selectOpml()})
            line(action("⇧  OPML dışa aktar"){saveOpml()})
            val all=PodcastSources.feeds(this)
            line(label("KAYITLI PODCASTLAR • "+all.size,13f,true))
            if(all.isEmpty())line(label(
                "Henüz podcast kaynağı yok. RSS adresi ekleyebilir veya OPML dosyanı içe aktarabilirsin.",
                14f))
            all.forEach{item->
                val row=action("◉ "+item.title+"\nRSS bölümlerini aç ›"){
                    selected=item;episodes=emptyList();render();loadEpisodes(item)
                }
                row.setOnLongClickListener{
                    AlertDialog.Builder(this)
                        .setTitle(item.title)
                        .setMessage("Bu RSS kaynağı silinsin mi?")
                        .setNegativeButton("Vazgeç",null)
                        .setPositiveButton("Sil"){_,_->
                            PodcastSources.remove(this,item.url)
                            render()
                        }.show()
                    true
                }
                line(row)
            }
        }else{
            line(action("⟳ Bölümleri yenile"){loadEpisodes(feed)})
            if(episodes.isEmpty())line(label(
                "Henüz bölüm yüklenmedi. RSS akışına bağlanmak için yenile.",14f))
            episodes.take(120).forEach{ep->
                line(action("▶  "+ep.title){
                    startActivity(Intent(this,PlayerActivity::class.java).apply{
                        putExtra(PlayerActivity.EXTRA_URL,ep.link)
                        putExtra(PlayerActivity.EXTRA_TITLE,ep.title)
                    })
                })
            }
        }
    }
    private fun showSources() {
        val items=PodcastSources.feeds(this)
        if(items.isEmpty()){addFeed();return}
        AlertDialog.Builder(this).setTitle("Podcast kaynağı seç")
            .setItems(items.map{it.title}.toTypedArray()){_,i->
                selected=items[i];episodes=emptyList();render();loadEpisodes(items[i])
            }.setNegativeButton("Kapat",null).show()
    }
    private fun addFeed(){
        val form=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(12),dp(18),dp(4))
        }
        val name=EditText(this).apply{
            hint="Podcast adı";setSingleLine(true)
        }
        val url=EditText(this).apply{
            hint="https://.../rss.xml";setSingleLine(true)
            inputType=android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        form.addView(name);form.addView(url)
        AlertDialog.Builder(this).setTitle("Yeni RSS kaynağı")
            .setMessage("Güvendiğin HTTPS podcast kaynağını ekle.")
            .setView(form).setNegativeButton("İptal",null)
            .setPositiveButton("Ekle"){_,_->
                if(PodcastSources.add(this,name.text.toString(),url.text.toString()))render()
                else Toast.makeText(this,"Kaynak eklenemedi veya zaten kayıtlı.",Toast.LENGTH_LONG).show()
            }.show()
    }
    private fun loadEpisodes(feed:PodcastFeed){
        line(label("RSS yükleniyor...",13f))
        Thread{
            try {
                val list=PodcastSources.parseRss(PodcastSources.fetch(feed.url),feed.title)
                PodcastCarLibrary.cache(applicationContext, feed, list)
                runOnUiThread{
                    if(isFinishing||isDestroyed||selected?.url!=feed.url)return@runOnUiThread
                    episodes=list;render()
                    if(list.isEmpty())Toast.makeText(this,
                        "RSS'de HTTPS ses bölümü bulunamadı.",Toast.LENGTH_LONG).show()
                }
            }catch(_:Exception){
                runOnUiThread{
                    if(!isFinishing&&!isDestroyed&&selected?.url==feed.url){
                        render()
                        Toast.makeText(this,
                            "RSS indirilemedi. Adresi ve ağ bağlantısını kontrol et.",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }
    private fun selectOpml(){
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
            addCategory(Intent.CATEGORY_OPENABLE)
            type="*/*"
        },42)
    }
    private fun saveOpml(){
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{
            addCategory(Intent.CATEGORY_OPENABLE)
            type="text/xml"
            putExtra(Intent.EXTRA_TITLE,"evcockpit-podcastlar.opml")
        },43)
    }
    @Deprecated("Activity result for simple documents")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK)return
        val uri=data?.data?:return
        try {
            if(requestCode==42){
                val bytes=contentResolver.openInputStream(uri)?.use{input->
                    val output=java.io.ByteArrayOutputStream()
                    val chunk=ByteArray(4096)
                    while(true){
                        val n=input.read(chunk)
                        if(n<0)break
                        if(output.size()+n>1_000_000)throw IllegalArgumentException()
                        output.write(chunk,0,n)
                    }
                    output.toByteArray()
                }?:return
                var count=0
                PodcastSources.parseOpml(bytes).forEach{
                    if(PodcastSources.add(this,it.title,it.url))count++
                }
                render()
                Toast.makeText(this,count.toString()+" podcast kaynağı içe aktarıldı",
                    Toast.LENGTH_LONG).show()
            }else if(requestCode==43){
                contentResolver.openOutputStream(uri)?.use {
                    it.write(PodcastSources.exportOpml(PodcastSources.feeds(this))
                        .toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this,"OPML kaydedildi",Toast.LENGTH_SHORT).show()
            }
        }catch(_:Exception){
            Toast.makeText(this,"OPML dosyası işlenemedi.",Toast.LENGTH_LONG).show()
        }
    }
    @Deprecated("Activity navigation")
    override fun onBackPressed(){
        if(selected!=null){selected=null;render()}else super.onBackPressed()
    }
    override fun onDestroy(){
        if(::sessionHeader.isInitialized)sessionHeader.release()
        super.onDestroy()
    }
}
