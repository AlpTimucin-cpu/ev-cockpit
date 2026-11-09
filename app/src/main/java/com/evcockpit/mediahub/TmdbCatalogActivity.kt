package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.Normalizer
import java.util.Locale

/**
 * Metadata discovery from the official TMDB API. This screen is NOT a streaming
 * service and never connects to REC TV's private playback backend.
 */
class TmdbCatalogActivity : Activity() {
    private val navy=0xff0b1520.toInt()
    private val muted=0xffa9c3ce.toInt()
    private val mint=0xff85ead5.toInt()
    private var selected=TmdbKind.MOVIE
    private lateinit var results:LinearLayout
    private lateinit var search:EditText
    private lateinit var status:TextView
    private lateinit var tabs:LinearLayout
    private var counter=0

    private fun dp(n:Int)=(resources.displayMetrics.density*n).toInt()
    private fun background(a:Int,b:Int,r:Int=15)=
        GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(a,b)).apply{
            cornerRadius=dp(r).toFloat()
        }
    private fun label(v:String,size:Float=13f,color:Int=Color.WHITE,heavy:Boolean=false)=
        TextView(this).apply{
            text=v; textSize=size;setTextColor(color)
            if(heavy)setTypeface(typeface,Typeface.BOLD)
        }
    private fun button(v:String,fn:()->Unit)=Button(this).apply{
        text=v;textSize=13f;isAllCaps=false;setTextColor(Color.WHITE)
        background=background(0xff264a50.toInt(),0xff1b3743.toInt())
        setOnClickListener{fn()}
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        selected=if(intent.getStringExtra(EXTRA_KIND)==TmdbKind.SERIES.name)
            TmdbKind.SERIES else TmdbKind.MOVIE
        window.statusBarColor=navy;window.navigationBarColor=navy
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(navy)
            setPadding(dp(12),dp(14),dp(12),dp(9))
        }
        setContentView(root)
        if(android.os.Build.VERSION.SDK_INT>=30){
            root.setOnApplyWindowInsetsListener{view,insets->
                val b=insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(dp(12),b.top+dp(8),dp(12),b.bottom+dp(6))
                insets
            }
        }
        val heading=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        }
        heading.addView(label("TMDB  •  Film ve Dizi Keşfi",21f,Color.WHITE,true),
            LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(button("⌂"){finish()},LinearLayout.LayoutParams(dp(54),dp(48)))
        root.addView(heading)
        root.addView(label("Film bilgileri ve afişler TMDB'den gelir. Burada video yayını sağlanmaz.",
            12f,muted))
        tabs=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        root.addView(tabs)
        search=EditText(this).apply{
            hint="Film / dizi ara (boşsa haftanın popülerleri)"
            setSingleLine(true);textSize=14f;setTextColor(Color.WHITE)
            setHintTextColor(muted)
            inputType=InputType.TYPE_CLASS_TEXT
            background=background(0xff1a303e.toInt(),0xff111f2d.toInt())
            setPadding(dp(12),dp(8),dp(12),dp(8))
        }
        root.addView(search,LinearLayout.LayoutParams(-1,dp(52)).apply{
            topMargin=dp(12)
        })
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        actions.addView(button("⌕ Ara / Listeyi getir"){fetch()},
            LinearLayout.LayoutParams(0,dp(50),1f))
        actions.addView(button("⚙ API anahtarı"){askToken()},
            LinearLayout.LayoutParams(0,dp(50),.87f))
        root.addView(actions)
        status=label("TMDB API Read Access Token ile erişim sağlayabilirsin.",12f,muted)
        status.setPadding(dp(5),dp(11),0,dp(9))
        root.addView(status)
        val scroll=ScrollView(this)
        results=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        scroll.addView(results)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val foot=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        foot.addView(label("TMDB verileri © ilgili hak sahipleri.",10f,muted))
        foot.addView(label("This product uses the TMDB API but is not endorsed or certified by TMDB.",
            10f,muted))
        foot.addView(button("TMDB resmî sitesi ve atıf bilgisi"){openTmdbAbout()},
            LinearLayout.LayoutParams(-1,dp(46)))
        root.addView(foot)
        refreshTabs()
        if(TmdbMetadata.isConfigured())fetch() else askToken()
    }

    private fun refreshTabs(){
        tabs.removeAllViews()
        listOf(TmdbKind.MOVIE,TmdbKind.SERIES).forEach{kind->
            val button=button(kind.label){
                selected=kind;refreshTabs();search.setText("");fetch()
            }
            button.background=if(kind==selected)
                background(0xff266f77.toInt(),0xff184959.toInt())
            else background(0xff1c3444.toInt(),0xff132331.toInt())
            tabs.addView(button,LinearLayout.LayoutParams(0,dp(47),1f).apply{
                rightMargin=dp(5)
            })
        }
    }

    private fun askToken(){
        val field=EditText(this).apply{
            hint="TMDB API Read Access Token"
            setSingleLine(true)
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val wrap=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0)
        }
        wrap.addView(label("TMDB hesabından alınan kendi API okuma anahtarını gir. Anahtar yalnızca uygulama belleğinde tutulur, dosyaya kaydedilmez.",12f,muted))
        wrap.addView(field)
        AlertDialog.Builder(this)
            .setTitle("TMDB bağlantısı")
            .setView(wrap)
            .setNegativeButton("İptal",null)
            .setNeutralButton("TMDB'den anahtar al"){_,_->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://www.themoviedb.org/settings/api")))
            }
            .setPositiveButton("Bağlan"){_,_->
                if(TmdbMetadata.authorizeForSession(field.text.toString())) fetch()
                else Toast.makeText(this,"API anahtarı biçimi geçersiz",Toast.LENGTH_LONG).show()
                field.text.clear()
            }.show()
    }

    private fun fetch(){
        val seq=++counter
        status.text="TMDB yükleniyor…"
        results.removeAllViews()
        TmdbMetadata.load(selected,search.text.toString()){ outcome ->
            if(seq!=counter || isFinishing || isDestroyed)return@load
            outcome.fold(onSuccess={ items ->
                status.text=selected.label+" · "+items.size+" sonuç • TMDB bilgileri"
                if(items.isEmpty())results.addView(label("Sonuç bulunamadı",14f,muted))
                items.forEach{entry->
                    results.addView(card(entry),LinearLayout.LayoutParams(-1,-2).apply{
                        bottomMargin=dp(11)
                    })
                }
            }, onFailure={failure->
                status.text=failure.message.orEmpty().take(100)
            })
        }
    }

    private fun card(entry:TmdbEntry):View{
        val row=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(9),dp(9),dp(9),dp(9))
            background=background(0xff1e3748.toInt(),0xff14232e.toInt(),18)
            isClickable=true;isFocusable=true
            contentDescription=entry.title+" içeriği hakkında"
            setOnClickListener{showDetails(entry)}
        }
        val artwork=ImageView(this).apply{
            scaleType=ImageView.ScaleType.CENTER_CROP
            setImageResource(android.R.drawable.ic_media_play)
            setBackgroundColor(0xff18313e.toInt())
        }
        row.addView(artwork,LinearLayout.LayoutParams(dp(84),dp(116)))
        if(entry.posterUrl.isNotBlank())ChannelLogoLoader.into(entry.posterUrl,artwork,this)
        val data=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(11),0,0,0)
        }
        data.addView(label(entry.title,16f,Color.WHITE,true).apply{
            maxLines=2
        })
        data.addView(label(entry.releaseYear+"  ★ "+
            String.format(Locale.US,"%.1f",entry.score),12f,mint))
        data.addView(label(entry.description.ifBlank{"Özet bulunmuyor."}.take(145),
            12f,muted).apply{maxLines=4})
        data.addView(label("Bilgi / kütüphanemde ara  ›",12f,mint,true))
        row.addView(data,LinearLayout.LayoutParams(0,-2,1f))
        return row
    }

    private fun fold(value:String):String=Normalizer.normalize(
        value.lowercase(Locale.ROOT).replace('ı','i'),Normalizer.Form.NFD
    ).replace(Regex("\\p{M}+"),"").trim()

    private fun showDetails(entry:TmdbEntry){
        val kind=if(selected==TmdbKind.MOVIE)VideoLibraryKind.MOVIE else VideoLibraryKind.SERIES
        AlertDialog.Builder(this)
            .setTitle(entry.title+" ("+entry.releaseYear+")")
            .setMessage(entry.description.ifBlank{"Özet bulunmuyor."}+
                "\n\nTMDB yalnızca film/dizi verilerini sağlar. İzlemek için kendi yasal yayın kaynağın gerekli.")
            .setNeutralButton("Türkiye'de nerede izlenir?"){_,_->
                showWatchProviders(entry)
            }
            .setPositiveButton("Kütüphanemde ara"){_,_->
                val title=fold(entry.title)
                val matches=VideoStore.load(this).filter{
                    VideoLibraryClassifier.classify(it)==kind &&
                        (fold(it.name).contains(title) || title.contains(fold(it.name))) &&
                        it.name.isNotBlank()
                }.take(30)
                if(matches.isEmpty()){
                    AlertDialog.Builder(this)
                        .setTitle("Yayın kaynağı bulunamadı")
                        .setMessage("Bu içerik kendi film/dizi listende yok. "+
                            "Film ve Dizi kütüphanesinden erişim hakkın bulunan HTTPS medya kaynağını ekleyebilirsin.")
                        .setPositiveButton("Tamam",null).show()
                }else{
                    AlertDialog.Builder(this)
                        .setTitle("Kütüphanemdeki eşleşmeler")
                        .setItems(matches.map{it.name}.toTypedArray()){_,index->
                            val item=matches[index]
                            startActivity(Intent(this,PlayerActivity::class.java).apply{
                                putExtra(PlayerActivity.EXTRA_URL,item.url)
                                putExtra(PlayerActivity.EXTRA_TITLE,item.name)
                                putExtra(PlayerActivity.EXTRA_ID,item.id)
                                putExtra(PlayerActivity.EXTRA_IS_VOD,true)
                            })
                        }
                        .setNegativeButton("Kapat",null).show()
                }
            }
            .setNegativeButton("Kapat",null).show()
    }

    private fun showWatchProviders(entry: TmdbEntry) {
        val kind = selected
        Toast.makeText(this,
            "Türkiye'deki resmî yayın seçenekleri sorgulanıyor…",
            Toast.LENGTH_SHORT).show()
        TmdbMetadata.loadWatchProviders(kind, entry.id) { response ->
            if (isDestroyed || isFinishing) return@loadWatchProviders
            response.fold(onSuccess={ options ->
                fun line(title: String, entries: List<String>): String =
                    title + ": " + if (entries.isEmpty()) "Bildirilmedi"
                    else entries.joinToString(", ")
                val content = listOf(
                    line("Abonelik", options.subscriptions),
                    line("Ücretsiz / reklamlı", options.freeOrAds),
                    line("Kirala", options.rental),
                    line("Satın al", options.purchase)
                ).joinToString("\n\n")
                AlertDialog.Builder(this)
                    .setTitle(entry.title + " • Türkiye'de izleme")
                    .setMessage(content +
                        "\n\nVeri: TMDB / JustWatch. Güncel abonelik, ücret, " +
                        "ülke ve yayın hakkı sağlayıcıya bağlıdır. " +
                        "Bu liste video yayını değildir.")
                    .setPositiveButton("İzleme seçeneklerini aç"){_,_->
                        startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse(options.watchPage)))
                    }
                    .setNegativeButton("Kapat",null).show()
            },onFailure={error->
                AlertDialog.Builder(this)
                    .setTitle("Sağlayıcı bilgisi getirilemedi")
                    .setMessage(error.message.orEmpty().take(160))
                    .setPositiveButton("Tamam",null).show()
            })
        }
    }

    private fun openTmdbAbout(){
        startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.themoviedb.org/about/logos-attribution")))
    }

    companion object { const val EXTRA_KIND="tmdb_kind" }
}
