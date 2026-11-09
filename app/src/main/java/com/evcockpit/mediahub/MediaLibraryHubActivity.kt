package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Independent MediaEngine-inspired film, series and sports library on the phone.
 * Works exclusively with sources imported or entered by the user.
 */
class MediaLibraryHubActivity : Activity() {
    private val mint=0xff7ce6cd.toInt()
    private val muted=0xffa7b9bb.toInt()
    private val black=0xff0b1316.toInt()
    private var selected=VideoLibraryKind.MOVIE
    private var favoritesOnly=false
    private var visibleLimit=36
    private lateinit var importButton: Button
    private lateinit var sportzXButton: Button
    private var activeCategory:String?=null
    private lateinit var categoryChips:LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var search: EditText
    private lateinit var counter: TextView
    private lateinit var favorites: Button
    private lateinit var tabs: LinearLayout
    private var dynamicOpenMovies: List<OfficialMediaCard> = emptyList()
    private val browseExecutor = Executors.newSingleThreadExecutor()
    private val bundledExecutor = Executors.newSingleThreadExecutor()
    private val browseHandler = Handler(Looper.getMainLooper())
    private var queryTask: Runnable? = null
    private var browseGeneration = 0
    private var skipInitialResume = true
    private var lastRenderedKind: VideoLibraryKind? = null

    private data class BrowseSnapshot(
        val library: MediaLibraryBrowseResult,
        val allEmbedded: List<EmbeddedFilm>,
        val embeddedMatches: List<EmbeddedFilm>,
        val categories: List<MediaLibraryGroup>,
        val official: List<OfficialMediaCard>
    )

    private fun dp(n:Int)=(resources.displayMetrics.density*n).toInt()
    private fun background(a:Int,b:Int,r:Int=16)=
        GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(a,b)).apply{
            cornerRadius=dp(r).toFloat()
        }
    private fun text(v:String,size:Float=14f,color:Int=Color.WHITE,strong:Boolean=false)=
        TextView(this).apply{
            text=v; textSize=size;setTextColor(color)
            if(strong)setTypeface(typeface,Typeface.BOLD)
            maxLines=3
        }
    private fun button(v:String,action:()->Unit)=Button(this).apply{
        text=v;textSize=13f;isAllCaps=false;setTextColor(Color.WHITE)
        background=background(0xff32474a.toInt(),0xff1a292c.toInt())
        minHeight=dp(48);setOnClickListener{action()}
    }
    private fun space(v:Int)=View(this).apply{
        layoutParams=LinearLayout.LayoutParams(1,dp(v))
    }

    private lateinit var sessionHeader: MediaEngineSessionHeader
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        selected=VideoLibraryKind.values().firstOrNull{
            it.name==intent.getStringExtra(EXTRA_KIND)
        }?.takeUnless{it==VideoLibraryKind.OTHER} ?: VideoLibraryKind.MOVIE
        window.statusBarColor=black;window.navigationBarColor=black
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(black)
            setPadding(dp(14),dp(12),dp(14),dp(10))
        }
        setContentView(root)
        sessionHeader=MediaEngineSessionHeader(this)
        root.addView(sessionHeader.view)
        if(android.os.Build.VERSION.SDK_INT>=30){
            root.setOnApplyWindowInsetsListener{view,insets->
                val b=insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(dp(14),b.top+dp(8),dp(14),b.bottom+dp(8))
                insets
            }
        }
        val header=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        }
        val labelGroup=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        labelGroup.addView(text("EV Cockpit YOL ARKADAŞIM",12f,mint,true))
        labelGroup.addView(text("Film • Dizi • Maç",25f,Color.WHITE,true))
        header.addView(labelGroup,LinearLayout.LayoutParams(0,-2,1f))
        header.addView(button("⌂ Ana sayfa"){finish()},
            LinearLayout.LayoutParams(dp(111),dp(48)))
        root.addView(header)
        root.addView(space(16))
        tabs=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        root.addView(tabs)
        sportzXButton=button("◉ SportzX • Uygulama içi web") {
            startActivity(Intent(this,SportzXHubActivity::class.java))
        }
        root.addView(sportzXButton,LinearLayout.LayoutParams(-1,dp(49)))
        root.addView(space(6))
        importButton=button("＋ M3U dosyası seç / Google Drive'dan film ekle") { pickM3uFile() }
        root.addView(importButton,LinearLayout.LayoutParams(-1,dp(52)))
        val categoryScroll=HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=false
            isFillViewport=false
        }
        categoryChips=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            setPadding(0,dp(6),0,dp(6))
        }
        categoryScroll.addView(categoryChips)
        root.addView(categoryScroll)
        root.addView(space(7))
        root.addView(button("◈ TMDB film / dizi keşfet (afiş ve bilgiler)") {
            startActivity(Intent(this,TmdbCatalogActivity::class.java).apply {
                putExtra(TmdbCatalogActivity.EXTRA_KIND,
                    if (selected == VideoLibraryKind.SERIES)
                        TmdbKind.SERIES.name else TmdbKind.MOVIE.name)
            })
        },LinearLayout.LayoutParams(-1,dp(47)))
        root.addView(space(11))
        search=EditText(this).apply{
            hint="Film, dizi, maç veya kategori ara"
            setSingleLine(true);textSize=14f
            setTextColor(Color.WHITE);setHintTextColor(muted)
            background=background(0xff1b2b30.toInt(),0xff172327.toInt())
            setPadding(dp(14),dp(8),dp(14),dp(8))
            addTextChangedListener(object:TextWatcher{
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){
                    if(::content.isInitialized){
                        visibleLimit=36
                        queryTask?.let { browseHandler.removeCallbacks(it) }
                        queryTask=Runnable { refresh() }.also {
                            browseHandler.postDelayed(it,280L)
                        }
                    }
                }
                override fun afterTextChanged(s:Editable?){}
            })
        }
        root.addView(search,LinearLayout.LayoutParams(-1,dp(50)))
        root.addView(space(10))
        val controls=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        }
        counter=text("Kaynaklar yükleniyor",12f,muted)
        controls.addView(counter,LinearLayout.LayoutParams(0,-2,1f))
        favorites=button("☆ Favoriler"){favoritesOnly=!favoritesOnly;visibleLimit=36;refresh()}
        controls.addView(favorites,LinearLayout.LayoutParams(dp(113),dp(47)))
        root.addView(controls)
        root.addView(space(9))
        val scroll=ScrollView(this).apply{isFillViewport=true}
        content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        scroll.addView(content)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val footer=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        footer.addView(button("＋ Kaynak ekle"){addSource()},
            LinearLayout.LayoutParams(0,dp(52),1f))
        footer.addView(button("◈ API ekle"){addApiSource()},
            LinearLayout.LayoutParams(0,dp(52),.9f).apply {
                leftMargin=dp(6)
            })
        footer.addView(button("⟳ Yenile"){refresh()},
            LinearLayout.LayoutParams(0,dp(52),.6f).apply{
                leftMargin=dp(6)
            })
        root.addView(footer)
        refresh()
        bundledExecutor.execute {
            BundledMovieLibrary.load(applicationContext)
            browseHandler.post {
                if(!isFinishing && !isDestroyed && selected==VideoLibraryKind.MOVIE)
                    refresh()
            }
        }
        BlenderMovieFeed.load{list->
            if(!isFinishing && !isDestroyed){
                dynamicOpenMovies=list
                refresh()
            }
        }
    }
    override fun onResume(){
        super.onResume()
        if (skipInitialResume) {
            skipInitialResume=false
        } else if(::content.isInitialized) refresh()
    }

    private fun updateTabs(){
        tabs.removeAllViews()
        listOf(VideoLibraryKind.MOVIE,VideoLibraryKind.SERIES,VideoLibraryKind.SPORT)
            .forEach{kind->
                val b=button(kind.title){selected=kind;activeCategory=null;visibleLimit=36;refresh()}
                b.background=if(kind==selected)
                    background(0xff25796b.toInt(),0xff155a54.toInt())
                    else background(0xff1f3033.toInt(),0xff142124.toInt())
                b.setTextColor(if(kind==selected)Color.WHITE else muted)
                tabs.addView(b,LinearLayout.LayoutParams(0,dp(51),1f).apply{
                    rightMargin=dp(5)
                })
            }
    }

    private fun updateCategories(groups:List<MediaLibraryGroup>){
        categoryChips.removeAllViews()
        if(groups.isEmpty())return
        val names=listOf<String?>(null)+groups.take(30).map{it.name}
        names.forEach{category->
            val active=category==activeCategory
            val title=if(category==null)"Tümü" else
                category.take(22)+(if(category.length>22)"…" else "")
            val name=if(category==null)title else
                title+" ("+groups.first{it.name==category}.count+")"
            val chip=button(name){
                activeCategory=category
                visibleLimit=36
                refresh()
            }.apply {
                textSize=12f
                contentDescription=category ?: "Tüm kategoriler"
                background=if(active)
                    background(0xff247868.toInt(),0xff155a54.toInt())
                else background(0xff1c3437.toInt(),0xff15262a.toInt())
            }
            categoryChips.addView(chip,LinearLayout.LayoutParams(
                -2,dp(43)).apply{rightMargin=dp(7)})
        }
    }

    /**
     * The 7,232-film search and SharedPreferences/asset parsing must never run
     * on Android's main/UI thread. A generation token drops stale search output.
     */
    private fun refresh() {
        if (!::content.isInitialized || browseExecutor.isShutdown) return
        val generation = ++browseGeneration
        val kind = selected
        val query = search.text.toString()
        val onlyFavorites = favoritesOnly
        val category = activeCategory
        updateTabs()
        importButton.text="＋ M3U / Google Drive'dan " + kind.title + " ekle"
        sportzXButton.visibility=if(kind==VideoLibraryKind.SPORT)View.VISIBLE else View.GONE
        counter.text="İçerikler hazırlanıyor…"
        if (lastRenderedKind != kind) {
            content.removeAllViews()
            content.addView(text("Katalog arka planda hazırlanıyor…",14f,muted).apply {
                setPadding(dp(9),dp(22),dp(9),dp(20))
            })
        }
        browseExecutor.execute {
            val outcome = runCatching {
                val imported=VideoStore.load(applicationContext)
                val recentIds=RecentStore.ids(applicationContext).mapNotNull {
                    if(it.startsWith("tv:"))it.removePrefix("tv:") else null
                }
                val resumable=if(kind==VideoLibraryKind.SPORT) emptySet()
                    else recentIds.filter {
                        VodProgressStore.position(applicationContext,it)>0L
                    }.toSet()
                val library=MediaLibraryBrowsePlanner.plan(
                    imported,kind,query,onlyFavorites,category,recentIds,resumable
                )
                val embedded=if(kind==VideoLibraryKind.MOVIE && !onlyFavorites)
                    EmbeddedFilmCatalog.load(applicationContext) else emptyList()
                // Bundled 3.1MB M3U loads on a SEPARATE worker so catalogue
                // titles appear without waiting for signed URL parsing.
                val groups=if(embedded.isNotEmpty())
                    EmbeddedFilmCatalog.groups(embedded,query) else emptyList()
                val categories=if(groups.isNotEmpty())
                    (groups.take(25)+library.categories).distinctBy {
                        EmbeddedFilmCatalog.normalized(it.name)
                    } else library.categories
                val ownTitles=if(embedded.isNotEmpty()) imported.asSequence()
                    .filter { VideoLibraryClassifier.classify(it)==VideoLibraryKind.MOVIE }
                    .map { EmbeddedFilmCatalog.normalized(it.name) }.toSet()
                    else emptySet()
                val embeddedMatches=if(embedded.isEmpty()) emptyList() else
                    EmbeddedFilmCatalog.filtered(embedded,query,category)
                        .filterNot { EmbeddedFilmCatalog.normalized(it.title) in ownTitles }
                val official=(OfficialMediaDirectory.list(kind,query)+
                    dynamicOpenMovies.filter { it.kind==kind &&
                        (query.isBlank() || it.title.contains(query,true)) })
                    .distinctBy { if(it.mode==OfficialMediaMode.OPEN_MOVIE)
                        it.target else it.id }
                    .filter { !onlyFavorites ||
                        OfficialMediaFavorites.contains(applicationContext,it.id) }
                BrowseSnapshot(library,embedded,embeddedMatches,categories,official)
            }
            browseHandler.post {
                if (isFinishing || isDestroyed || generation!=browseGeneration) return@post
                outcome.fold(
                    onSuccess = { renderSnapshot(it) },
                    onFailure = {
                        counter.text="Katalog okunamadı"
                        content.removeAllViews()
                        content.addView(text(
                            "Katalog yüklenemedi. Yenile düğmesine basabilirsin. " +
                            "Uygulamayı kapatmak zorunda değilsin.",14f,muted))
                    }
                )
            }
        }
    }

    private fun renderSnapshot(snapshot:BrowseSnapshot) {
        lastRenderedKind = selected
        val library=snapshot.library
        val allEmbedded=snapshot.allEmbedded
        val embeddedMatches=snapshot.embeddedMatches
        val official=snapshot.official
        updateCategories(snapshot.categories)
        counter.text=selected.title+" · "+library.total+" kişisel kayıt • "+
            embeddedMatches.size+" gömülü film • "+official.size+" resmî kaynak"
        favorites.text=if(favoritesOnly)"★ Favoriler" else "☆ Favoriler"
        content.removeAllViews()
        // The public repository holds catalogue titles. The private build may
        // additionally package the user's full M3U; show its actual state.
        if (selected == VideoLibraryKind.MOVIE && !favoritesOnly) {
            val bundled = BundledMovieLibrary.peek()
            content.addView(text(
                when {
                    bundled == null -> "TAM M3U • Dosya yükleniyor…"
                    bundled.size > 0 -> "TAM M3U PAKETTE • ${bundled.size} ARŞİV BAĞLANTISI (ERİŞİM DOĞRULANMADI)"
                    else -> "TAM M3U BULUNAMADI • Yalnızca film adları yüklü"
                },
                12f, muted, true
            ).apply { setPadding(dp(7),dp(9),dp(5),dp(6)) })
        }
        // User-provided movie titles and genres are bundled, never the third-party
        // film stream URLs. Search/genre filtering works offline on first launch.
        if (selected == VideoLibraryKind.MOVIE && !favoritesOnly &&
            allEmbedded.isNotEmpty()) {
            // Known open-licensed video separates broken playlist servers
            // from a broken Media3 player, without storing user credentials.
            content.addView(button("▶ ÇEVRİMDIŞI OYNATICI TESTİ • DAHİLİ VİDEO") {
                playOfflineTestVideo()
            }, LinearLayout.LayoutParams(-1, dp(54)))
            content.addView(text(
                "Önce bunu dene: Uygulamaya gömülü 2 saniyelik renkli hareketli MP4 " +
                "internetsiz oynar. Açılırsa H.264 çözücü ve Media3 temel olarak " +
                "çalışıyordur; eski film sunucuları ayrıca test edilmelidir.",
                12f, muted).apply { setPadding(dp(7), dp(5), dp(7), dp(10)) })
            content.addView(text("GÖMÜLÜ FİLM KATALOĞU • "+allEmbedded.size+" BAŞLIK",
                13f,mint,true).apply { setPadding(dp(7),dp(14),0,dp(7)) })
            content.addView(text(
                if ((BundledMovieLibrary.peek()?.size ?: 0) > 0)
                    "DİKKAT: 7.232 film adı, 7.232 çalışan video anlamına gelmez. " +
                    "Gömülü liste 29 Ağustos 2026 döneminden kalan tarihli/süreli bağlantılar içeriyor. " +
                    "İnternette erişim doğrulanmadı; bu kaynakların açılmaması mümkündür."
                else
                    "Film adları hazır ancak oynatma M3U'su bu APK'da yok. " +
                    "İzlemek için yetkili M3U dosyanı seç.",
                12f,muted).apply { setPadding(dp(7),0,dp(7),dp(11)) })
            embeddedMatches.take(visibleLimit).forEach { film ->
                content.addView(embeddedCard(film),LinearLayout.LayoutParams(-1,-2).apply {
                    bottomMargin=dp(9)
                })
            }
            if (embeddedMatches.size > visibleLimit) {
                val pageCount = minOf(visibleLimit, embeddedMatches.size)
                content.addView(button("▼ Daha fazla film • $pageCount/${embeddedMatches.size}") {
                    visibleLimit=(visibleLimit+36).coerceAtMost(embeddedMatches.size)
                    refresh()
                }, LinearLayout.LayoutParams(-1,dp(53)))
            }
            content.addView(space(10))
        }

        // Display known open movies and official provider choices even when no
        // personal M3U was imported. Avoid an apparently empty Movie/Series page.
        if(official.isNotEmpty()){
            content.addView(text(when(selected){
                VideoLibraryKind.MOVIE -> "AÇIK FİLMLER VE RESMÎ FİLM KATALOĞU"
                VideoLibraryKind.SERIES -> "DİZİLER • RESMÎ SAĞLAYICILAR"
                VideoLibraryKind.SPORT -> "SPOR • RESMÎ YAYIN SAĞLAYICILARI"
                else -> "KEŞFET"
            },13f,mint,true).apply { setPadding(dp(7),dp(14),0,dp(9)) })
            official.forEach{source ->
                content.addView(officialCard(source),LinearLayout.LayoutParams(-1,-2).apply{
                    bottomMargin=dp(9)
                })
            }
            content.addView(space(10))
        }

        if(library.continueWatching.isNotEmpty()){
            content.addView(text("↻ KALDIĞIN YERDEN DEVAM ET",12f,mint,true).apply{
                setPadding(dp(7),dp(11),0,dp(10))
            })
            library.continueWatching.forEach{ch->
                content.addView(itemCard(ch),LinearLayout.LayoutParams(-1,-2).apply{
                    bottomMargin=dp(9)
                })
            }
            content.addView(space(6))
        }

        // Personal imports are the primary reason for opening this screen.
        // A 7,000-entry M3U must not create 500 ImageViews and network image
        // requests at once: show only 36 rows, then add more on user demand.
        content.addView(text("KENDİ M3U KÜTÜPHANEM • "+library.total+" KAYIT",
            12f,mint,true).apply{setPadding(dp(7),dp(10),0,dp(9))})
        val channels=library.entries
        if(channels.isEmpty() && library.continueWatching.isEmpty()){
            if(!favoritesOnly && search.text.isBlank() && activeCategory==null &&
                library.total==0){
                content.addView(text(
                    "Henüz ${selected.title.lowercase()} listesi eklenmemiş. " +
                    "Yukarıdaki M3U / Google Drive düğmesine basıp " +
                    "kullanım hakkın olan .m3u dosyanı seç. " +
                    "Dosyayı sadece Drive bağlantısı olarak bu sohbetten " +
                    "göndermek uygulamaya otomatik yüklemez.",
                    15f,muted).apply{setPadding(dp(12),dp(20),dp(12),dp(15))})
                content.addView(button("＋ M3U DOSYASINI ŞİMDİ SEÇ"){
                    pickM3uFile()
                },LinearLayout.LayoutParams(-1,dp(54)))
            }else{
                content.addView(text(if(favoritesOnly)"Henüz favori içerik yok."
                    else "Bu aramada veya kategoride kayıt bulunamadı.",
                    15f,muted).apply{setPadding(dp(12),dp(20),dp(12),dp(15))})
            }
        }else{
            channels.take(visibleLimit).forEach{ch->
                content.addView(itemCard(ch),LinearLayout.LayoutParams(-1,-2).apply{
                    bottomMargin=dp(10)
                })
            }
            if(channels.size>visibleLimit){
                val current=minOf(visibleLimit,channels.size)
                content.addView(button("▼ Daha fazla göster • $current/${channels.size}"){
                    visibleLimit=(visibleLimit+36).coerceAtMost(channels.size)
                    refresh()
                },LinearLayout.LayoutParams(-1,dp(54)))
            }
        }
    }

    /** A locally generated 96x54 H.264 MP4 packaged as an app asset.
     * Controls for playback ability without touching any CDN or film URL.
     */
    private fun playOfflineTestVideo() {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_OFFLINE_TEST, true)
            putExtra(PlayerActivity.EXTRA_TITLE, "Çevrimdışı Oynatıcı Testi • Yerel H.264")
        })
    }

    private fun embeddedCard(film: EmbeddedFilm): LinearLayout {
        val tile = LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(12),dp(11),dp(11),dp(11))
            background=background(0xff243037.toInt(),0xff131e27.toInt(),16)
            isClickable=true;isFocusable=true
            contentDescription=film.title+" • Film ayrıntılarını aç"
            setOnClickListener { openEmbeddedFilm(film) }
        }
        tile.addView(text("▣",30f,0xffd6a4c5.toInt(),true).apply {
            gravity=Gravity.CENTER
            background=background(0xff583449.toInt(),0xff302b44.toInt(),12)
        }, LinearLayout.LayoutParams(dp(73),dp(88)))
        val labels=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),0,dp(6),0)
        }
        labels.addView(text(film.title,16f,Color.WHITE,true).apply {
            maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
        })
        labels.addView(space(5))
        labels.addView(text(film.group,12f,muted).apply {
            maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
        })
        labels.addView(space(5))
        labels.addView(text(
            if ((BundledMovieLibrary.peek()?.size ?: 0)>0)
                "⚠ Arşiv bağlantısı • çalıştığı doğrulanmadı"
            else "Film bilgisi • Oynatmak için kaynak ekle",11f,muted,true))
        tile.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        tile.addView(text("›",27f,mint,true))
        return tile
    }

    private fun playBundled(film: EmbeddedFilm, candidate: PlaylistEntry) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, candidate.url)
            putExtra(PlayerActivity.EXTRA_TITLE, film.title)
            // A deterministic local id avoids placing signed playback tokens
            // into playback progress or recent-history keys.
            putExtra(PlayerActivity.EXTRA_ID,
                "bundle-" + EmbeddedFilmCatalog.normalized(film.title).hashCode())
            putExtra(PlayerActivity.EXTRA_IS_VOD, true)
        })
    }

    /** The M3U is a bundled *source*, not a guarantee that a stream still works. */
    private fun openEmbeddedFilm(film: EmbeddedFilm) {
        // A newly imported, user-authorized stream always takes precedence over
        // an archived signed link from the original bundled playlist.
        val normalizedTitle = EmbeddedFilmCatalog.normalized(film.title)
        val userMedia = VideoStore.load(this).firstOrNull { channel ->
            VideoLibraryClassifier.classify(channel) == VideoLibraryKind.MOVIE &&
                EmbeddedFilmCatalog.normalized(channel.name) == normalizedTitle
        }
        if (userMedia != null) {
            play(userMedia)
            return
        }
        val bundle = BundledMovieLibrary.peek()
        if (bundle == null) {
            Toast.makeText(this, "Gömülü M3U okunuyor. Tekrar dokun.",
                Toast.LENGTH_SHORT).show()
            return
        }
        val bundled = BundledMovieLibrary.find(film, bundle)
        if (bundled != null) {
            val tms = BundledMovieLibrary.timestampSeconds(bundled.url)
            val old = tms != null && tms > 1_600_000_000L &&
                tms < System.currentTimeMillis()/1000L - 7*24*3600L
            if (!old) {
                playBundled(film,bundled)
            } else {
                AlertDialog.Builder(this)
                    .setTitle(film.title)
                    .setMessage(
                        "Bu film için yalnızca ESKİ ARŞİV YAYIN ADRESİ var. " +
                        "7.232 başlığın arşivde olması hiçbirinin açılacağını kanıtlamaz.\\n\\n" +
                        "Bağlantıda Ağustos 2026 tarihli tms bilgisi var; " +
                        "geçerliliği şüpheli (kesin sona erme süresi bilinmiyor).\\n\\n" +
                        "Önce Filmler sayfasındaki açık lisanslı test videosunu dene. " +
                        "Ardından güncel, kullanım hakkın olan M3U veya HTTPS video " +
                        "bağlantısı ekleyebilirsin."
                    )
                    .setNegativeButton("Kapat", null)
                    .setNeutralButton("M3U seç") { _, _ -> pickM3uFile() }
                    .setPositiveButton("Oynatmayı dene") { _, _ ->
                        playBundled(film,bundled)
                    }.show()
            }
            return
        }
        showUnlinkedFilm(film)
    }

    /** Film catalogue cards without a matching video show a clear source state. */
    private fun showUnlinkedFilm(film: EmbeddedFilm) {
        val normalizedTitle = EmbeddedFilmCatalog.normalized(film.title)
        val own = VideoStore.load(this).firstOrNull { channel ->
            VideoLibraryClassifier.classify(channel) == VideoLibraryKind.MOVIE &&
                EmbeddedFilmCatalog.normalized(channel.name) == normalizedTitle
        }
        if (own != null) {
            play(own)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(film.title)
            .setMessage(
                film.group + "\n\n" +
                "Bu kayıt çevrimdışı film kataloğundan geliyor; APK'nın içinde " +
                "filmin videosu bulunmuyor.\n\n" +
                "Sahip olduğun veya kullanım iznin bulunan HTTPS yayın adresini " +
                "bu filme bağlayabilir ya da M3U dosyası içe aktarabilirsin."
            )
            .setNegativeButton("Kapat", null)
            .setNeutralButton("M3U içe aktar") { _, _ -> pickM3uFile() }
            .setPositiveButton("Yayın bağlantısı ekle") { _, _ ->
                addStreamForFilm(film)
            }
            .show()
    }

    private fun addStreamForFilm(film: EmbeddedFilm) {
        val input = EditText(this).apply {
            hint = "https://.../film.m3u8 veya film.mp4"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(14), dp(15), dp(14), dp(15))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(6), dp(15), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(film.title)
            .setMessage("Yalnızca erişim veya yayın hakkın olan HTTPS video adresini gir. " +
                "Süreli/DRM korumalı bağlantılar oynatılamayabilir.")
            .setView(container)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Kaydet ve oynat", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = input.text.toString().trim()
                if (!M3uParser.isHttpsUrl(url)) {
                    input.error = "Geçerli bir HTTPS video adresi gir"
                    return@setOnClickListener
                }
                VideoStore.addAll(this, listOf(
                    PlaylistEntry(film.title, url, film.group, false)
                ), VideoLibraryKind.MOVIE)
                val saved = VideoStore.load(this).firstOrNull { it.url == url }
                if (saved == null) {
                    input.error = "Kaydedilemedi. Kütüphane dolu olabilir."
                    return@setOnClickListener
                }
                dialog.dismiss()
                activeCategory = null
                visibleLimit = 36
                refresh()
                play(saved)
            }
        }
        dialog.show()
    }

    private fun officialCard(src:OfficialMediaCard):LinearLayout{
        val accent=when(src.kind){
            VideoLibraryKind.MOVIE->0xffc5444b.toInt()
            VideoLibraryKind.SERIES->0xff725cc1.toInt()
            VideoLibraryKind.SPORT->0xff328f79.toInt()
            else->mint
        }
        val tile=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(12),dp(11),dp(12),dp(11))
            background=background(0xff202b31.toInt(),0xff131c24.toInt(),16)
            isClickable=true;isFocusable=true
            contentDescription=src.title+" · "+src.provider
            setOnClickListener{openOfficial(src)}
        }
        val poster=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
            background=background(accent,0xff19212e.toInt(),13)
        }
        if(src.imageUrl.isNotBlank()){
            val cover=ImageView(this).apply{
                scaleType=ImageView.ScaleType.CENTER_CROP
                setImageResource(android.R.drawable.ic_media_play)
            }
            poster.addView(cover,LinearLayout.LayoutParams(-1,-1))
            ChannelLogoLoader.into(src.imageUrl,cover,this)
        }else{
            poster.addView(text(when(src.mode){
                OfficialMediaMode.OPEN_MOVIE->"▶"
                OfficialMediaMode.PROVIDER_APP->"▣"
                OfficialMediaMode.PROVIDER_WEB->"▦"
                OfficialMediaMode.TV_CHANNEL->"●"
            },31f,Color.WHITE,true))
            poster.addView(text(if(src.provider=="Blender Open Movies")"OPEN"
                else src.provider.take(7),10f,Color.WHITE,true))
        }
        tile.addView(poster,LinearLayout.LayoutParams(dp(84),dp(92)))
        val labels=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),0,dp(6),0)
        }
        labels.addView(text(src.title,16f,Color.WHITE,true))
        labels.addView(space(3))
        labels.addView(text(src.detail,12f,muted))
        labels.addView(space(5))
        labels.addView(text(when(src.mode){
            OfficialMediaMode.OPEN_MOVIE->if(VodProgressStore.position(this,src.id)>0L)
                "↻ "+(VodProgressStore.position(this,src.id)/60_000L)+". dakikadan devam"
                else "▶ Açık filmi oynat"
            OfficialMediaMode.PROVIDER_APP->"↗ Resmî uygulamayı aç"
            OfficialMediaMode.PROVIDER_WEB->"↗ Resmî yayın bilgisini aç"
            OfficialMediaMode.TV_CHANNEL->"▶ Canlı yayını dene"
        },12f,mint,true))
        tile.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        val isFav=OfficialMediaFavorites.contains(this,src.id)
        tile.addView(button(if(isFav)"★" else "☆"){
            OfficialMediaFavorites.toggle(this,src.id)
            refresh()
        }.apply{contentDescription=if(isFav)
            src.title+" favorilerden çıkar" else src.title+" favorilere ekle"},
            LinearLayout.LayoutParams(dp(47),dp(48)))
        tile.addView(text("›",28f,accent,true))
        return tile
    }

    private fun openOfficial(src:OfficialMediaCard){
        when(src.mode){
            OfficialMediaMode.PROVIDER_APP->
                InstalledMediaApps.open(this,src.provider,src.target)
            OfficialMediaMode.PROVIDER_WEB->{
                AlertDialog.Builder(this)
                    .setTitle(src.title)
                    .setMessage(src.rightNote+"\n\nResmî sağlayıcının sayfası açılacak.")
                    .setNegativeButton("İptal",null)
                    .setPositiveButton("Resmî siteyi aç"){_,_->
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW,
                                android.net.Uri.parse(src.target)))
                        } catch (_:Exception) {
                            Toast.makeText(this,"Site açılamadı",Toast.LENGTH_SHORT).show()
                        }
                    }.show()
            }
            OfficialMediaMode.OPEN_MOVIE-> {
                val dialog=AlertDialog.Builder(this)
                    .setTitle(src.title)
                    .setMessage("Blender'ın açık lisanslı videosunun oynatma adresi alınıyor…")
                    .setNegativeButton("İptal",null)
                    .create()
                dialog.show()
                BlenderOpenVideo.resolve(src.target){outcome->
                    dialog.dismiss()
                    if(isFinishing || isDestroyed)return@resolve
                    outcome.fold(onSuccess={url->
                        RecentStore.remember(this,src.id,"tv")
                        startActivity(Intent(this,PlayerActivity::class.java).apply{
                            putExtra(PlayerActivity.EXTRA_TITLE,src.title)
                            putExtra(PlayerActivity.EXTRA_URL,url)
                            putExtra(PlayerActivity.EXTRA_ID,src.id)
                            putExtra(PlayerActivity.EXTRA_IS_VOD,true)
                        })
                    },onFailure={
                        AlertDialog.Builder(this)
                            .setTitle(src.title)
                            .setMessage("Blender oynatma bağlantısı şu an alınamadı. "+
                                "Sunucu geçici olarak ulaşılamıyor olabilir. "+
                                "İstersen resmî Blender video sayfasını açabilirsin.")
                            .setNegativeButton("Kapat",null)
                            .setPositiveButton("Blender'da aç"){_,_->
                                BlenderOpenVideo.officialPage(src.target)?.let{url->
                                    try{
                                        startActivity(Intent(Intent.ACTION_VIEW,
                                            android.net.Uri.parse(url)))
                                    }catch(_:Exception){}
                                }
                            }.show()
                    })
                }
            }
            OfficialMediaMode.TV_CHANNEL-> {
                Toast.makeText(this,"Açık kanal bağlantısı için Kaynaklar'ı kullan",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun itemCard(ch:VideoChannel):LinearLayout{
        val tint=when(selected){
            VideoLibraryKind.MOVIE->0xffa82c45.toInt()
            VideoLibraryKind.SERIES->0xff7764a9.toInt()
            VideoLibraryKind.SPORT->0xff198e8b.toInt()
            else->0xff376375.toInt()
        }
        val card=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(10),dp(10),dp(10),dp(10))
            background=background(0xff1b3034.toInt(),0xff111d22.toInt(),19)
            isClickable=true;isFocusable=true
            setOnClickListener{play(ch)}
            contentDescription=ch.name+" • Oynat"
        }
        val poster=LinearLayout(this).apply{
            gravity=Gravity.CENTER
            background=background(tint,0xff1b3037.toInt(),13)
        }
        if(ch.logo.isNotBlank()){
            val pic=ImageView(this).apply{scaleType=ImageView.ScaleType.FIT_CENTER}
            ChannelLogoLoader.into(ch.logo,pic,this)
            poster.addView(pic,LinearLayout.LayoutParams(-1,-1))
        }else{
            poster.addView(text(when(selected){
                VideoLibraryKind.MOVIE->"▣"
                VideoLibraryKind.SERIES->"▤"
                VideoLibraryKind.SPORT->"⚽"
                else->"▶"
            },35f,Color.WHITE,true))
        }
        card.addView(poster,LinearLayout.LayoutParams(dp(88),dp(105)))
        val info=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),0,dp(5),0)
        }
        info.addView(text(ch.name,16f,Color.WHITE,true).apply{
            maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
        })
        info.addView(space(5))
        info.addView(text(ch.group,12f,muted).apply{
            maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
        })
        if(selected!=VideoLibraryKind.SPORT){
            val ms=VodProgressStore.position(this,ch.id)
            if(ms>0){
                info.addView(space(5))
                info.addView(text("↻ "+(ms/60_000L)+". dakikadan devam",11f,mint))
            }
        }
        info.addView(space(8))
        info.addView(text("▶ İzle",13f,mint,true))
        card.addView(info,LinearLayout.LayoutParams(0,-2,1f))
        card.addView(button(if(ch.favorite)"★" else "☆"){
            VideoStore.toggleFavorite(this,ch.id);refresh()
        },LinearLayout.LayoutParams(dp(48),dp(49)))
        return card
    }

    private fun play(ch:VideoChannel){
        RecentStore.remember(this,ch.id,"tv")
        startActivity(Intent(this,PlayerActivity::class.java).apply{
            putExtra(PlayerActivity.EXTRA_URL,ch.url)
            putExtra(PlayerActivity.EXTRA_TITLE,ch.name)
            putExtra(PlayerActivity.EXTRA_ID,ch.id)
            putExtra(PlayerActivity.EXTRA_IS_VOD,selected!=VideoLibraryKind.SPORT)
        })
    }

    private fun pickM3uFile() {
        val kind = selected
        AlertDialog.Builder(this)
            .setTitle(kind.title + " • M3U dosyası")
            .setMessage(
                "Telefondan veya Google Drive dosya seçicisinden M3U dosyanı seç. " +
                "Liste cihazında saklanacak; otomatik paylaşılmayacak. " +
                "Dosyadaki afişler, türler ve videolar " + kind.title.lowercase() +
                " bölümüne eklenecek.\n\n" +
                "Yalnızca izleme / kullanım yetkin bulunan yayınları içe aktar. " +
                "Dosya içindeki tüm bağlantıların çalışacağı garanti edilmez."
            )
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("M3U dosyası seç") { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, PICK_VOD_M3U)
            }.show()
    }

    @Deprecated("Android framework Activity file-picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode != PICK_VOD_M3U && requestCode != PICK_JSON_API) ||
            resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val targetKind = selected
        val waiting = AlertDialog.Builder(this)
            .setTitle(targetKind.title + " • İçe aktarılıyor")
            .setMessage("Film isimleri, afişler ve kategoriler okunuyor…")
            .setCancelable(false).create()
        waiting.show()
        Thread {
            val outcome = runCatching {
                val playlist = contentResolver.openInputStream(uri)?.use { PlaylistLoader.readLimited(it) }
                    ?: error("Dosya okunamadı")
                val items = if (requestCode == PICK_JSON_API)
                    UserMediaApiImporter.parse(playlist)
                else M3uParser.parse(playlist).filterNot { it.isAudio }
                require(items.isNotEmpty()) { "Dosya geçerli HTTPS medya kaydı içermiyor" }
                val added = VideoStore.addAll(applicationContext, items, targetKind)
                Pair(items.size, added)
            }
            runOnUiThread {
                waiting.dismiss()
                if (isFinishing || isDestroyed) return@runOnUiThread
                outcome.fold(onSuccess = { (total, added) ->
                    activeCategory = null
                    visibleLimit = 36
                    refresh()
                    AlertDialog.Builder(this)
                        .setTitle(if (requestCode == PICK_JSON_API)
                            "JSON / API kitaplığı hazır" else "M3U kitaplığı hazır")
                        .setMessage(
                            "$total geçerli video kaydı bulundu.\n" +
                            "$added yeni kayıt " + targetKind.title.lowercase() +
                            " bölümüne kaydedildi.\n\n" +
                            "Daha önce eklenmiş bağlantılar seçilen bölüme taşındı; yeniden çoğaltılmadı. " +
                            "Yayınların erişim hakkını ve çalışabilirliğini tek tek kontrol et."
                        )
                        .setPositiveButton("Tamam", null).show()
                }, onFailure = {
                    // Do not show exception details: playlist URLs can contain account tokens.
                    Toast.makeText(this,
                        "Dosya içe aktarılamadı. 8 MB altı UTF-8 JSON/M3U ve HTTPS adreslerini kontrol et.",
                        Toast.LENGTH_LONG).show()
                })
            }
        }.start()
    }

    private fun addApiSource() {
        val address = EditText(this).apply {
            hint = "https://.../api veya .../playlist.json"
            setSingleLine(true)
            setTextColor(Color.BLACK)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18),0,dp(18),0)
            addView(address)
        }
        AlertDialog.Builder(this)
            .setTitle(selected.title+" • HTTPS API kaynağı")
            .setMessage("Kullanım iznin olan bir JSON API veya M3U bağlantısı gir. " +
                "JSON içinde title/name ve url/stream_url alanları olmalı. " +
                "Özel oturum, şifre, DRM veya sağlayıcıya özel API işlemleri desteklenmez.")
            .setView(wrapper)
            .setNegativeButton("Vazgeç",null)
            .setNeutralButton("JSON / .api dosyası seç") { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, PICK_JSON_API)
            }
            .setPositiveButton("Listeyi içe aktar") { _,_ ->
                val url=address.text.toString().trim()
                if (!M3uParser.isHttpsUrl(url)) {
                    Toast.makeText(this,"Geçerli HTTPS API adresi gerekli.",
                        Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val kind=selected
                val loading=AlertDialog.Builder(this)
                    .setTitle("API listesi okunuyor")
                    .setMessage("JSON veya M3U içerikleri yükleniyor...")
                    .setCancelable(false).create()
                loading.show()
                Thread {
                    val result=runCatching {
                        val items=UserMediaApiImporter.fetch(url)
                        require(items.isNotEmpty()) { "Desteklenen medya alanı bulunamadı" }
                        items.size to VideoStore.addAll(applicationContext,items,kind)
                    }
                    runOnUiThread {
                        loading.dismiss()
                        if(isFinishing || isDestroyed) return@runOnUiThread
                        result.fold(
                            onSuccess={ (found, added) ->
                                activeCategory=null
                                visibleLimit=36
                                favoritesOnly=false
                                refresh()
                                AlertDialog.Builder(this)
                                    .setTitle("API içe aktarıldı")
                                    .setMessage("$found geçerli video kaydı okundu. " +
                                        "$added yeni kayıt eklendi; daha önce kayıtlı " +
                                        "bağlantılar tekrar eklenmedi.")
                                    .setPositiveButton("Tamam",null).show()
                            },
                            onFailure={
                                // Never show raw API exception strings: the URL may
                                // contain account tokens or user credentials.
                                Toast.makeText(this,
                                    "API okunamadı. JSON/M3U biçimini, HTTPS erişimini " +
                                    "ve kaynak iznini kontrol et.",
                                    Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                }.start()
            }.show()
    }

    private fun addSource(){
        val form=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(10),dp(18),dp(5))
        }
        val title=EditText(this).apply{
            hint="İçerik adı / varsa bölüm numarası";setSingleLine(true)
        }
        val address=EditText(this).apply{
            hint="https://.../video.m3u8 veya video.mp4"
            setSingleLine(true)
            inputType=android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        form.addView(title);form.addView(address)
        AlertDialog.Builder(this)
            .setTitle(selected.title+" • İçerik ekle")
            .setMessage("Yalnızca kullanım hakkın olan HTTPS medya bağlantısını ekle.")
            .setView(form)
            .setNegativeButton("İptal",null)
            .setPositiveButton("Kaydet"){_,_->
                if(VideoStore.add(this,title.text.toString().trim(),
                        address.text.toString().trim(),selected.sourceGroup)){
                    Toast.makeText(this,"İçerik eklendi",Toast.LENGTH_SHORT).show()
                    refresh()
                }else Toast.makeText(this,"HTTPS adresi geçersiz veya kaynak zaten var.",
                    Toast.LENGTH_LONG).show()
            }.show()
    }
    companion object {
        const val EXTRA_KIND="media_library_kind"
        private const val PICK_VOD_M3U = 742
        private const val PICK_JSON_API = 743
    }
    override fun onDestroy(){
        browseGeneration++
        queryTask?.let { browseHandler.removeCallbacks(it) }
        browseExecutor.shutdownNow()
        bundledExecutor.shutdownNow()
        if(::sessionHeader.isInitialized)sessionHeader.release()
        super.onDestroy()
    }
}
