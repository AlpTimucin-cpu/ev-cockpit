package me.aap.fermata.ev;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;

import android.view.View;
import java.util.Collections;
import java.util.Comparator;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.webkit.WebViewAssetLoader;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/**
 * MediaEngine's opt-in EV Cockpit hub. Completely independent from MediaEngine playback/session
 * components: no TV, IPTV, Web addon, MediaProjection or navigation rewrites.
 *
 * Runs network+JSON work in a single background executor and limits map pins
 * to 55 per viewport. Never assumes BMS/remote access to an EV Cockpit vehicle.
 */
public final class EvCockpitEvActivity extends Activity {
    private static final String DATA =
            "https://raw.githubusercontent.com/AlpTimucin-cpu/ID4-Media-Hub/" +
            "feature/media-engine-migration/data/charging/epdk_public_stations.json.gz";
    private static final String PRICES =
            "https://raw.githubusercontent.com/AlpTimucin-cpu/ID4-Media-Hub/" +
            "feature/media-engine-migration/data/charging/operator_prices.json";
    private static final String MAP =
            "https://appassets.androidplatform.net/assets/evcockpit/index.html";
    private static final String IDENTIFIER="EV-Cockpit/0.3";
    private static final int MAIN=0xff091e2a;
    private static final int AQUA=0xff66e1c9;
    private static final long THREE_DAYS=72L*60*60*1000;
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private LinearLayout root;
    private TextView status;
    private WebView map;
    private EditText address;
    private JSONArray records;
    private String verifiedAt="";
    private boolean mapReady;
    private boolean stopped;
    private int generation;
    private int energyPage=-1;
    private TextView autoEnergyFacts,autoEnergyModel,autoEnergyPrice;
    private EditText stationQuery;
    private LinearLayout stationCards,stationPaging;
    private ScrollView stationScroll;
    private TextView stationInfo;
    private Button typeButton,radiusButton;
    private Location stationLocation;
    private JSONObject priceCatalog;
    private int typeFilter=0,radiusFilter=0,stationPage=0;
    private static final int PAGE_SIZE=25;
    private static final int[] RADII={0,10,25,50,100};
    private final Handler searchHandler=new Handler(Looper.getMainLooper());
    private final Runnable searchNow=this::findStations;
    private final ArrayList<StationHit> stationMatches=new ArrayList<>();
    private static final class StationHit {
        final JSONObject data;
        final double km;
        final boolean ac,dc;
        StationHit(JSONObject s,double distance,boolean a,boolean d){
            data=s;km=distance;ac=a;dc=d;
        }
    }

    private void applyFullscreen(){
        if(android.os.Build.VERSION.SDK_INT>=30){
            android.view.WindowInsetsController c=getWindow().getInsetsController();
            if(c!=null){
                c.setSystemBarsBehavior(
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(android.view.WindowInsets.Type.systemBars());
            }
        }else getWindow().getDecorView().setSystemUiVisibility(
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN |
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
    @Override public void onWindowFocusChanged(boolean hasFocus){
        super.onWindowFocusChanged(hasFocus);
        if(hasFocus)applyFullscreen();
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        applyFullscreen();
        getWindow().setStatusBarColor(MAIN);
        getWindow().setNavigationBarColor(MAIN);
        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MAIN);
        setContentView(root);
        LinearLayout tabs=new LinearLayout(this);
        root.addView(tabs);
        addTab(tabs,"‹ EV Cockpit",()->finish());
        addTab(tabs,"🗺 Rota",()->startActivity(new Intent(this,EvCockpitNavigatorActivity.class)));
        addTab(tabs,"⚡ Şarj",this::stationsScreen);
        addTab(tabs,"🚘 Araç",()->startActivity(new Intent(this,EvCockpitOverviewActivity.class)));
        for(int i=0;i<tabs.getChildCount();i++){
            View item=tabs.getChildAt(i);
            if(item instanceof Button){
                Button tab=(Button)item;
                tab.setTextSize(10);
                tab.setSingleLine(true);
                tab.setEllipsize(TextUtils.TruncateAt.END);
            }
        }
        status=text("EV Cockpit · Şarj istasyonları · OBD: Araç",10,false);
        root.addView(status);
        // Enter the station list, never the deprecated long-press map flow.
        stationsScreen();
    }
    private void addTab(LinearLayout row,String label,Runnable action) {
        Button button=button(label,action);
        row.addView(button,new LinearLayout.LayoutParams(0,dp(52),1f));
    }
    private LinearLayout content() {
        if(map!=null){
            try {
                map.stopLoading();
                map.loadUrl("about:blank");
                if(map.getParent() instanceof ViewGroup)
                    ((ViewGroup)map.getParent()).removeView(map);
                map.destroy();
            }catch(Exception ignore){}
            map=null;
        }
        while(root.getChildCount()>2) root.removeViewAt(2);
        mapReady=false;
        LinearLayout layout=new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        root.addView(layout,new LinearLayout.LayoutParams(-1,0,1f));
        return layout;
    }
    private void mapScreen() {
        int g=++generation;
        LinearLayout body=content();
        body.addView(text("EV Cockpit · Türkiye haritası ve şarj istasyonları",14,true));
        LinearLayout searchRow=new LinearLayout(this);
        address=new EditText(this);
        address.setTextColor(Color.WHITE);
        address.setHintTextColor(0xff91b6c3);
        address.setSingleLine(true);
        address.setTextSize(14f);
        address.setHint("İl, ilçe, sokak veya adres");
        searchRow.addView(address,new LinearLayout.LayoutParams(0,dp(48),1f));
        searchRow.addView(button("Ara",this::lookupAddress));
        body.addView(searchRow);
        WebViewAssetLoader loader=new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        map=new WebView(this);
        map.setBackgroundColor(0xffeff1ef);
        WebSettings settings=map.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString()+" "+IDENTIFIER);
        map.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(
                    WebView view,WebResourceRequest req){
                android.net.Uri uri=req.getUrl();
                if(!"https".equals(uri.getScheme()))return denied();
                String host=uri.getHost();
                if("appassets.androidplatform.net".equals(host))
                    return loader.shouldInterceptRequest(uri);
                if("unpkg.com".equals(host)||
                   "tile.openstreetmap.org".equals(host)) return null;
                return denied();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,
                    WebResourceRequest req){return true;}
            @Override public void onPageFinished(WebView view,String url){
                if(!MAP.equals(url) || g!=generation || stopped)return;
                map.evaluateJavascript("window.evcockpitIsReady && window.evcockpitIsReady();", value->{
                    if(g!=generation || stopped)return;
                    mapReady="true".equals(value);
                    status.setText(mapReady ?
                       "Harita hazır · Hedef: dokun, başlangıç: uzun bas" :
                       "Harita yüklenemedi. İnterneti ve CDN erişimini kontrol et.");
                });
            }
        });
        body.addView(map,new LinearLayout.LayoutParams(-1,0,1f));
        LinearLayout commands=new LinearLayout(this);
        addTab(commands,"⚡ Şarjları göster",this::showMapStations);
        addTab(commands,"↗ Rota planla",()->startActivity(new Intent(this,EvCockpitNavigatorActivity.class)));
        body.addView(commands);
        body.addView(text("© OpenStreetMap katkıcıları. Rota önizlemedir; " +
                "canlı trafik ve sesli dönüş talimatları henüz yoktur.",11,false));
        map.loadUrl(MAP);
    }
    private void showMapStations(){
        if(map==null||!mapReady){alert("Harita yükleniyor","Lütfen bekle.");return;}
        final int id=++generation;
        map.evaluateJavascript("window.evcockpitMapBounds();",raw->{
            JSONArray bbox;
            try{bbox=new JSONArray(raw);if(bbox.length()!=4)throw new Exception();}
            catch(Exception error){setStatus("Harita alanı okunamadı.");return;}
            status.setText("EPDK istasyonları arka planda yükleniyor…");
            work.execute(()->{
                try{
                    JSONArray all=loadOfficialData();
                    JSONArray matches=new JSONArray();
                    int total=0;
                    double south=bbox.getDouble(0),west=bbox.getDouble(1);
                    double north=bbox.getDouble(2),east=bbox.getDouble(3);
                    for(int i=0;i<all.length();i++){
                        JSONObject s=all.optJSONObject(i);
                        if(s==null)continue;
                        double lat=s.optDouble("lat",0),lon=s.optDouble("lon",0);
                        if(lat<south||lat>north||lon<west||lon>east)continue;
                        total++;
                        if(matches.length()<55)matches.put(new JSONObject()
                            .put("lat",lat).put("lon",lon)
                            .put("title",s.optString("name").substring(0,
                                Math.min(90,s.optString("name").length())))
                            .put("operator",s.optString("operator"))
                            .put("sockets",formatSockets(s.optJSONArray("sockets"))));
                    }
                    int count=total;
                    runOnUiThread(()->{
                        if(id!=generation||stopped||!mapReady)return;
                        map.evaluateJavascript("window.evcockpitShowStations("+matches+");",null);
                        setStatus(count+" resmî halka açık nokta bu bölgede · "+
                            Math.min(count,55)+" işaret gösterildi · "+verifiedAt);
                    });
                }catch(Exception e){setStatus("EPDK okunamadı. İnternet veya veri güncelliğini kontrol et.");}
            });
        });
    }
    private static String formatSockets(JSONArray sockets){
        if(sockets==null)return "Soket: bilinmiyor";
        int ccs=0,ac=0,chademo=0;StringBuilder rated=new StringBuilder();
        for(int i=0;i<sockets.length();i++){
            JSONObject s=sockets.optJSONObject(i);if(s==null)continue;
            String type=EvCockpitStationTypes.searchable(s.optString("type"));
            String kind=s.optString("kind");
            if(type.contains("ccs")||type.contains("combo"))ccs++;
            else if(type.contains("chademo"))chademo++;
            else if(EvCockpitStationTypes.ac(type,kind))ac++;
            // EPDK snapshot doesn't specify a validated power unit: show raw field only.
            String p=s.optString("power_raw","").trim();
            if(!p.isEmpty() && rated.length()<80 && rated.indexOf(p)<0){
                if(rated.length()>0)rated.append(", ");
                rated.append(p);
            }
        }
        return "CCS "+ccs+" · AC Type 2 "+ac+" · CHAdeMO "+chademo+
            (rated.length()>0?" · EPDK güç alanı: "+rated:"");
    }
    private void lookupAddress(){
        if(address==null||!mapReady){alert("Harita","Önce harita açılsın.");return;}
        String q=address.getText().toString().trim();
        if(q.length()<3){alert("Adres", "En az 3 karakter gir.");return;}
        setStatus("Adres aranıyor…");
        int id=++generation;
        work.execute(()->{
            try{
                // Photon explicitly permits small, reasonable use of its
                // public demo API; do not use the restricted public Nominatim
                // endpoint as an app's generic AI-generated geocoder.
                String path="/api/?limit=6&lang=tr&bbox=25,35,46,43&q="+
                    URLEncoder.encode(q,"UTF-8");
                JSONArray features=new JSONObject(download("photon.komoot.io",
                    path,350000)).getJSONArray("features");
                List<JSONObject> points=new ArrayList<>();
                List<String> names=new ArrayList<>();
                for(int i=0;i<features.length();i++){
                    JSONObject feature=features.optJSONObject(i);
                    if(feature==null)continue;
                    JSONArray coords=feature.optJSONObject("geometry")==null ?
                        null:feature.optJSONObject("geometry").optJSONArray("coordinates");
                    if(coords==null||coords.length()<2)continue;
                    double lon=coords.optDouble(0,Double.NaN);
                    double lat=coords.optDouble(1,Double.NaN);
                    if(!valid(lat,lon))continue;
                    JSONObject props=feature.optJSONObject("properties");
                    String name=props==null?"Haritadaki sonuç":
                        (props.optString("name")+" "+props.optString("street")+
                        " "+props.optString("city")+" "+props.optString("state")).trim();
                    JSONObject normalized=new JSONObject()
                        .put("lat",lat).put("lon",lon).put("display_name",name);
                    points.add(normalized);names.add(name);
                }
                runOnUiThread(()->{
                    if(id!=generation||stopped)return;
                    if(points.isEmpty()){setStatus("Adres bulunamadı.");return;}
                    new AlertDialog.Builder(this).setTitle("Türkiye adres araması")
                        .setItems(names.toArray(new String[0]),(dialog,index)->{
                            JSONObject a=points.get(index);
                            String lat=a.optString("lat"),lon=a.optString("lon");
                            String args=lat+","+lon+","+
                                JSONObject.quote(a.optString("display_name").substring(0,
                                    Math.min(90,a.optString("display_name").length())));
                            map.evaluateJavascript("window.evcockpitFocus("+args+");",null);
                        }).setNegativeButton("Vazgeç",null).show();
                    setStatus(points.size()+" adres bulundu.");
                });
            }catch(Exception e){setStatus("Adres servisine erişilemedi.");}
        });
    }
    private void drawRoute(){
        if(map==null||!mapReady){alert("Harita","Harita henüz hazır değil.");return;}
        map.evaluateJavascript("window.evcockpitGetRouteEndpoints();",raw->{
            try{
                JSONArray coords=new JSONArray(raw);
                if(coords.length()!=4)throw new Exception();
                double fromLon=coords.getDouble(0),fromLat=coords.getDouble(1);
                double toLon=coords.getDouble(2),toLat=coords.getDouble(3);
                if(!valid(fromLat,fromLon)||!valid(toLat,toLon))throw new Exception();
                int id=++generation;
                setStatus("Rota hesaplanıyor…");
                work.execute(()->{
                    try{
                        String path=String.format(Locale.US,
                            "/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=simplified&geometries=geojson&steps=false",
                            fromLon,fromLat,toLon,toLat);
                        JSONObject rawRoute=new JSONObject(download("router.project-osrm.org",
                            path,1500000));
                        JSONObject route=rawRoute.getJSONArray("routes").getJSONObject(0);
                        double km=route.getDouble("distance")/1000.0;
                        double minutes=route.getDouble("duration")/60.0;
                        JSONObject presentation=new JSONObject()
                            .put("coordinates",route.getJSONObject("geometry").getJSONArray("coordinates"))
                            .put("distanceKm",String.format(Locale.US,"%.1f",km))
                            .put("durationMin",String.format(Locale.US,"%.0f",minutes));
                        runOnUiThread(()->{
                            if(id!=generation||stopped||!mapReady)return;
                            map.evaluateJavascript("window.evcockpitShowRoute("+presentation+");",null);
                            setStatus(String.format(Locale.forLanguageTag("tr-TR"),
                                "Tahmini rota %.1f km • %.0f dakika",km,minutes));
                        });
                    }catch(Exception e){setStatus("Rota servisi kullanılamıyor.");}
                });
            }catch(Exception e){
                alert("Rota seçimi","Başlangıç için haritaya uzun bas, hedef için dokun.");
            }
        });
    }

    // Full EPDK public station list, on-demand location, paging, source-labeled pricing.
    private void stationsScreen(){
        ++generation;
        LinearLayout body=content();
        body.setPadding(dp(8),0,dp(8),dp(3));
        body.addView(text("⚡ Türkiye şarj istasyonları",19,true));
        body.addView(text("Halka açık EPDK kayıtları · Güncel doluluk için doğrulanmış canlı veri gerekir.",11,false));
        LinearLayout filter=new LinearLayout(this);
        body.addView(filter);
        typeButton=button("AC + DC ▾",()->{
            typeFilter=(typeFilter+1)%3;
            typeButton.setText(typeFilter==1?"Yalnız AC ▾":typeFilter==2?"Yalnız DC ▾":"AC + DC ▾");
            findStations();
        });
        filter.addView(typeButton,new LinearLayout.LayoutParams(0,dp(44),1));
        radiusButton=button("Tüm mesafeler ▾",()->{
            radiusFilter=(radiusFilter+1)%RADII.length;
            radiusButton.setText(RADII[radiusFilter]==0?"Tüm mesafeler ▾":RADII[radiusFilter]+" km ▾");
            findStations();
        });
        filter.addView(radiusButton,new LinearLayout.LayoutParams(0,dp(44),1));
        stationQuery=new EditText(this);
        stationQuery.setSingleLine(true);stationQuery.setTextColor(Color.WHITE);
        stationQuery.setHintTextColor(0xffa3bdcb);stationQuery.setTextSize(14);
        stationQuery.setHint("Şehir, istasyon, adres veya operatör");
        stationQuery.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        stationQuery.setOnEditorActionListener((view,action,event)->{
            if(action==EditorInfo.IME_ACTION_SEARCH){
                searchHandler.removeCallbacks(searchNow);
                findStations();
                return true;
            }
            return false;
        });
        stationQuery.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){
                searchHandler.removeCallbacks(searchNow);
                searchHandler.postDelayed(searchNow,400);
            }
            @Override public void afterTextChanged(Editable e){}
        });
        body.addView(stationQuery,new LinearLayout.LayoutParams(-1,dp(46)));
        LinearLayout actions=new LinearLayout(this);body.addView(actions);
        actions.addView(button("⌕ Ara",this::findStations),
            new LinearLayout.LayoutParams(0,dp(43),1));
        actions.addView(button("◎ Yakınımdakiler",this::stationGps),
            new LinearLayout.LayoutParams(0,dp(43),1));
        LinearLayout officialRow=new LinearLayout(this);
        body.addView(officialRow);
        officialRow.addView(button("● Canlı doluluk: Şarj@TR",this::openEpdkOfficial),
            new LinearLayout.LayoutParams(0,dp(43),2));
        officialRow.addView(button("↻ EPDK",this::refreshEpdkSnapshot),
            new LinearLayout.LayoutParams(0,dp(43),1));
        stationInfo=text("İstasyon kayıtları okunuyor…",11,false);body.addView(stationInfo);
        stationScroll=new ScrollView(this);
        stationCards=new LinearLayout(this);
        stationCards.setOrientation(LinearLayout.VERTICAL);
        stationScroll.addView(stationCards);
        body.addView(stationScroll,new LinearLayout.LayoutParams(-1,0,1));
        stationPaging=new LinearLayout(this);body.addView(stationPaging);
        body.addView(text("Mesafe kuş uçuşudur. Fiyatlar istasyona özel olmayabilir; kampanya ve müsaitlik yalnız resmî canlı kaynakla teyit edilir.",10,false));
        findStations();
    }
    /** A real EPDK re-fetch, with the last cached station file retained on failure. */
    private void refreshEpdkSnapshot(){
        final int request=++generation;
        if(stationInfo!=null)stationInfo.setText("EPDK kaynağı güncelleniyor…");
        work.execute(()->{
            try{
                synchronized(this){
                    records=null;
                    File cached=new File(getCacheDir(),"mediaengine_evcockpit_epdk.gz");
                    if(cached.isFile())cached.setLastModified(0);
                    loadOfficialData();
                }
                runOnUiThread(()->{if(!stopped&&request==generation)findStations();});
            }catch(Exception e){
                runOnUiThread(()->{
                    if(stopped||request!=generation)return;
                    if(stationInfo!=null)stationInfo.setText("Yeni EPDK verisi alınamadı; eski kayıtlar korunuyor.");
                    findStations();
                });
            }
        });
    }
    private static boolean hasType(JSONObject s,boolean dc){
        JSONArray array=s.optJSONArray("sockets");
        if(array==null)return false;
        for(int i=0;i<array.length();i++){
            JSONObject socket=array.optJSONObject(i);if(socket==null)continue;
            if(dc?EvCockpitStationTypes.dc(socket.optString("type"),socket.optString("kind")):
                EvCockpitStationTypes.ac(socket.optString("type"),socket.optString("kind")))return true;
        }
        return false;
    }
    private static double haversine(double a,double b,double c,double d){
        double x=Math.toRadians(c-a),y=Math.toRadians(d-b);
        double h=Math.pow(Math.sin(x/2),2)+Math.cos(Math.toRadians(a))*
            Math.cos(Math.toRadians(c))*Math.pow(Math.sin(y/2),2);
        return 12742.0176*Math.atan2(Math.sqrt(h),Math.sqrt(Math.max(0,1-h)));
    }
    private void findStations(){
        if(stationCards==null||stationQuery==null)return;
        final int request=++generation,mode=typeFilter,radius=RADII[radiusFilter];
        final String q=EvCockpitStationTypes.searchable(stationQuery.getText().toString().trim());
        final Location position=stationLocation==null?null:new Location(stationLocation);
        stationCards.removeAllViews();stationPaging.removeAllViews();
        stationInfo.setText("EPDK istasyonları listeleniyor…");
        work.execute(()->{
            try{
                JSONArray all=loadOfficialData();
                JSONObject tariffs=priceCatalog;
                if(tariffs==null)try{
                    tariffs=new JSONObject(download("raw.githubusercontent.com",
                        "/AlpTimucin-cpu/ID4-Media-Hub/feature/media-engine-migration/data/charging/operator_prices.json",150000));
                }catch(Exception ignored){}
                ArrayList<StationHit> hits=new ArrayList<>();
                for(int i=0;i<all.length();i++){
                    JSONObject s=all.optJSONObject(i);if(s==null)continue;
                    boolean ac=hasType(s,false),dc=hasType(s,true);
                    if((mode==1&&!ac)||(mode==2&&!dc))continue;
                    String hay=EvCockpitStationTypes.searchable(s.optString("name")+" "+s.optString("address")+" "+
                        s.optString("operator")+" "+s.optString("brand"));
                    if(!q.isEmpty()&&!hay.contains(q))continue;
                    double lat=s.optDouble("lat",Double.NaN),lon=s.optDouble("lon",Double.NaN);
                    double km=position!=null&&valid(lat,lon)?
                        haversine(position.getLatitude(),position.getLongitude(),lat,lon):Double.NaN;
                    if(radius>0&&position!=null&&(!Double.isFinite(km)||km>radius))continue;
                    hits.add(new StationHit(s,km,ac,dc));
                }
                if(position==null)hits.sort(Comparator.comparing(
                    h->h.data.optString("name",""),String.CASE_INSENSITIVE_ORDER));
                else hits.sort(Comparator.comparingDouble(
                    h->Double.isFinite(h.km)?h.km:Double.POSITIVE_INFINITY));
                final JSONObject priceData=tariffs;
                runOnUiThread(()->{
                    if(stopped||request!=generation)return;
                    priceCatalog=priceData;
                    stationMatches.clear();stationMatches.addAll(hits);
                    stationPage=0;renderStationPage();
                    long acCount=hits.stream().filter(x->x.ac).count();
                    long dcCount=hits.stream().filter(x->x.dc).count();
                    stationInfo.setText(hits.size()+" istasyon eşleşti · AC "+acCount+" · DC "+dcCount+"\n"+
                        (position==null?"Konum yok: mesafe için ◎ Yakınımdakiler":
                            "Yakından uzağa (kuş uçuşu)")+
                        "\nEPDK kayıt tarihi: "+verifiedAt+
                        (position==null&&radius>0?" · Yarıçap filtresi için GPS gerekli":""));
                    setStatus("EPDK istasyonları · canlı soket durumu henüz entegre değil");
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    if(stopped||request!=generation)return;
                    stationInfo.setText("İstasyonlar yüklenemedi. Bağlantını kontrol edip yeniden dene.");
                    stationCards.removeAllViews();
                    stationCards.addView(button("↻ Tekrar dene",this::findStations));
                });
            }
        });
    }
    private void renderStationPage(){
        stationCards.removeAllViews();stationPaging.removeAllViews();
        if(stationScroll!=null)stationScroll.post(()->stationScroll.smoothScrollTo(0,0));
        final int start=stationPage*PAGE_SIZE,end=Math.min(stationMatches.size(),start+PAGE_SIZE);
        for(int i=start;i<end;i++){
            StationHit hit=stationMatches.get(i);
            JSONObject s=hit.data;
            LinearLayout card=new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg=new GradientDrawable();
            bg.setColor(0xff15374b);bg.setCornerRadius(dp(12));
            card.setBackground(bg);card.setPadding(dp(10),dp(7),dp(10),dp(8));
            LinearLayout.LayoutParams margin=new LinearLayout.LayoutParams(-1,-2);
            margin.setMargins(0,0,0,dp(7));stationCards.addView(card,margin);
            TextView title=text(s.optString("name","İstasyon"),15,true);
            title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(title);
            card.addView(text(s.optString("operator","İşletmeci yok")+" · "+
                (hit.ac?"AC":"")+(hit.ac&&hit.dc?" + ":"")+(hit.dc?"DC":"")+
                (!hit.ac&&!hit.dc?"Soket türü bilinmiyor":"")+
                (Double.isFinite(hit.km)?" · "+fmt(hit.km)+" km kuş uçuşu":""),12,false));
            card.addView(text(s.optString("address","Adres yok"),11,false));
            card.addView(text(formatSockets(s.optJSONArray("sockets")),11,false));
            card.addView(text("Canlı soket: doğrulanamadı · Şarj@TR'de kontrol et",10,false));
            card.addView(text(priceForStation(priceCatalog,s.optString("operator"),hit.ac,hit.dc),11,false));
            // Do not show a campaign badge without a verified operator feed.
            LinearLayout buttons=new LinearLayout(this);card.addView(buttons);
            buttons.addView(button("🗺 Rota",()->navigateToStation(s)),
                new LinearLayout.LayoutParams(0,dp(43),1));
            buttons.addView(button("₺ Fiyat kaynağı",()->
                openOperatorPrice(s.optString("operator"))),
                new LinearLayout.LayoutParams(0,dp(43),1));
        }
        if(stationMatches.isEmpty()){
            stationCards.addView(text("İstasyon eşleşmedi. Aramayı veya filtreyi değiştir.",13,false));
            return;
        }
        Button prev=button("‹ Önceki",()->{if(stationPage>0){stationPage--;renderStationPage();}});
        prev.setEnabled(stationPage>0);
        stationPaging.addView(prev,new LinearLayout.LayoutParams(0,dp(42),1));
        TextView position=text((stationPage+1)+" / "+((stationMatches.size()+PAGE_SIZE-1)/PAGE_SIZE),12,true);
        position.setGravity(android.view.Gravity.CENTER);
        stationPaging.addView(position,new LinearLayout.LayoutParams(0,dp(42),1));
        Button next=button("Sonraki ›",()->{
            if((stationPage+1)*PAGE_SIZE<stationMatches.size()){stationPage++;renderStationPage();}
        });
        next.setEnabled(end<stationMatches.size());
        stationPaging.addView(next,new LinearLayout.LayoutParams(0,dp(42),1));
    }
    private void openEpdkOfficial(){
        Intent installed=getPackageManager().getLaunchIntentForPackage("tr.gov.epdk.sarjetTR");
        if(installed!=null){startActivity(installed);return;}
        startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(
            "https://play.google.com/store/apps/details?id=tr.gov.epdk.sarjetTR")));
    }
    private static String priceForStation(JSONObject prices,String operator,boolean ac,boolean dc){
        if(prices==null)return "Fiyat: güncel resmî tarife okunamadı";
        JSONArray ops=prices.optJSONArray("operators");
        if(ops==null)return "Fiyat: doğrulanamadı";
        for(int i=0;i<ops.length();i++){
            JSONObject op=ops.optJSONObject(i);
            if(op==null||!operatorMatches(operator,op.optString("operator")))continue;
            JSONArray tariffs=op.optJSONArray("tariffs");
            StringBuilder b=new StringBuilder();
            Instant newest=Instant.EPOCH;
            if(tariffs!=null)for(int j=0;j<tariffs.length();j++){
                JSONObject t=tariffs.optJSONObject(j);
                if(t==null)continue;
                String band=t.optString("connector_band").toUpperCase(Locale.ROOT);
                if(!(ac&&band.startsWith("AC")||dc&&band.startsWith("DC")))continue;
                double price=t.optDouble("price_try_per_kwh",Double.NaN);
                if(!Double.isFinite(price)||price<=0)continue;
                if(b.length()>0)b.append(" · ");
                b.append(band.startsWith("AC")?"AC":"DC").append(" ").append(fmt(price));
                try{
                    Instant at=Instant.parse(t.optString("verified_at_utc"));
                    if(at.isAfter(newest))newest=at;
                }catch(Exception ignored){}
            }
            if(b.length()==0)return "Fiyat: bu soket için kaynak yok";
            boolean fresh=newest.isAfter(Instant.now().minus(Duration.ofHours(36)))&&
                !newest.isAfter(Instant.now());
            return (fresh?"Genel ağ fiyatı: ":"ESKİ genel fiyat (güncel değil): ")+
                b+" TL/kWh · "+(newest.equals(Instant.EPOCH)?"tarih yok":newest.toString().substring(0,10))+
                " · Soket fiyatı değil";
        }
        return "Fiyat: bu işletmeci için doğrulanmış tarife yok";
    }
    private void openOperatorPrice(String operator){
        JSONArray ops=priceCatalog==null?null:priceCatalog.optJSONArray("operators");
        if(ops!=null)for(int i=0;i<ops.length();i++){
            JSONObject op=ops.optJSONObject(i);
            if(op!=null&&operatorMatches(operator,op.optString("operator"))){
                String url=op.optString("official_url");
                if(url.startsWith("https://")){
                    startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));return;
                }
            }
        }
        new AlertDialog.Builder(this).setTitle("Canlı fiyat ve doluluk")
            .setMessage("Bu operatörün canlı soket verisi ve kampanyaları doğrulanamıyor. "+
                "EPDK Şarj@TR ile müsaitliği kontrol edebilirsin.")
            .setNegativeButton("Kapat",null)
            .setPositiveButton("Şarj@TR", (dialog,which)->openEpdkOfficial()).show();
    }
    private void navigateToStation(JSONObject s){
        double lat=s.optDouble("lat",Double.NaN),lon=s.optDouble("lon",Double.NaN);
        if(!valid(lat,lon)){alert("Koordinat yok","İstasyon konumu eksik.");return;}
        EvCockpitEnergyData.saveStation(this,s.optString("name",""),s.optString("operator",""));
        Intent intent=new Intent(this,EvCockpitNavigatorActivity.class);
        intent.putExtra("toLat",lat);intent.putExtra("toLon",lon);
        intent.putExtra("toName",s.optString("name","Şarj istasyonu"));
        startActivity(intent);
    }
    private void stationGps(){
        if(Build.VERSION.SDK_INT>=23 &&
           checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED &&
           checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION},704);return;
        }
        try{
            LocationManager location=(LocationManager)getSystemService(LOCATION_SERVICE);
            String provider=location.isProviderEnabled(LocationManager.GPS_PROVIDER)?
                LocationManager.GPS_PROVIDER:LocationManager.NETWORK_PROVIDER;
            if(!location.isProviderEnabled(provider)){stationInfo.setText("Konum servislerini aç.");return;}
            stationInfo.setText("Telefon konumu alınıyor…");
            if(Build.VERSION.SDK_INT>=30)
                location.getCurrentLocation(provider,null,getMainExecutor(),this::stationLocationReceived);
            else stationLocationReceived(location.getLastKnownLocation(provider));
        }catch(Exception e){stationInfo.setText("Konum alınamadı. İzni ve GPS'i kontrol et.");}
    }
    private void stationLocationReceived(Location found){
        if(stopped||stationInfo==null)return;
        if(found==null||!valid(found.getLatitude(),found.getLongitude()) ||
            found.getTime()<=0||Math.abs(System.currentTimeMillis()-found.getTime())>900000){
            stationInfo.setText("Güncel konum yok. Tekrar dene.");return;
        }
        stationLocation=new Location(found);findStations();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==704){
            boolean granted=false;
            for(int status:results)if(status==PackageManager.PERMISSION_GRANTED)granted=true;
            if(granted)stationGps();
            else if(stationInfo!=null)stationInfo.setText("Konum izni yok; şehir/operatör araması kullanılabilir.");
        }
    }

    /**
     * Fully automatic energy page. No sample SoC, battery %, trip length or tariff
     * can be mistaken for measured vehicle telemetry.
     */
    private void energyScreen(){
        final int currentPage=++generation;
        energyPage=currentPage;
        LinearLayout container=content();
        ScrollView sc=new ScrollView(this);
        LinearLayout panel=new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12),dp(10),dp(12),dp(38));
        sc.addView(panel);
        container.addView(sc,new LinearLayout.LayoutParams(-1,0,1f));
        panel.addView(text("EV COCKPIT · ENERJİ & MALİYET",18,true));
        panel.addView(text("Manuel giriş yok. Seçilen rota, son doğrulanmış OBD "+
            "batarya ölçümü ve seçilen operatörün kaynaklı tarifesi kullanılır.",13,false));
        final TextView data=text("",15,false);
        final TextView model=text("",15,false);
        final TextView tariff=text("Fiyat kaynağı kontrol ediliyor…",14,false);
        panel.addView(data);panel.addView(model);panel.addView(tariff);
        autoEnergyFacts=data;autoEnergyModel=model;autoEnergyPrice=tariff;
        panel.addView(text("Model varsayımı: referans batarya "+
            "52 kWh, tüketim 16–24 kWh/100 km, varış rezervi %15. "+
            "Bunlar araçtan ölçülmemiştir; gerçek SoH, tüketim, "+
            "şarj kayıpları ve soket ücreti doğrulanmış değildir.",12,false));
        panel.addView(button("🔄 Verileri otomatik güncelle",()->{
            if(stopped||currentPage!=generation)return;
            renderAutoEnergy(data,model);
            fetchAutomaticPrice(currentPage,tariff);
        }));
        panel.addView(button("🔌 OBD batarya ölçümlerini aç",()->
            startActivity(new Intent(this,EvCockpitNavigatorActivity.class).putExtra("openObdSettings",true))));
        panel.addView(button("🗺 Otomatik rota mesafesi al",()->
            startActivity(new Intent(this,EvCockpitNavigatorActivity.class))));
        panel.addView(button("⚡ Şarj istasyonu seç",this::stationsScreen));
        renderAutoEnergy(data,model);
        fetchAutomaticPrice(currentPage,tariff);
    }

    private void renderAutoEnergy(TextView data,TextView model){
        EvCockpitEnergyData.Snapshot snapshot=EvCockpitEnergyData.load(this,System.currentTimeMillis());
        String route=snapshot.hasRoute()?String.format(Locale.forLanguageTag("tr-TR"),
            "Haritadan rota: %.1f km / %.0f dk",snapshot.routeKm,snapshot.routeMinutes):
            "Rota bulunamadı. Önce haritada rota oluştur.";
        String soc=snapshot.hasSoc()?String.format(Locale.forLanguageTag("tr-TR"),
            "Son doğrulanmış OBD doluluk: %%%.1f (%d sn önce)",snapshot.soc,
            (System.currentTimeMillis()-snapshot.socAt)/1000L):
            "GÜNCEL OBD SoC YOK • %65 gibi örnek değer gösterilmez.";
        data.setText("OTOMATİK VERİ KAYNAKLARI\n"+route+"\n"+soc+"\n"+
            (snapshot.station.isEmpty()?"Şarj istasyonu seçilmedi":
             "Seçilen istasyon: "+snapshot.station+" • "+snapshot.operator));
        if(!snapshot.hasRoute()){
            model.setText("ROTA ENERJİSİ\nHesap için önce gerçek rota mesafesi gerekli.");
            return;
        }
        EvCockpitEnergyModel.Result estimate=EvCockpitEnergyModel.estimate(snapshot.routeKm,
            snapshot.hasSoc()?snapshot.soc:Double.NaN);
        if(estimate==null){model.setText("Model için geçerli rota bulunamadı");return;}
        String line=String.format(Locale.forLanguageTag("tr-TR"),
            "MODEL TAHMİNİ (ÖLÇÜM DEĞİL)\nYol tüketimi: %.1f – %.1f kWh",
            estimate.lowRouteKwh,estimate.highRouteKwh);
        if(estimate.hasSoc)line+=String.format(Locale.forLanguageTag("tr-TR"),
            "\n%%15 rezerv varsayımıyla ilave batarya enerjisi: %.1f – %.1f kWh",
            estimate.lowRequiredChargeKwh,estimate.highRequiredChargeKwh);
        else line+="\nİlave şarj ihtiyacını hesaplamak için güncel OBD verisi gerekir.";
        model.setText(line);
    }

    private void fetchAutomaticPrice(int page,TextView target){
        EvCockpitEnergyData.Snapshot selected=EvCockpitEnergyData.load(this,System.currentTimeMillis());
        if(selected.operator.trim().isEmpty()){
            target.setText("OTOMATİK FİYAT\nOperatör seçilmedi; bir şarj "+
                "istasyonu seçtiğinde tarifesi kontrol edilir.");
            return;
        }
        target.setText("OTOMATİK FİYAT\n"+selected.operator+" güncel tarife aranıyor…");
        work.execute(()->{
            String answer;
            try{
                JSONObject root=new JSONObject(download("raw.githubusercontent.com",
                    "/AlpTimucin-cpu/ID4-Media-Hub/feature/media-engine-migration/data/charging/operator_prices.json",150000));
                JSONArray operators=root.optJSONArray("operators");
                double cheapest=Double.POSITIVE_INFINITY,highest=Double.NEGATIVE_INFINITY;
                String from="";
                if(operators!=null)for(int i=0;i<operators.length();i++){
                    JSONObject op=operators.optJSONObject(i);
                    if(op==null||!operatorMatches(selected.operator,op.optString("operator")))continue;
                    JSONArray tariffs=op.optJSONArray("tariffs");
                    if(tariffs==null)continue;
                    for(int j=0;j<tariffs.length();j++){
                        JSONObject t=tariffs.optJSONObject(j);if(t==null)continue;
                        long hours=Long.MAX_VALUE;
                        try{hours=Duration.between(Instant.parse(t.getString("verified_at_utc")),
                            Instant.now()).toHours();}catch(Exception ignored){}
                        if(hours<0||hours>36)continue;
                        double price=t.optDouble("price_try_per_kwh",Double.NaN);
                        if(!Double.isFinite(price)||price<=0)continue;
                        cheapest=Math.min(cheapest,price);highest=Math.max(highest,price);
                        from=t.optString("source_url");
                    }
                }
                if(!Double.isFinite(cheapest)||!Double.isFinite(highest)){
                    answer="OTOMATİK FİYAT\n"+selected.operator+
                        ": Güncel doğrulanmış tarife yok. Eski veya "+
                        "manuel fiyatla maliyet üretilmedi.";
                }else{
                    EvCockpitEnergyData.Snapshot latest=EvCockpitEnergyData.load(this,System.currentTimeMillis());
                    EvCockpitEnergyModel.Result result=EvCockpitEnergyModel.estimate(latest.routeKm,
                        latest.hasSoc()?latest.soc:Double.NaN);
                    if(result==null||!result.hasSoc){
                        answer="OTOMATİK FİYAT\n"+selected.operator+" yayımlanmış "+
                            "genel AC/DC tarifeleri: "+fmt(cheapest)+" – "+
                            fmt(highest)+" TL/kWh. Güncel SoC ve rota birlikte "+
                            "olmadığı için toplam TL çıkarılamaz. Kaynak: "+from;
                    }else{
                        double[] cost=result.indicativeCost(cheapest,highest);
                        answer="OTOMATİK FİYAT\n"+selected.operator+" genel AC/DC "+
                            "tarife aralığı: "+fmt(cheapest)+" – "+fmt(highest)+
                            " TL/kWh\nTEORİK ek şarj bedeli: "+
                            fmt(cost[0])+" – "+fmt(cost[1])+
                            " TL\nGerçek fatura değildir; soket fiyatı ve kayıplar "+
                            "belirsiz. Kaynak: "+from;
                    }
                }
            }catch(Exception e){
                answer="OTOMATİK FİYAT\nTarife kaynağına ulaşılamadı. "+
                    "Sabit 13 TL/kWh gibi fiyat kullanılmadı.";
            }
            final String output=answer;
            runOnUiThread(()->{if(!stopped&&page==generation)target.setText(output);});
        });
    }
    private static boolean operatorMatches(String selected,String source){
        String a=selected.trim().toLowerCase(Locale.ROOT);
        String b=source.trim().toLowerCase(Locale.ROOT);
        return !a.isEmpty()&&!b.isEmpty()&&(a.equals(b)||a.contains(b)||b.contains(a));
    }
    private static String fmt(double v){
        return String.format(Locale.forLanguageTag("tr-TR"),"%.1f",v);
    }

    private void showPrices(){
        setStatus("Resmî Trugo/ZES tarifeleri kontrol ediliyor…");
        work.execute(()->{
            try{
                JSONObject root=new JSONObject(download("raw.githubusercontent.com",
                    "/AlpTimucin-cpu/ID4-Media-Hub/feature/media-engine-migration/data/charging/operator_prices.json",150000));
                JSONArray operators=root.optJSONArray("operators");
                StringBuilder b=new StringBuilder();
                if(operators!=null)for(int i=0;i<operators.length();i++){
                    JSONObject operator=operators.optJSONObject(i);if(operator==null)continue;
                    b.append(operator.optString("operator")).append("\n");
                    JSONArray items=operator.optJSONArray("tariffs");
                    if(items==null)continue;
                    for(int j=0;j<items.length();j++){
                        JSONObject t=items.optJSONObject(j);if(t==null)continue;
                        String ts=t.optString("verified_at_utc");
                        boolean fresh=false;
                        try{
                            long age=Duration.between(Instant.parse(ts),Instant.now()).toHours();
                            fresh=age>=0&&age<=36;
                        }catch(Exception ignore){}
                        b.append("  ").append(t.optString("connector_band")).append(": ")
                         .append(t.optString("price_try_per_kwh")).append(" TL/kWh")
                         .append(fresh?" (güncel)":" (ESKİ / DOĞRULANAMADI)").append("\n");
                    }
                    b.append("\n");
                }
                if(b.length()==0)b.append("Resmî tarifeler şu an doğrulanamıyor.");
                String message=b+"\nOperatör genel tarifesi; istasyonun kesin soket fiyatı değildir.";
                runOnUiThread(()->{
                    if(stopped)return;
                    new AlertDialog.Builder(this).setTitle("Kaynaklı resmî tarifeler")
                        .setMessage(message).setPositiveButton("Tamam",null).show();
                    setStatus("Fiyatlar kaynak ve zamana göre kontrol edildi.");
                });
            }catch(Exception e){setStatus("Tarifelere erişilemiyor.");}
        });
    }
    private synchronized JSONArray loadOfficialData()throws Exception{
        if(records!=null)return records;
        File cache=new File(getCacheDir(),"mediaengine_evcockpit_epdk.gz");
        byte[] zipped=null;
        if(cache.isFile()&&System.currentTimeMillis()-cache.lastModified()<6*3600000L){
            zipped=readBounded(new FileInputStream(cache),10_000_000);
        }else{
            try{
                String path="/AlpTimucin-cpu/ID4-Media-Hub/"+
                    "feature/media-engine-migration/data/charging/epdk_public_stations.json.gz";
                zipped=downloadBytes("raw.githubusercontent.com",path,10_000_000);
            }catch(Exception e){
                if(cache.isFile())zipped=readBounded(new FileInputStream(cache),10_000_000);
                else throw e;
            }
        }
        ByteArrayOutputStream decoded=new ByteArrayOutputStream();
        try(InputStream in=new GZIPInputStream(new ByteArrayInputStream(zipped))){
            byte[] buf=new byte[8192];
            for(int n;(n=in.read(buf))!=-1;){
                if(decoded.size()+n>22_000_000)throw new Exception("Oversized EPDK data");
                decoded.write(buf,0,n);
            }
        }
        JSONObject root=new JSONObject(decoded.toString("UTF-8"));
        if(root.optInt("schema_version")!=1||root.optBoolean("prices_included",true)||
           root.optBoolean("live_status_included",true))throw new Exception();
        JSONArray stations=root.getJSONArray("stations");
        if(stations.length()<5000||stations.length()>100000)throw new Exception();
        Instant verified=Instant.parse(root.getString("verified_at_utc"));
        long age=Duration.between(verified,Instant.now()).toHours();
        if(age<0)throw new Exception("Future-dated official snapshot");
        // Station locations remain useful when daily background publishing
        // is delayed; label old data instead of inventing freshness.
        verifiedAt=root.getString("verified_at_utc").substring(0,16)+" UTC"+
            (age>72?" • ESKİ İSTASYON VERİSİ":"");
        // Once source/schema/time has been validated, preserve last valid cache.
        if(!cache.isFile()||System.currentTimeMillis()-cache.lastModified()>6*3600000L){
            try(FileOutputStream out=new FileOutputStream(cache)){out.write(zipped);}
        }
        return records=stations;
    }
    private static boolean valid(double lat,double lon){
        return Double.isFinite(lat)&&Double.isFinite(lon)&&
            lat>=35&&lat<=43&&lon>=25&&lon<=46;
    }
    private static byte[] readBounded(InputStream stream,int max)throws Exception{
        try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];
            for(int n;(n=in.read(buffer))!=-1;){
                if(out.size()+n>max)throw new Exception("Response too large");
                out.write(buffer,0,n);
            }
            return out.toByteArray();
        }
    }
    private static byte[] downloadBytes(String host,String path,int max)throws Exception{
        if(!("raw.githubusercontent.com".equals(host)||
             "photon.komoot.io".equals(host)||
             "router.project-osrm.org".equals(host)))throw new Exception();
        URL url=new URL("https://"+host+path);
        HttpURLConnection conn=(HttpURLConnection)url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(14000);conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent",IDENTIFIER);
        conn.setRequestProperty("Accept","application/json");
        try{
            if(conn.getResponseCode()!=200)throw new Exception("HTTP "+conn.getResponseCode());
            return readBounded(conn.getInputStream(),max);
        }finally{conn.disconnect();}
    }
    private static String download(String host,String path,int max)throws Exception{
        return new String(downloadBytes(host,path,max),java.nio.charset.StandardCharsets.UTF_8);
    }
    private void setStatus(String text){
        runOnUiThread(()->{if(!stopped && status!=null)status.setText(text);});
    }
    private WebResourceResponse denied(){
        return new WebResourceResponse("text/plain","utf-8",
            403,"Blocked",java.util.Map.of(),
            new ByteArrayInputStream(new byte[0]));
    }
    private void alert(String title,String text){
        new AlertDialog.Builder(this).setTitle(title).setMessage(text)
            .setPositiveButton("Tamam",null).show();
    }
    private int dp(int d){return (int)(d*getResources().getDisplayMetrics().density);}
    private TextView text(String content,int size,boolean strong){
        TextView v=new TextView(this);
        v.setText(content);v.setTextSize(size);
        v.setTextColor(strong?AQUA:0xffc7d6dd);
        if(strong)v.setTypeface(v.getTypeface(),Typeface.BOLD);
        v.setPadding(dp(9),dp(10),dp(9),dp(10));
        return v;
    }
    private Button button(String label,Runnable r){
        Button b=new Button(this);
        b.setText(label);b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        GradientDrawable g=new GradientDrawable();
        g.setColor(0xff22566a);g.setCornerRadius(dp(11));
        b.setBackground(g);b.setOnClickListener(v->r.run());
        return b;
    }
    @Override public void onResume(){
        super.onResume();
        // Recompute whenever returning from the OBD or navigation Activity.
        if(energyPage==generation && autoEnergyFacts!=null && !stopped){
            renderAutoEnergy(autoEnergyFacts,autoEnergyModel);
            fetchAutomaticPrice(energyPage,autoEnergyPrice);
        }
    }
    @Override public void onDestroy(){
        stopped=true;generation++;work.shutdownNow();
        if(map!=null){
            try{map.stopLoading();map.loadUrl("about:blank");
                if(map.getParent() instanceof ViewGroup)
                    ((ViewGroup)map.getParent()).removeView(map);
                map.destroy();}catch(Exception ignore){}
            map=null;
        }
        searchHandler.removeCallbacks(searchNow);
        super.onDestroy();
    }
}
