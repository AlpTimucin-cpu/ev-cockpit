package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/** Phone-side setup UI. Android Auto renders its own driver-safe library view. */
class MainActivity : Activity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private lateinit var listContainer: LinearLayout
    private lateinit var status: TextView
    private lateinit var miniHeader: MediaEngineAudioHeader
    private lateinit var videoContainer: LinearLayout
    private lateinit var searchVideos: EditText
    private lateinit var searchRadios: EditText
    private var favoritesOnly: Boolean = false
    private var radioFavoritesOnly = false
    private var radioPage = 0
    private var videoPage = 0
    private lateinit var homeContainer: LinearLayout
    private lateinit var tvPlayer: TvPlayback
    private var epgUrlField: EditText? = null
    private lateinit var radioDashboard: RadioDashboard
    private var radioDirectoryLoading = false
    private var currentRadioId: String? = null
    private lateinit var tabPages: List<LinearLayout>
    private lateinit var navButtons: List<TextView>
    private lateinit var contentScroll: ScrollView
    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 16, 28)
        window.navigationBarColor = Color.rgb(12, 22, 37)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(9, 16, 28))
        }
        setContentView(root)
        // Android 15 enforces edge-to-edge drawing: keep the bottom nav above OS controls.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val systemBars = insets.getInsets(WindowInsets.Type.systemBars())
            root.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
        miniHeader = MediaEngineAudioHeader(
            this,
            { skipRadio(-1) },
            { toggleRadioPlayback() },
            { skipRadio(1) },
            { CarCompatibility.show(this) }
        )
        status = miniHeader.status
        root.addView(miniHeader.view)

        contentScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, dp(6), 0, dp(8))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16),0,dp(16),0)
        }
        contentScroll.addView(content)
        root.addView(contentScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val home = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val television = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val radio = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val library = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tabPages = listOf(home, television, radio, library)
        tabPages.forEach { content.addView(it) }
        homeContainer = home

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8),dp(7),dp(8),dp(9))
            setBackgroundColor(0xff102137.toInt())
        }
        navButtons = listOf("⌂\nAna sayfa","⚙\nAyarlar").mapIndexed { index, name ->
            textAction(name) { selectTab(if(index==0)0 else 3) }.apply {
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(6), dp(3), dp(6))
                minHeight = dp(50)
                nav.addView(this, LinearLayout.LayoutParams(0,dp(54),1f))
            }
        }
        root.addView(nav)
        selectTab(0)

        // Video is embedded in a single persistent player instead of a long vertical channel list.
        tvPlayer = TvPlayback(
            this,
            { VideoStore.load(this) },
            { selectTab(3) },
            { channel ->
                RecentStore.remember(this, channel.id, "tv")
                renderHome()
            }
        )
        val mediaApps = panel().apply {
            background=gradient(0xff162c48.toInt(),0xff172c3c.toInt(),16)
        }
        mediaApps.addView(label("VİDEO UYGULAMALARI",16,true))
        mediaApps.addView(label("YouTube ve Netflix kartları resmî telefon uygulamalarını açar. Bağımsız HTTPS tarayıcısı aşağıda yer alır.",12,false))
        val appActions=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        appActions.addView(button("▶ YouTube") {
            startActivity(Intent(this, MediaEngineYoutubeActivity::class.java))
        }, LinearLayout.LayoutParams(0,dp(50),1f))
        appActions.addView(button("▣ Netflix") {
            openSubscriptionPortal(StreamingService.NETFLIX)
        }, LinearLayout.LayoutParams(0,dp(50),1f))
        appActions.addView(button("▶ Prime Video") {
            openSubscriptionPortal(StreamingService.PRIME_VIDEO)
        }, LinearLayout.LayoutParams(0,dp(50),1f))
        mediaApps.addView(appActions)
        mediaApps.addView(button("🌐 Web Medya • HTTPS video bağlantısı aç") {
            openWebMedia(MediaWebActivity.YOUTUBE_URL)
        })
        television.addView(mediaApps)
        television.addView(button("📺 MediaEngine 3.2 uygulamasını aç") { MediaEngineBridge.open(this) })
        television.addView(button("↗ Kendi TV kanalımdan MediaEngine’ya gönder") { MediaEngineBridge.chooseVideo(this) })
        television.addView(tvPlayer.view())
        television.addView(label("Kanal listesi ve yayın adresleri Kitaplığım bölümünde saklanır.", 12, false))

        radioDashboard = RadioDashboard(
            this,
            { StationStore.load(this) },
            { station -> playStation(station) },
            { toggleRadioPlayback() },
            { direction -> skipRadio(direction) },
            { syncRadioCatalogue(true) },
            { id ->
                StationStore.toggleFavorite(this, id)
                refresh()
                renderHome()
            }
        )
        radio.addView(radioDashboard.view())
        radio.addView(button("↗ Seçili radyoyu MediaEngine’ya gönder") {
            val selectedId = controller?.currentMediaItem?.mediaId ?: currentRadioId
            val selected = StationStore.load(this).firstOrNull { it.id == selectedId }
            MediaEngineBridge.offerRadio(this, selected)
        })
        radio.addView(button("↗ FermataX 2.2.6 ile radyoyu telefonda aç") {
            val chosenId = controller?.currentMediaItem?.mediaId ?: currentRadioId
            val chosen = StationStore.load(this).firstOrNull { it.id == chosenId }
            FermataXInterop.offerRadio(this, chosen)
        })

        library.addView(section("KÜTÜPHANEM","Kaynaklar, oynatma ve cihaz bağlantıları"))
        library.addView(button("🗺 Türkiye • Adres / Şarj / Rota Haritası") {
            startActivity(Intent(this, TurkeyNavigationActivity::class.java))
        })
        library.addView(button("⚡ EV Cockpit • Enerji / Menzil / Şarj Planlayıcı") {
            startActivity(Intent(this, EvGarageActivity::class.java))
        })
        val importCard=panel()
        importCard.addView(label("M3U / M3U8 KANAL LİSTESİ",18,true))
        importCard.addView(label("Abonelik veya açık yayın bağlantınızı ekleyin. Kanallar TV ve Radyo sekmelerine ayrılır.",12,false))
        val playlistUrl=searchInput("https://.../kanallar.m3u")
        importCard.addView(playlistUrl)
        importCard.addView(button("Bağlantıdan kanalları yükle") { importFromHttps(playlistUrl.text.toString().trim()) })
        importCard.addView(button("Telefondan M3U dosyası seç",secondary=true) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type="*/*"
            }, PICK_PLAYLIST)
        })
        library.addView(importCard)
        val providerCard=panel()
        providerCard.addView(label("MEDIAENGINE • IPTV SAĞLAYICILARI",18,true))
        providerCard.addView(label(
            "Xtream Codes sağlayıcını doğrudan EV Cockpit kitaplığına bağla. " +
            "MediaEngine'nın ayrıca kurulu olması gerekmez. Sadece HTTPS ve izinli yayınlar desteklenir.",
            12,false))
        providerCard.addView(button("＋ Xtream Codes hesabımdan TV, film ve radyo içe aktar") {
            MediaEngineXtreamImport.show(this) { refresh(); refreshVideo(); renderHome() }
        })
        library.addView(providerCard)

        val ready=panel()
        ready.addView(label("AÇIK YAYIN LİSTELERİ",18,true))
        TrustedSources.playlists.forEach { source ->
            ready.addView(textAction("＋  "+source.name) {
                importFromHttps(source.url)
            })
        }
        ready.addView(button("🌍 Dünya kanalları • Ülke seç") { showWorldPicker(true) })
        ready.addView(button("▦ Kategoriye göre kanal ara") { showWorldPicker(false) })
        ready.addView(textAction("▶  TRT Haber kanalı") {
            if (VideoStore.add(this, "TRT Haber", TrustedSources.TRT_HABER, "Türkiye / Haber")) {
                refreshVideo(); renderHome(); toast("TRT Haber eklendi")
            } else toast("Kanal zaten var veya kaynak eklenemedi")
        })
        ready.addView(textAction("▶  Apple HLS test videosu") {
            if (VideoStore.add(this, "Apple HLS Test", TrustedSources.APPLE_HLS_TEST, "Test")) {
                refreshVideo(); renderHome(); toast("Test videosu eklendi")
            } else toast("Video zaten ekli")
        })
        library.addView(ready)

        val epgCard=panel()
        epgCard.addView(label("TV PROGRAM REHBERİ • XMLTV / APTV TARZI",18,true))
        epgCard.addView(label("M3U tvg-id alanı ile eşleşen programları Şimdi / Sonra olarak gösterir. HTTPS XMLTV veya XMLTV.GZ rehberi ekleyebilirsin.",12,false))
        val epgUrlInput=searchInput("https://.../epg.xml veya epg.xml.gz")
        epgUrlInput.setText(EpgGuide.source(this))
        epgUrlField = epgUrlInput
        epgCard.addView(epgUrlInput)
        epgCard.addView(button("📅 EPG rehberini indir ve güncelle") {
            importEpgFromHttps(epgUrlInput.text.toString().trim())
        })
        epgCard.addView(button("▦ Bugünkü programları göster",secondary=true) {
            showEpgSchedule()
        })
        library.addView(epgCard)

        val radioCard=panel()
        radioCard.addView(label("YENİ RADYO",18,true))
        val radioName=searchInput("Radyo adı")
        val radioUrl=searchInput("https://.../yayın.mp3")
        radioCard.addView(radioName)
        radioCard.addView(radioUrl)
        radioCard.addView(button("Radyo ekle") {
            if (StationStore.add(this, radioName.text.toString(),radioUrl.text.toString())) {
                radioName.setText("");radioUrl.setText("")
                refresh();renderHome();toast("Radyo eklendi")
            } else toast("Geçerli HTTPS adresi gir")
        })
        library.addView(radioCard)

        val external=panel()
        external.addView(label("DİĞER UYGULAMALAR",18,true))
        external.addView(textAction("♫  YouTube Music'i aç") {
            val launch=packageManager.getLaunchIntentForPackage("com.google.android.apps.youtube.music")
            try { startActivity(launch ?: Intent(Intent.ACTION_VIEW,Uri.parse("https://music.youtube.com"))) }
            catch (_: Exception) { toast("YouTube Music açılamadı") }
        })
        external.addView(textAction("▶  YouTube uygulamasını aç") {
            InstalledMediaApps.openYouTube(this)
        })
        external.addView(textAction("▣  Prime Video uygulamasını aç") {
            InstalledMediaApps.openPrimeVideo(this)
        })
        external.addView(textAction("▣  Netflix uygulamasına geç") {
            InstalledMediaApps.openNetflix(this)
        })
        external.addView(textAction("◉  SportzX bölümünü uygulama içinde aç") {
            startActivity(Intent(this,SportzXHubActivity::class.java))
        })
        external.addView(textAction("↗  Kurulu SportzX Android uygulamasını aç") {
            SportzXLauncher.openIfInstalled(this)
        })
        external.addView(textAction("📺 MediaEngine 3.2 • Telefonda aç") { MediaEngineBridge.open(this) })
        external.addView(label("EKRAN YANSITMA • SCREENONAUTO",15,true))
        external.addView(label("Kurulu ScreenOnAuto uygulamasına kendi belgelenmiş komutlarıyla istek gönderir. Gerçek araç ekranındaki video ayrıca doğrulanmalıdır.",12,false))
        val mirrorActions=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        mirrorActions.addView(button("🖥 Yansıtmayı başlat") {
            ScreenOnAutoBridge.send(this,true)
        }, LinearLayout.LayoutParams(0,dp(50),1f))
        mirrorActions.addView(button("■ Durdur",secondary=true) {
            ScreenOnAutoBridge.send(this,false)
        }, LinearLayout.LayoutParams(0,dp(50),1f))
        external.addView(mirrorActions)
        external.addView(label("SportzX sitesi dahili bölümde açılır. SportzX Android uygulamasının yayınları, ayrı uygulamada çalışır.",12,false))
        library.addView(external)

        val utilities=panel()
        utilities.addView(label("ARAÇLAR",18,true))
        utilities.addView(textAction("▤  M3U listemi yedekle") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type="application/x-mpegURL"
                putExtra(Intent.EXTRA_TITLE,"EVCOCKPIT_Media_Hub_Yedek.m3u")
            },EXPORT_PLAYLIST)
        })
        utilities.addView(textAction("▶  Telefon videosu aç") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE);type="video/*"
            },PICK_LOCAL_VIDEO)
        })
        utilities.addView(textAction("🚗 EV Cockpit / Android Auto bağlantı raporu") {
            CarCompatibility.show(this)
        })
        utilities.addView(button("🧩 Shizuku / AAAD / Fermata durumunu kontrol et") {
            CarCompanionApps.show(this)
        })
        utilities.addView(button("🧪 Ses kitaplığı API bağlantısını kontrol et") {
            CarAudioSelfTest.run(this)
        })
        utilities.addView(button("🔊 Araç ses sisteminde radyo testini başlat") {
            val station = StationStore.load(this).firstOrNull()
            if (station == null) {
                toast("Henüz kayıtlı radyo yok. Önce Radyo bölümünden kanal ekle.")
                selectTab(2)
            } else {
                playStation(station)
                selectTab(2)
                toast("Radyo başlatıldı. Sesin EV Cockpit'ten gelip gelmediğini kontrol et.")
            }
        })
        utilities.addView(textAction("⚙  Android Auto ayarları") {
            try { startActivity(Intent("android.settings.APPLICATION_DETAILS_SETTINGS",Uri.parse("package:com.google.android.projection.gearhead"))) }
            catch (_: Exception) { toast("Android Auto ayarları açılamadı") }
        })
        utilities.addView(button("🧩 TÜM UYGULAMALAR • ORTAK ENTEGRASYON MERKEZİ") {
            IntegrationCenter.show(this)
        })
        utilities.addView(label("Android Auto ekranında yalnızca ses medya kataloğu hedeflenir. Araçta video oynatma desteklenmez.",12,false))
        library.addView(utilities)

        refresh()
        refreshVideo()
        renderHome()
        selectTab(intent.getIntExtra(EXTRA_SELECT_TAB,0).coerceIn(0,3))
        val token = SessionToken(this, ComponentName(this, AudioLibraryService::class.java))
        controllerFuture = MediaController.Builder(this,token).buildAsync().also { future ->
            future.addListener({
                try {
                    val connected=future.get()
                    if(isFinishing || isDestroyed)return@addListener
                    controller=connected
                    connected.addListener(object:Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            status.text="●   Radyo hatası: ${error.errorCodeName}"
                        }
                        override fun onMediaItemTransition(
                            mediaItem: androidx.media3.common.MediaItem?,reason:Int
                        ) {
                            if(mediaItem != null)status.text="♫   ${mediaItem.mediaMetadata.title ?: "Radyo"}"
                            refreshMiniHeader()
                             if(::homeContainer.isInitialized) renderHome()
                        }
                    })
                    connected.addListener(object:Player.Listener{
                        override fun onIsPlayingChanged(isPlaying:Boolean) {
                            val selected = StationStore.load(this@MainActivity)
                                .firstOrNull{it.id==connected.currentMediaItem?.mediaId}
                            if(selected!=null)currentRadioId=selected.id
                            if(::radioDashboard.isInitialized)
                                radioDashboard.setPlaying(selected,isPlaying)
                            refreshMiniHeader()
                             if(::homeContainer.isInitialized) renderHome()
                        }
                    })
                    status.text="●   Ses servisi hazır"
                }catch (_:Exception){
                    if(!isFinishing && !isDestroyed)status.text="●   Ses servisine bağlanılamadı"
                }
            },mainExecutor)
        }
        syncRadioCatalogue(false)
    }

    /** Community-listed playlists are user-selected, not bundled broadcasts. */
    private fun showWorldPicker(byCountry: Boolean) {
        val sources = if (byCountry) WorldCatalog.countries() else WorldCatalog.genres
        val visible = sources.toMutableList()
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(6))
        }
        val input = searchInput(if (byCountry) "Ülke ara" else "Kategori ara")
        wrapper.addView(input)
        val adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_list_item_1, visible.map { it.name }.toMutableList()
        )
        val list = android.widget.ListView(this).apply { this.adapter = adapter }
        wrapper.addView(list, LinearLayout.LayoutParams(-1, dp(340)))
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (byCountry) "Ülkeye göre IPTV" else "Kategoriye göre IPTV")
            .setView(wrapper)
            .setNegativeButton("Kapat", null)
            .create()
        input.addTextChangedListener(watch {
            val filter = input.text.toString().trim()
            visible.clear()
            visible.addAll(sources.filter { it.name.contains(filter, ignoreCase = true) })
            adapter.clear()
            adapter.addAll(visible.map { it.name })
            adapter.notifyDataSetChanged()
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val chosen = visible.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            AlertDialog.Builder(this)
                .setTitle(chosen.name)
                .setMessage("İptv-org topluluk dizininden liste içe aktarılacak. Yayınların " +
                    "çalışması, kullanım hakkı ve bölgesel erişimi garanti değildir.")
                .setNegativeButton("İptal", null)
                .setPositiveButton("Listeyi yükle") { _, _ -> importFromHttps(chosen.url) }
                .show()
        }
        dialog.show()
    }

    private fun openWebMedia(url: String) {
        try {
            startActivity(Intent(this, MediaWebActivity::class.java)
                .putExtra(MediaWebActivity.EXTRA_URL, url))
        } catch (_: Exception) {
            toast("Web Medya başlatılamadı; APK kurulumunu kontrol edin.")
        }
    }

    private fun toggleRadioPlayback() {
        val ctrl=controller ?: return toast("Radyo servisi henüz hazır değil")
        if(ctrl.isPlaying) { ctrl.pause();return }
        if(ctrl.currentMediaItem!=null){ctrl.play();return}
        val first=StationStore.load(this).firstOrNull()
        if(first!=null)playStation(first) else syncRadioCatalogue(true)
    }

    private fun skipRadio(direction:Int) {
        val stations=StationStore.load(this)
        if(stations.isEmpty())return
        val index=stations.indexOfFirst{it.id==currentRadioId}
        val next=if(index<0)0 else (index+direction+stations.size)%stations.size
        playStation(stations[next])
    }

    private fun syncRadioCatalogue(force:Boolean) {
        if (radioDirectoryLoading) return
        if (!force && !RadioDirectory.shouldRefresh(this)) {
            if(::radioDashboard.isInitialized)
                radioDashboard.message("Kayıtlı Türkiye kataloğu hazır • Güncelle ile yenileyebilirsin")
            return
        }
        radioDirectoryLoading=true
        if(::radioDashboard.isInitialized)
            radioDashboard.message("Türkiye radyoları indiriliyor…")
        val context=applicationContext
        Thread {
            val result=try{
                RadioDirectory.loadAndMerge(context)
            }catch (_: Exception){
                -1
            }
            runOnUiThread {
                radioDirectoryLoading=false
                if(!isFinishing && !isDestroyed){
                    if(::radioDashboard.isInitialized){
                        radioDashboard.message(
                            if(result>=0)"Katalog güncellendi • "+result+" yeni radyo eklendi"
                            else "İnternet kataloğuna ulaşılamadı • Kayıtlı radyolar kullanılabilir"
                        )
                        radioDashboard.refresh()
                    }
                    renderHome()
                }
            }
        }.start()
    }

    private fun watch(action:()->Unit):TextWatcher=object:TextWatcher{
        override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
        override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){action()}
        override fun afterTextChanged(s:Editable?){}
    }

    private fun selectTab(index:Int){
        if (index != 1 && ::tvPlayer.isInitialized) tvPlayer.pause()
        currentTab=index
        if(::tabPages.isInitialized){
            tabPages.forEachIndexed { i,p -> p.visibility=if(i==index)View.VISIBLE else View.GONE }
        }
        if(::navButtons.isInitialized){
            navButtons.forEachIndexed { i,v ->
                val highlighted=(i==0 && index!=3)||(i==1 && index==3)
                v.setTextColor(if(highlighted)0xff68e1d5.toInt() else 0xffaabbd0.toInt())
                v.background=if(highlighted)
                    gradient(0xff214a62.toInt(),0xff14283d.toInt(),15)else null
            }
        }
        if(::contentScroll.isInitialized)contentScroll.post{contentScroll.scrollTo(0,0)}
    }

    /** EV Cockpit Drive Hub phone dashboard. Keeps existing package/storage/player services. */
    private fun renderHome() {
        if (!::homeContainer.isInitialized) return
        val channels = VideoStore.load(this)
        val stations = StationStore.load(this)
        val state = CockpitPlanner.build(RecentStore.ids(this), channels, stations)
        val activeId = controller?.currentMediaItem?.mediaId ?: currentRadioId
        val currentTitle = stations.firstOrNull { it.id == activeId }?.name
        val epg = EpgScheduleIndex(EpgGuide.load(this), System.currentTimeMillis())
        val cockpit = MediaEngineHomeDashboard.render(
            activity = this,
            overview = state,
            epg = epg,
            shortcuts = UserQuickLinks.load(this),
            onOpenShortcut = { url -> openWebMedia(url) },
            onAddShortcut = { addPersonalShortcut() },
            onRemoveShortcut = { url -> removePersonalShortcut(url) },
            actions = CockpitActions(
                onTv = {
                    startActivity(Intent(this,MediaEngineTvBrowseActivity::class.java))
                },
                onRadio = { selectTab(2) },
                onPodcast = { startActivity(Intent(this,PodcastBrowserActivity::class.java)) },
                onWeb = { openWebMedia(MediaWebActivity.YOUTUBE_URL) },
                onYoutube = { startActivity(Intent(this, MediaEngineYoutubeActivity::class.java)) },
                onDiscover = { startActivity(Intent(this,TmdbCatalogActivity::class.java)) },
                onMovies = { openLibraryHub(VideoLibraryKind.MOVIE) },
                onSeries = { openLibraryHub(VideoLibraryKind.SERIES) },
                onSports = { openLibraryHub(VideoLibraryKind.SPORT) },
                onNetflix = { openSubscriptionPortal(StreamingService.NETFLIX) },
                onPrimeVideo = { openSubscriptionPortal(StreamingService.PRIME_VIDEO) },
                onLibrary = { selectTab(3) },
                onGuide = { showEpgSchedule() },
                onCarStatus = { CarCompatibility.show(this) },
                onCompanions = { IntegrationCenter.show(this) },
                onPlay = { entry ->
                    when (entry.kind) {
                        "tv" -> channels.firstOrNull { it.id == entry.id }?.let {
                            selectTab(1)
                            tvPlayer.play(it)
                        }
                        "radio" -> stations.firstOrNull { it.id == entry.id }?.let {
                            playStation(it)
                            selectTab(2)
                        }
                    }
                },
                onRadioPauseResume = {
                    toggleRadioPlayback()
                    renderHome()
                }
            )
        )
        homeContainer.removeAllViews()
        homeContainer.addView(cockpit)
        homeContainer.addView(button("🗺 TÜRKİYE NAVİGASYON • Adres & Şarj Noktaları") {
            startActivity(Intent(this, TurkeyNavigationActivity::class.java))
        })
        homeContainer.addView(button("⚡ EV Cockpit ENERJİ MERKEZİ • Şarj ve menzil hesabı") {
            startActivity(Intent(this, EvGarageActivity::class.java))
        })
    }

    private fun refreshMiniHeader() {
        if(!::miniHeader.isInitialized)return
        val item=controller?.currentMediaItem
        val id=item?.mediaId ?: currentRadioId
        val station=StationStore.load(this).firstOrNull { it.id == id }
        miniHeader.render(station?.name,controller?.isPlaying==true)
    }

    private fun addPersonalShortcut() {
        val form=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(9),dp(16),dp(8))
        }
        val name=EditText(this).apply{hint="Kısayol adı";setSingleLine(true)}
        val url=EditText(this).apply{
            hint="https://...";setSingleLine(true)
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        form.addView(name);form.addView(url)
        AlertDialog.Builder(this).setTitle("Kısayol ekle")
            .setMessage("Yalnızca HTTPS bağlantısı kaydedilir.")
            .setView(form).setNegativeButton("Vazgeç",null)
            .setPositiveButton("Kaydet"){_,_->
                if(UserQuickLinks.add(this,name.text.toString(),url.text.toString()))
                    renderHome()
                else toast("Kısayol eklenemedi: adres geçersiz veya zaten kayıtlı.")
            }.show()
    }

    private fun removePersonalShortcut(url:String) {
        AlertDialog.Builder(this).setTitle("Kısayolu sil")
            .setMessage("Kişisel bağlantı kaldırılacak.")
            .setNegativeButton("Vazgeç",null)
            .setPositiveButton("Sil"){_,_->
                UserQuickLinks.remove(this,url)
                renderHome()
            }.show()
    }

    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if(::tabPages.isInitialized)
            selectTab(intent.getIntExtra(EXTRA_SELECT_TAB,0).coerceIn(0,3))
    }

    override fun onResume() {
        super.onResume()
        refreshMiniHeader()
        refreshMiniHeader()
                             if(::homeContainer.isInitialized) renderHome()
    }

    private fun openSubscriptionPortal(service: StreamingService) {
        startActivity(Intent(this, MediaEngineSubscriptionActivity::class.java).apply {
            putExtra(MediaEngineSubscriptionActivity.EXTRA_SERVICE, service.name)
        })
    }

    private fun openLibraryHub(kind: VideoLibraryKind) {
        startActivity(Intent(this, MediaLibraryHubActivity::class.java).apply {
            putExtra(MediaLibraryHubActivity.EXTRA_KIND, kind.name)
        })
    }

    private fun shortcut(icon:String,title:String,description:String,action:()->Unit):LinearLayout{
        val view=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER
            setPadding(dp(5),dp(8),dp(5),dp(8))
            background=gradient(0xff203853.toInt(),0xff162a43.toInt(),17)
            val lp=LinearLayout.LayoutParams(-1,-1)
            lp.setMargins(dp(3),dp(3),dp(3),dp(3))
            layoutParams=lp
            setOnClickListener{action()}
        }
        view.addView(label(icon,26,true).apply{setTextColor(0xff80e6dd.toInt())})
        view.addView(label(title,12,true))
        view.addView(label(description,10,false))
        return view
    }

    private fun section(title:String,subtitle:String):LinearLayout=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL
        setPadding(dp(4),dp(15),dp(4),dp(9))
        addView(label(title,19,true))
        addView(label(subtitle,12,false))
    }

    private fun searchInput(hintText:String)=EditText(this).apply{
        hint=hintText
        setSingleLine(true)
        textSize=15f
        setTextColor(Color.WHITE)
        setHintTextColor(0xff8298b2.toInt())
        background=gradient(0xff223750.toInt(),0xff1b2c42.toInt(),13)
        setPadding(dp(14),dp(13),dp(14),dp(13))
        val lp=LinearLayout.LayoutParams(-1,dp(50))
        lp.setMargins(0,dp(6),0,dp(12))
        layoutParams=lp
    }

    private fun textAction(value:String,action:()->Unit):TextView=label(value,14,true).apply{
        gravity=Gravity.CENTER_VERTICAL
        setPadding(dp(14),dp(12),dp(12),dp(12))
        setOnClickListener{action()}
    }

    private fun gradient(start:Int,end:Int,radius:Int):GradientDrawable=
        GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(start,end)).apply{
            cornerRadius=dp(radius).toFloat()
        }

    private fun importEpgFromHttps(address: String) {
        if (!M3uParser.isHttpsUrl(address)) {
            toast("EPG için geçerli HTTPS XMLTV adresi gir.")
            return
        }
        status.text = "Program rehberi indiriliyor…"
        Thread {
            try {
                val bytes = EpgGuide.downloadHttps(address)
                val parsed = EpgGuide.parse(bytes)
                require(parsed.isNotEmpty()) { "Kullanılabilir program bulunamadı" }
                val count = EpgGuide.save(applicationContext, address, parsed)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        status.text = "EPG rehberi hazır: " + count + " program"
                        if (::tvPlayer.isInitialized) tvPlayer.refresh()
                        toast("TV program rehberi güncellendi.")
                    }
                }
            } catch (_: Exception) {
                // The URL might contain an IPTV account token: don't log or display exceptions.
                runOnUiThread {
                    if (!isFinishing && !isDestroyed)
                        status.text = "EPG yüklenemedi. HTTPS adresini, XMLTV biçimini ve erişim iznini kontrol et."
                }
            }
        }.start()
    }

    private fun showEpgSchedule() {
        val guide = EpgGuide.load(this)
        val now = System.currentTimeMillis()
        val channels = VideoStore.load(this)
        val rows = channels.asSequence().mapNotNull { channel ->
            val programme = EpgGuide.current(guide, channel.tvgId, channel.name, now)
                ?: return@mapNotNull null
            channel to programme
        }.take(35).toList()
        val text = if (rows.isEmpty()) {
            "Eşleşen güncel program bulunamadı.\n\nÖnce XMLTV rehberini yükle. " +
                "M3U kanallarında tvg-id alanları ile XMLTV channel id alanlarının aynı olması gerekir."
        } else buildString {
            append("Güncel programı bulunan ").append(rows.size).append(" kanal\n\n")
            rows.forEach { (channel, item) ->
                append(channel.name).append("\n")
                append(EpgGuide.clock(item.startMs)).append("–")
                    .append(EpgGuide.clock(item.stopMs)).append("  ")
                    .append(item.title).append("\n\n")
            }
        }
        AlertDialog.Builder(this).setTitle("TV Program Rehberi")
            .setMessage(text).setPositiveButton("Kapat", null).show()
    }

    private fun importFromHttps(address: String) {
        if (!M3uParser.isHttpsUrl(address)) {
            toast("HTTPS ile başlayan geçerli bir M3U bağlantısı gir.")
            return
        }
        status.text = "M3U listesi alınıyor…"
        Thread {
            try {
                val text = PlaylistLoader.fromHttps(address)
                runOnUiThread { if (!isFinishing && !isDestroyed) importPlaylist(text) }
            } catch (_: Exception) {
                // Network exceptions can contain M3U links with subscriber credentials.
                runOnUiThread { if (!isFinishing && !isDestroyed)
                    status.text = "M3U listesi alınamadı. Adresi, interneti veya sunucunun erişim izinlerini kontrol et."
                }
            }
        }.start()
    }

    private fun playStation(station: Station) {
        val ctrl = controller ?: return toast("Medya hizmeti henüz hazır değil")
        // Send only the selected media ID across Binder, not the entire M3U library.
        // MediaLibrarySession resolves it and builds a bounded next/previous queue.
        ctrl.setMediaItem(station.toMediaItem())
        ctrl.prepare()
        ctrl.play()
        currentRadioId = station.id
        if (::radioDashboard.isInitialized) radioDashboard.setPlaying(station, true)
        RecentStore.remember(this,station.id,"radio")
        renderHome()
        status.text = "♫  ${station.name}"
    }

    private fun radioRow(station:Station,compact:Boolean=false):LinearLayout {
        return mediaCard(station.name, station.group,station.logo,station.favorite,false,{
            playStation(station)
        },{
            StationStore.toggleFavorite(this,station.id);refresh();renderHome()
        },{
            val menu=PopupMenu(this,it)
            menu.menu.add("Bağlantıyı kontrol et").setOnMenuItemClickListener{
                checkStream(station.name,station.url);true
            }
            menu.menu.add("TV listesine taşı").setOnMenuItemClickListener{
                if(VideoStore.add(this,station.name,station.url,station.group)){
                    StationStore.remove(this,station.id);refresh();refreshVideo();renderHome()
                }else toast("Kanal taşınamadı")
                true
            }
            menu.menu.add("Listeden kaldır").setOnMenuItemClickListener{
                StationStore.remove(this,station.id);refresh();renderHome();true
            }
            menu.show()
        },compact)
    }

    private fun videoRow(channel:VideoChannel,compact:Boolean=false):LinearLayout =
        mediaCard(channel.name,channel.group,channel.logo,channel.favorite,true,{
            if (::tvPlayer.isInitialized) {
                selectTab(1)
                tvPlayer.play(channel)
            } else toast("TV oynatıcı hazırlanıyor")
        },{
            VideoStore.toggleFavorite(this,channel.id);refreshVideo();renderHome()
        },{
            val menu=PopupMenu(this,it)
            menu.menu.add("Bağlantıyı kontrol et").setOnMenuItemClickListener{
                checkStream(channel.name,channel.url);true
            }
            menu.menu.add("Radyo listesine taşı").setOnMenuItemClickListener{
                if(StationStore.add(this,channel.name,channel.url,channel.group)){
                    VideoStore.remove(this,channel.id);refreshVideo();refresh();renderHome()
                }else toast("Kanal taşınamadı")
                true
            }
            menu.menu.add("Listeden kaldır").setOnMenuItemClickListener{
                VideoStore.remove(this,channel.id);refreshVideo();renderHome();true
            }
            menu.show()
        },compact)

    private fun mediaCard(
        title:String,category:String,logo:String,favorite:Boolean,video:Boolean,
        open:()->Unit,toggle:()->Unit,menu:(View)->Unit,compact:Boolean=false
    ):LinearLayout {
        val card=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(10),dp(8),dp(6),dp(8))
            background=gradient(0xff1b304c.toInt(),0xff15263b.toInt(),15)
            val lp=LinearLayout.LayoutParams(-1,dp(if(compact)76 else 88))
            lp.setMargins(0,dp(4),0,dp(4))
            layoutParams=lp
            setOnClickListener{open()}
        }
        val mark=FrameLayout(this).apply{
            background=gradient(0xff326381.toInt(),0xff29405b.toInt(),12)
        }
        val initials=title.split(' ').filter{it.isNotBlank()}.take(2).joinToString(""){it.take(1)}.uppercase().take(2)
        mark.addView(label(initials.ifBlank{if(video)"TV" else "♫"},18,true).apply{
            gravity=Gravity.CENTER
            setTextColor(0xffc3fcf0.toInt())
            setPadding(0,0,0,0)
        },FrameLayout.LayoutParams(-1,-1))
        if(logo.isNotBlank()){
            val image=ImageView(this).apply{
                scaleType=ImageView.ScaleType.FIT_CENTER
                alpha=0f
                setPadding(dp(4),dp(4),dp(4),dp(4))
            }
            mark.addView(image,FrameLayout.LayoutParams(-1,-1))
            ChannelLogoLoader.into(logo,image,this)
        }
        card.addView(mark,LinearLayout.LayoutParams(dp(56),dp(56)))
        val texts=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(12),0,dp(4),0)
        }
        texts.addView(label(title,15,true).apply{
            maxLines=2
            ellipsize=android.text.TextUtils.TruncateAt.END
            setPadding(0,0,0,0)
        })
        texts.addView(label((if(video)"●  CANLI TV  •  " else "♫  RADYO  •  ")+category,11,false).apply{
            maxLines=1
            ellipsize=android.text.TextUtils.TruncateAt.END
            setPadding(0,dp(5),0,0)
        })
        card.addView(texts,LinearLayout.LayoutParams(0,-1,1f))
        val star=textAction(if(favorite)"★" else "☆"){toggle()}.apply{
            gravity=Gravity.CENTER
            textSize=22f
            setTextColor(if(favorite)0xffffd17c.toInt() else 0xff9fb3c9.toInt())
        }
        card.addView(star,LinearLayout.LayoutParams(dp(40),dp(50)))
        val more=textAction("⋮"){}.apply{
            gravity=Gravity.CENTER
            textSize=26f
            setTextColor(0xffb8c9df.toInt())
            setOnClickListener{menu(this)}
        }
        card.addView(more,LinearLayout.LayoutParams(dp(26),dp(50)))
        return card
    }

    private fun refresh() {
        if (::radioDashboard.isInitialized) radioDashboard.refresh()
    }

    private fun importPlaylist(source: String) {
        val entries = M3uParser.parse(source)
        if (entries.isEmpty()) {
            status.text = "Bu dosyada geçerli HTTPS M3U kanalı bulunamadı."
            return
        }
        val audio = StationStore.addAll(this, entries)
        val video = VideoStore.addAll(this, entries)
        status.text = "M3U alındı: $audio yeni radyo, $video yeni video kanalı."
        refresh()
        refreshVideo()
        renderHome()
        val proposedGuide = M3uParser.suggestedGuideUrl(source)
        if (proposedGuide != null && proposedGuide != EpgGuide.source(this)) {
            AlertDialog.Builder(this)
                .setTitle("M3U program rehberi bulundu")
                .setMessage("Kanal listesi XMLTV program rehberi de öneriyor. " +
                    "Rehberi ayrıca HTTPS üzerinden indirmek ister misin? " +
                    "Kaynak adresi paylaşılmayacak ve yalnızca onay verirsen indirilecek.")
                .setNegativeButton("Şimdi değil",null)
                .setPositiveButton("EPG rehberini yükle") { _, _ ->
                    epgUrlField?.setText(proposedGuide)
                    importEpgFromHttps(proposedGuide)
                }
                .show()
        }
    }

    private fun refreshVideo() {
        if (::tvPlayer.isInitialized) tvPlayer.refresh()
    }

    /** Tests only HTTP reachability, off the UI thread. A successful HEAD is not playable-video proof. */
    private fun checkStream(name: String, url: String) {
        status.text = "${name.take(50)}: bağlantı kontrol ediliyor…"
        Thread {
            val result = StreamProbe.check(url)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    status.text = "${name.take(50)}: ${result.description()}"
                    toast(result.description())
                }
            }
        }.start()
    }

    private fun addPageButtons(container: LinearLayout, page: Int, maxPage: Int, go: (Int) -> Unit) {
        if (maxPage == 0) return
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val previous = button("◀ Önceki") { go(page - 1) }.apply { isEnabled = page > 0 }
        val next = button("Sonraki ▶") { go(page + 1) }.apply { isEnabled = page < maxPage }
        nav.addView(previous, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(next, LinearLayout.LayoutParams(0, -2, 1f))
        container.addView(nav)
    }

    @Deprecated("Android framework Activity file-picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            PICK_PLAYLIST -> {
                status.text = "Dosya okunuyor…"
                Thread {
                    try {
                        val playlist = contentResolver.openInputStream(uri)?.use { PlaylistLoader.readLimited(it) }
                            ?: error("Dosya okunamadı")
                        runOnUiThread { if (!isFinishing && !isDestroyed) importPlaylist(playlist) }
                    } catch (_: Exception) {
                        runOnUiThread { if (!isFinishing && !isDestroyed)
                            status.text = "M3U dosyası okunamadı. Dosyayı ve biçimini kontrol et."
                        }
                    }
                }.start()
            }
            EXPORT_PLAYLIST -> {
                val entries = StationStore.load(this).map { PlaylistEntry(it.name, it.url, it.group, true) } +
                    VideoStore.load(this).map { PlaylistEntry(it.name, it.url, it.group, false, it.logo, it.tvgId) }
                val contents = M3uExporter.export(entries)
                Thread {
                    try {
                        contentResolver.openOutputStream(uri, "w")?.use {
                            it.write(contents.toByteArray(Charsets.UTF_8))
                        } ?: error("Yedek dosyası açılamadı")
                        runOnUiThread { if (!isFinishing && !isDestroyed) toast("M3U yedeği kaydedildi.") }
                    } catch (_: Exception) {
                        runOnUiThread { if (!isFinishing && !isDestroyed) toast("Yedekleme başarısız oldu.") }
                    }
                }.start()
            }
            PICK_LOCAL_VIDEO -> {
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    this.data = uri
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(PlayerActivity.EXTRA_TITLE, "Yerel video")
                })
            }
        }
    }

    companion object {
        const val EXTRA_SELECT_TAB="mediaengine_select_tab"
        private const val PICK_PLAYLIST = 701
        private const val PICK_LOCAL_VIDEO = 702
        private const val EXPORT_PLAYLIST = 703
        private const val PAGE_SIZE = 20
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.rgb(23, 35, 55))
            cornerRadius = dp(14).toFloat()
        }
        val p = LinearLayout.LayoutParams(-1, -2)
        p.setMargins(0, dp(6), 0, dp(6))
        layoutParams = p
    }
    private fun button(title: String, secondary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = title
        textSize = 13f
        setTextColor(Color.WHITE)
        setAllCaps(false)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(if(secondary)0xff29435e.toInt() else 0xff126b91.toInt())
            cornerRadius = dp(12).toFloat()
        }
        setPadding(dp(8), dp(8), dp(8), dp(8))
        minHeight = dp(44)
        setOnClickListener { action() }
    }
    private fun label(value: String, size: Int, strong: Boolean) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(if (strong) Color.WHITE else Color.rgb(175, 197, 220))
        setPadding(dp(4), dp(10), dp(4), dp(10))
        if (strong) setTypeface(null, android.graphics.Typeface.BOLD)
    }
    private fun styleInputs(root: View) {
        if (root is EditText) {
            root.setTextColor(Color.WHITE)
            root.setHintTextColor(Color.rgb(156, 177, 202))
            root.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(78, 131, 190))
        }
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) styleInputs(root.getChildAt(i))
        }
    }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()

    override fun onStop() {
        // Phone activity not visible: do not keep hidden video decoding or retrying.
        if (::tvPlayer.isInitialized) tvPlayer.pause()
        super.onStop()
    }

    override fun onDestroy() {
        if (::tvPlayer.isInitialized) tvPlayer.release()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}
