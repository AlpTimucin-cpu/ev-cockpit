package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.util.Locale

/**
 * In-app Turkey-wide OpenStreetMap address search and EV point-of-interest map.
 *
 * Phase-1 navigator: selected destinations are handed off to the installed
 * turn-by-turn map app. This is NOT a live turn-by-turn engine or a VW
 * infotainment/navigation feature unlock. The app makes no location request.
 * Charging points are voluntarily requested from OSM in the small visible box,
 * not all licensed providers or guaranteed live price/availability information.
 */
class TurkeyNavigationActivity : Activity() {
    private val screenColor=0xff081724.toInt()
    private val accent=0xff70e2d0.toInt()
    private val pale=0xffa7bccb.toInt()
    private var map: MapLibreMap? = null
    private lateinit var mapView: MapView
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var selectedLabel: TextView
    private var selected: TurkeyGeoServices.Place? = null
    private var stations: List<TurkeyGeoServices.Station> = emptyList()
    private val drawn=ArrayList<Marker>()
    private val drawHandler=Handler(Looper.getMainLooper())
    private var drawVersion=0
    private val maxVisibleMarkers=65
    private var chargingFilter=ChargingStationDiscovery.Filter()
    private var latestOfficial=emptyMap<String,EpdkPublicStations.Entry>()

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=screenColor
        window.navigationBarColor=screenColor
        MapLibre.getInstance(this)
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(screenColor)
        }
        root.setOnApplyWindowInsetsListener { view,insets ->
            if(android.os.Build.VERSION.SDK_INT>=30) {
                val system=insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0,system.top,0,system.bottom)
            }
            insets
        }
        setContentView(root)
        val header=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(9),dp(4),dp(9),dp(4))
        }
        header.addView(button("‹"){finish()},LinearLayout.LayoutParams(dp(48),dp(48)))
        header.addView(text("EV Cockpit • TÜRKİYE NAVİGASYON",18,true),
            LinearLayout.LayoutParams(0,-2,1f))
        root.addView(header)
        root.addView(text("Adres bul • Şarj noktaları • Yol tarifi",12,false,pale))
        val lookup=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            setPadding(dp(8),dp(4),dp(8),dp(4))
        }
        search=EditText(this).apply{
            hint="İl, ilçe, sokak, adres veya işletme ara"
            setTextColor(Color.WHITE)
            setHintTextColor(pale)
            textSize=13f
            setSingleLine(true)
            imeOptions=EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(12),dp(4),dp(6),dp(4))
            background=shape(0xff213649.toInt())
            setOnEditorActionListener{_,_,_->lookupAddress();true}
        }
        lookup.addView(search,LinearLayout.LayoutParams(0,dp(51),1f))
        lookup.addView(button("Ara"){lookupAddress()},
            LinearLayout.LayoutParams(dp(62),dp(51)).apply{leftMargin=dp(7)})
        root.addView(lookup)
        mapView=MapView(this)
        root.addView(mapView,LinearLayout.LayoutParams(-1,0,1f))
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync{ ready ->
            map=ready
            ready.setStyle(TurkeyGeoServices.MAP_STYLE) {
                ready.cameraPosition=CameraPosition.Builder()
                    .target(LatLng(39.00,35.00)).zoom(5.4).build()
                redraw()
            }
            ready.addOnMapLongClickListener { point ->
                selected=TurkeyGeoServices.Place(
                    "Haritada seçilen nokta",point.latitude,point.longitude)
                selectedLabel.text="Hedef: "+selected!!.coordinates()
                redraw()
                true
            }
            ready.setOnMarkerClickListener { marker ->
                val match=stations.firstOrNull{
                    kotlin.math.abs(it.lat-marker.position.latitude)<0.000001 &&
                    kotlin.math.abs(it.lon-marker.position.longitude)<0.000001
                }
                if(match!=null)showStation(match) else {
                    selected?.let { place ->
                        AlertDialog.Builder(this).setTitle(place.title)
                            .setMessage(place.coordinates())
                            .setPositiveButton("Yol tarifi"){_,_->navigate(place)}
                            .setNegativeButton("Kapat",null).show()
                    }
                }
                true
            }
        }
        val controls=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            setPadding(dp(6),dp(6),dp(6),dp(6))
        }
        controls.addView(button("⚡ Şarj noktaları"){loadStations()},
            LinearLayout.LayoutParams(0,dp(54),1f))
        controls.addView(button("⚙ Filtre"){showChargingFilters()},
            LinearLayout.LayoutParams(0,dp(54),.75f).apply{leftMargin=dp(4)})
        controls.addView(button("☷ Liste"){listStations()},
            LinearLayout.LayoutParams(0,dp(54),.65f).apply{leftMargin=dp(4)})
        controls.addView(button("↗ Rota"){selected?.let(::navigate) ?: alert(
            "Hedef seç","Adres ara, şarj noktası seç veya haritaya uzun bas.")},
            LinearLayout.LayoutParams(0,dp(54),.65f).apply{leftMargin=dp(4)})
        root.addView(controls)
        selectedLabel=text("Henüz hedef seçilmedi. Haritaya uzun basarak hedef koyabilirsin.",
            12,false,pale)
        root.addView(selectedLabel)
        status=text("Harita: © OpenStreetMap katkıcıları • OpenFreeMap. " +
            "Şarj noktaları OSM topluluk verisidir.",11,false,pale)
        root.addView(status)
        root.addView(button("＋ Open Charge Map kaynağı (isteğe bağlı API anahtarı)"){
            configureOcmKey()
        },LinearLayout.LayoutParams(-1,dp(48)))
        root.addView(button("₺ Operatörlerin günlük resmî fiyatlarını karşılaştır") {
            OfficialChargingTariffs.show(this)
        },LinearLayout.LayoutParams(-1,dp(50)))
        root.addView(button("Şarj Haritası • Karşılaştırma ve canlı servisler") {
            OfficialChargingTariffs.officialBrowser(this,"https://www.sarjharitasi.tr/")
        },LinearLayout.LayoutParams(-1,dp(48)))
        root.addView(button("EPDK • Türkiye resmî şarj istasyonu listesini aç") {
            openWeb(TurkeyGeoServices.EPDK_STATIONS)
        },LinearLayout.LayoutParams(-1,dp(48)))
        root.addView(button("EPDK Şarj@TR • Resmî istasyon, anlık müsaitlik ve fiyatlar"){
            openWeb(TurkeyGeoServices.EPDK_PUBLIC)
        },LinearLayout.LayoutParams(-1,dp(50)))
    }

    private fun lookupAddress(){
        val term=search.text.toString().trim()
        if(term.length<3){alert("Adres eksik","Türkiye'de aramak için en az 3 karakter gir.");return}
        status.text="Adres aranıyor…"
        Thread{
            val found=try{TurkeyGeoServices.searchAddress(term)}
            catch(e:Exception){null}
            runOnUiThread{
                if(isFinishing||isDestroyed)return@runOnUiThread
                if(found==null){status.text="Adres servisine bağlanılamadı veya arama hız sınırına ulaşıldı.";return@runOnUiThread}
                if(found.isEmpty()){status.text="Bu ifadeyle eşleşen adres bulunamadı.";return@runOnUiThread}
                status.text=found.size.toString()+" adres bulundu."
                AlertDialog.Builder(this).setTitle("Türkiye adres sonuçları")
                    .setItems(found.map{it.title}.toTypedArray()){_,i->
                        val place=found[i]
                        selected=place
                        selectedLabel.text=place.title
                        redraw()
                        map?.cameraPosition=CameraPosition.Builder()
                            .target(LatLng(place.lat,place.lon)).zoom(15.0).build()
                        stations=emptyList()
                        redraw()
                    }
                    .setNegativeButton("İptal",null).show()
            }
        }.start()
    }

    private fun loadStations(){
        val m=map?:run{alert("Harita hazırlanıyor","Harita verisinin yüklenmesini kontrol et.");return}
        val bounds=m.projection.visibleRegion.latLngBounds
        val bbox=TurkeyGeoServices.Bounds(
            bounds.latitudeSouth,bounds.longitudeWest,
            bounds.latitudeNorth,bounds.longitudeEast)
        if(!bbox.isSmallEnough()){
            alert("Haritayı yakınlaştır",
                "Hızlı ve doğru sorgu için ilçe veya semt düzeyine yakınlaş. " +
                "Tüm Türkiye'yi tek seferde taramak ortak OSM sunucularına uygun değil.")
            return
        }
        status.text="EPDK resmî halka açık şarj noktaları aranıyor…"
        Thread{
            // Fetch the entire already-normalized official public snapshot,
            // then filter OFFICIAL SOCKETS; do not infer live occupancy.
            val snapshot=try{EpdkPublicStations.load(applicationContext)
                .takeIf{it.fresh()}}catch(_:Exception){null}
            val officialEntries=snapshot?.stations.orEmpty().asSequence()
                .filter{it.lat in bbox.south..bbox.north && it.lon in bbox.west..bbox.east}
                .toList()
            val matching=ChargingStationDiscovery.filter(officialEntries,chargingFilter)
                .take(180)
            val epdk=if(snapshot!=null) matching.map{it.asPoint()} else null
            val osm=try{TurkeyGeoServices.nearbyStations(bbox)}catch(_:Exception){null}
            val ocm=if(OpenChargeMapSources.hasKey(applicationContext)) {
                try {
                    val lat=(bbox.south+bbox.north)/2.0
                    val lon=(bbox.west+bbox.east)/2.0
                    OpenChargeMapSources.nearby(applicationContext,lat,lon)
                        .filter{
                            it.lat in bbox.south..bbox.north &&
                            it.lon in bbox.west..bbox.east
                        }
                }catch(_:Exception){null}
            }else emptyList()
            val filtered=chargingFilter.operator.isNotBlank() ||
                chargingFilter.text.isNotBlank() ||
                chargingFilter.connector!=ChargingStationDiscovery.Connector.ALL
            // Only EPDK records have verified socket and licensing metadata.
            // With advanced filters, do not mix in weaker community claims.
            val community=if(filtered) emptyList()
                else OpenChargeMapSources.merge(osm.orEmpty(),ocm.orEmpty())
            val found=OpenChargeMapSources.merge(epdk.orEmpty(),community).take(200)
            runOnUiThread{
                if(isFinishing||isDestroyed)return@runOnUiThread
                if(epdk==null && osm==null && ocm==null){
                    status.text="EPDK ve topluluk şarj veri servislerine ulaşılamadı."
                    return@runOnUiThread
                }
                stations=found
                latestOfficial=matching.associateBy{it.stationId}
                redraw()
                val official=epdk?.size ?: 0
                status.text="$official resmî EPDK + "+
                    (found.size-official).coerceAtLeast(0)+" topluluk noktası. "+
                    (if(found.size>maxVisibleMarkers) "Donmayı önlemek için haritada ilk $maxVisibleMarkers nokta çizilir; tamamı Liste'de. " else "")+
                    "Canlı doluluk/fiyat doğrulanmıyor."
            }
        }.start()
    }

    private fun redraw(){
        val m=map?:return
        // Adding/removing hundreds of legacy MapLibre markers in one UI frame
        // can make Android show the "app isn't responding" (ANR) dialog.
        // Render a controlled number in small message-loop batches.
        val generation=++drawVersion
        val previous=drawn.toList()
        drawn.clear()
        val targets=buildList {
            selected?.let {
                add(MarkerOptions().position(LatLng(it.lat,it.lon))
                    .title("Hedef • "+it.title.take(80)))
            }
            stations.take(maxVisibleMarkers).forEach {
                add(MarkerOptions().position(LatLng(it.lat,it.lon))
                    .title("⚡ "+it.title.take(80))
                    .snippet(it.operator.take(80)))
            }
        }
        var removed=0
        fun addBatch(index:Int) {
            if(generation!=drawVersion || isFinishing || isDestroyed)return
            for(i in index until minOf(index+3,targets.size)){
                try {drawn.add(m.addMarker(targets[i]))} catch (_:Exception){}
            }
            if(index+3<targets.size){
                drawHandler.postDelayed({addBatch(index+3)},45L)
            }
        }
        fun removeBatch() {
            if(generation!=drawVersion || isFinishing || isDestroyed)return
            val end=minOf(removed+5,previous.size)
            while(removed<end){
                try {m.removeMarker(previous[removed])}catch(_:Exception){}
                removed++
            }
            if(removed<previous.size)drawHandler.postDelayed({removeBatch()},30L)
            else addBatch(0)
        }
        removeBatch()
    }

    private fun configureOcmKey(){
        val input=EditText(this).apply{
            hint="Open Charge Map API anahtarı"
            isSingleLine=true
            textSize=14f
            inputType=android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val form=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(15),dp(9),dp(15),dp(9))
        }
        form.addView(text("API anahtarını openchargemap.org hesabından ücretsiz " +
            "oluşturabilirsin. Anahtar cihazda şifreli saklanır ve yalnızca " +
            "HTTPS X-API-Key başlığıyla sağlayıcıya gönderilir.",12,false,pale))
        form.addView(input)
        AlertDialog.Builder(this)
            .setTitle("Open Charge Map • "+if(OpenChargeMapSources.hasKey(this))
                "Anahtar kayıtlı" else "Anahtar ekle")
            .setView(form)
            .setNegativeButton("Kapat",null)
            .setNeutralButton("Anahtarımı sil"){_,_->
                OpenChargeMapSources.clearKey(this)
                status.text="Open Charge Map anahtarı silindi."
            }
            .setPositiveButton("Kaydet"){_,_->
                if(OpenChargeMapSources.saveKey(this,input.text.toString()))
                    status.text="OCM anahtarı kaydedildi. Şarj noktalarını yenileyebilirsin."
                else alert("Anahtar kaydedilemedi",
                    "En az 10 karakterli, boşluksuz geçerli bir anahtar gir.")
                input.text.clear()
            }.show()
    }

    private fun showChargingFilters(){
        val labels=arrayOf("Tümü","CCS DC","Type 2 AC","CHAdeMO","Diğer")
        val opts=arrayOf(
            ChargingStationDiscovery.Connector.ALL,
            ChargingStationDiscovery.Connector.CCS,
            ChargingStationDiscovery.Connector.TYPE2,
            ChargingStationDiscovery.Connector.CHADEMO,
            ChargingStationDiscovery.Connector.OTHER)
        val form=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(8),dp(18),dp(8))
        }
        val op=EditText(this).apply{
            hint="Operatör adı (isteğe bağlı)"
            isSingleLine=true
            setText(chargingFilter.operator)
        }
        val query=EditText(this).apply{
            hint="İl / ilçe / adres / istasyon adı"
            isSingleLine=true
            setText(chargingFilter.text)
        }
        val connector=Spinner(this).apply{
            adapter=ArrayAdapter(this@TurkeyNavigationActivity,
                android.R.layout.simple_spinner_dropdown_item,labels)
            setSelection(opts.indexOf(chargingFilter.connector).coerceAtLeast(0))
        }
        form.addView(text("EPDK resmî istasyonlarına göre filtrele",13,true))
        form.addView(op)
        form.addView(query)
        form.addView(connector)
        form.addView(text("Anlık müsaitlik filtresi henüz yok: yetkili canlı " +
            "servis bağlanmadan 'müsait' iddia edilmeyecek.",12,false,pale))
        AlertDialog.Builder(this).setTitle("Şarj@TR tarzı filtreler")
            .setView(form)
            .setNegativeButton("Vazgeç",null)
            .setNeutralButton("Temizle"){_,_->
                chargingFilter=ChargingStationDiscovery.Filter()
                loadStations()
            }
            .setPositiveButton("Uygula"){_,_->
                chargingFilter=ChargingStationDiscovery.Filter(
                    operator=op.text.toString().trim().take(100),
                    text=query.text.toString().trim().take(120),
                    connector=opts[connector.selectedItemPosition])
                loadStations()
            }.show()
    }

    private fun listStations(){
        if(stations.isEmpty()){
            alert("Henüz şarj listesi yok",
                "Haritayı semt düzeyine yakınlaştırıp 'Şarj noktaları' düğmesine dokun.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Görünen alandaki "+stations.size+" şarj noktası")
            .setItems(stations.map{it.title+" • "+it.operator}.toTypedArray()){_,i->
                showStation(stations[i])
            }
            .setNegativeButton("Kapat",null).show()
    }

    private fun showStation(station: TurkeyGeoServices.Station){
        val entry=station.osmId.removePrefix("epdk:").takeIf{
            station.osmId.startsWith("epdk:")}?.let{latestOfficial[it]}
        val officialDetails=entry?.let{ChargingStationDiscovery.socketDetails(it)}
            ?: station.detail()
        AlertDialog.Builder(this).setTitle("⚡ "+station.title)
            .setMessage(officialDetails+"\n\n" +
                "Soketin anlık müsaitliği ve bu istasyondaki geçerli fiyat " +
                "yetkili operatör API'sinden henüz doğrulanamadı. " +
                "Bilinmiyor olarak kabul et.")
            .setNegativeButton("Kapat",null)
            .setNeutralButton("Haritada göster"){_,_->
                selected=station.asPlace()
                selectedLabel.text="⚡ "+station.title
                map?.cameraPosition=CameraPosition.Builder()
                    .target(LatLng(station.lat,station.lon)).zoom(15.5).build()
                redraw()
            }
            .setPositiveButton("Yol tarifi"){_,_->navigate(station.asPlace())}
            .show()
    }

    private fun navigate(place:TurkeyGeoServices.Place){
        // An established, installed turn-by-turn navigation engine handles
        // rerouting, voice instructions, road closures and traffic.
        val coords=place.coordinates()
        val appIntent=Intent(Intent.ACTION_VIEW,Uri.parse(
            "google.navigation:q="+coords+"&mode=d")).apply{
            setPackage("com.google.android.apps.maps")
        }
        try{startActivity(appIntent)}
        catch(_:Exception){
            val fallback=Uri.parse(
                "https://www.google.com/maps/dir/?api=1&destination="+coords+"&travelmode=driving")
            try{startActivity(Intent(Intent.ACTION_VIEW,fallback))}
            catch(_:Exception){alert("Navigasyon açılamadı",
                "Google Maps veya bir navigasyon uygulaması kurulu değil.")}
        }
    }

    private fun openWeb(url:String){
        try{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}
        catch(_:Exception){alert("Bağlantı açılamadı","Telefonunda tarayıcı bulunamadı.")}
    }

    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun shape(color:Int)=GradientDrawable().apply{
        setColor(color);cornerRadius=dp(11).toFloat()
    }
    private fun text(content:String,size:Int,strong:Boolean,color:Int=Color.WHITE)=
        TextView(this).apply{
            text=content
            textSize=size.toFloat()
            setTextColor(color)
            if(strong)setTypeface(typeface,Typeface.BOLD)
            setPadding(dp(11),dp(7),dp(11),dp(7))
            maxLines=3
        }
    private fun button(value:String,click:()->Unit)=Button(this).apply{
        text=value;textSize=12f;isAllCaps=false
        setTextColor(Color.WHITE)
        background=shape(0xff24556d.toInt())
        setOnClickListener{click()}
    }
    private fun alert(title:String,description:String){
        AlertDialog.Builder(this).setTitle(title).setMessage(description)
            .setPositiveButton("Tamam",null).show()
    }

    override fun onStart(){super.onStart();mapView.onStart()}
    override fun onResume(){super.onResume();mapView.onResume()}
    override fun onPause(){mapView.onPause();super.onPause()}
    override fun onStop(){mapView.onStop();super.onStop()}
    override fun onDestroy(){drawVersion++;drawHandler.removeCallbacksAndMessages(null);mapView.onDestroy();super.onDestroy()}
    override fun onLowMemory(){super.onLowMemory();mapView.onLowMemory()}
    override fun onSaveInstanceState(out:Bundle){
        super.onSaveInstanceState(out)
        mapView.onSaveInstanceState(out)
    }
}
