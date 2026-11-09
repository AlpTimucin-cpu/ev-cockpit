package me.aap.fermata.ev;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.text.InputType;
import android.widget.EditText;
import androidx.webkit.WebViewAssetLoader;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Navigation-first, same-app route preview for electric vehicles.
 * Beta routing via OSRM demo; NOT a validated live-traffic, offline or safety-grade navigator.
 * The map uses OSM tiles. Never launches ABRP/Maps/other apps.
 */
public final class EvCockpitNavigatorActivity extends Activity implements LocationListener {
    private static final String PAGE="https://appassets.androidplatform.net/assets/evcockpit/navigator.html";
    private final ExecutorService jobs=Executors.newSingleThreadExecutor();
    // Slow geocoding and model weather must never queue ahead of navigation.
    private final ExecutorService searchJobs=Executors.newSingleThreadExecutor();
    private final ExecutorService routeWeatherJobs=Executors.newSingleThreadExecutor();
    // Station requests must not hold back route or weather processing.
    private final ExecutorService stationJobs=Executors.newSingleThreadExecutor();
    // Slow public routing variants must not hold the first route/map hostage.
    private final ExecutorService variantJobs=Executors.newSingleThreadExecutor();
    // Current Open-Meteo readings are MODEL values, never actual station sensors.
    private final ExecutorService currentWeatherJobs=Executors.newSingleThreadExecutor();
    // User-triggered OSM camera queries must not block route, charging or weather.
    private final ExecutorService safetyJobs=Executors.newSingleThreadExecutor();
    private final ExecutorService incidentJobs=Executors.newSingleThreadExecutor();
    private static final long INCIDENT_REFRESH_MS=4*60*1000L;
    private volatile long lastIncidentPollAt;
    private volatile String lastSpokenIncidentId="";
    private volatile long lastIncidentSpeechAt;
    private volatile JSONArray activeIncidentRoute;
    private volatile long lastSafetyQueryAt=0;
    private volatile long lastSafetySpeechAt=0;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ArrayList<Step> steps=new ArrayList<>();
    private WebView web;
    private EvCockpitLiveTelemetry telemetry;
    private final EvCockpitTripJournal tripJournal=new EvCockpitTripJournal();
    private EvCockpitStationCatalog stationCatalog;
    private volatile int stationRequestId;
    private volatile int routeStationRequestId;
    private EvCockpitLiveTelemetry.Data latestObd=new EvCockpitLiveTelemetry.Data();
    private LocationManager locationManager;
    private TextToSpeech tts;
    private Location position;
    private boolean running, voice=true, gpsTrackingRequested, resumeRouteAfterPause;
    private volatile boolean ttsReady;
    private volatile float speechVolume=0.85f;
    private android.media.AudioManager speechAudio;
    private android.media.AudioFocusRequest speechFocus;
    private volatile boolean destroyed;
    private volatile String activeRouteProvider="OSRM demo";
    private volatile int generation;
    private int nextStep;
    private volatile JSONArray cachedAlternatives;
    private volatile JSONObject activeWeatherRoute;
    private volatile long lastJourneyWeatherRefreshAt;
    // Foreground-only refresh. Open-Meteo updates its MODELS periodically;
    // polling more often would not make roadside sensor observations appear.
    private static final long ROUTE_WEATHER_REFRESH_MS=5*60*1000L;
    private boolean weatherForeground=false;
    private final Runnable routeWeatherTicker=new Runnable(){
        @Override public void run(){
            if(destroyed || !weatherForeground)return;
            JSONObject route=activeWeatherRoute;
            long now=System.currentTimeMillis();
            if(route!=null && now-lastJourneyWeatherRefreshAt>=ROUTE_WEATHER_REFRESH_MS){
                lastJourneyWeatherRefreshAt=now;
                final int ticket=generation;
                send("mediaengineJourneyWeatherLoading",record("weather",
                    "Rota hava modeli 5 dakikada bir yenileniyor."));
                try{
                    routeWeatherJobs.execute(()->loadJourneyWeather(route,ticket));
                }catch(java.util.concurrent.RejectedExecutionException ignored){}
            }
            ui.postDelayed(this,60000L);
        }
    };
    private volatile boolean cachedRouteGps, cachedHasStop;
    private volatile int selectedAlternativeIndex;
    private boolean socPromptShown;
    private long lastSpeech;
    private volatile long lastCurrentWeatherAt;
    private volatile double lastCurrentWeatherLat=Double.NaN,
                            lastCurrentWeatherLon=Double.NaN;
    private static final class Step {
        final double lat, lon;
        final String instruction;
        int spokenMask=0;
        Step(double lat,double lon,String instruction){this.lat=lat;this.lon=lon;this.instruction=instruction;}
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        // Enter the requested car-style cockpit at launch, not the tall legacy sheet.
        // Store a manual portrait choice so that a configuration recreation cannot
        // silently lock the user back into landscape.
        boolean wide=getSharedPreferences("evcockpit_presentation",MODE_PRIVATE)
            .getBoolean("landscape",true);
        setRequestedOrientation(wide?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().setStatusBarColor(0xff061e2b);
        getWindow().setNavigationBarColor(0xff061e2b);
        locationManager=(LocationManager)getSystemService(LOCATION_SERVICE);
        stationCatalog=new EvCockpitStationCatalog(this);
        telemetry=new EvCockpitLiveTelemetry(this,data->{
            latestObd=data;
            send("mediaengineTelemetry",obdPacket(data));
        });
        voice=getSharedPreferences("eya_voice_v1",MODE_PRIVATE)
            .getBoolean("enabled",true);
        speechVolume=Math.max(0f,Math.min(1f,
            getSharedPreferences("eya_voice_v1",MODE_PRIVATE).getFloat("volume",0.85f)));
        speechAudio=(android.media.AudioManager)getSystemService(AUDIO_SERVICE);
        tts=new TextToSpeech(this,status->{
            if(status==TextToSpeech.SUCCESS && tts!=null){
                int lang=tts.setLanguage(Locale.forLanguageTag("tr-TR"));
                ttsReady=lang!=TextToSpeech.LANG_MISSING_DATA &&
                    lang!=TextToSpeech.LANG_NOT_SUPPORTED;
                tts.setOnUtteranceProgressListener(
                  new android.speech.tts.UtteranceProgressListener(){
                    @Override public void onStart(String id){}
                    @Override public void onDone(String id){
                        ui.post(()->releaseSpeechFocus());
                    }
                    @Override public void onError(String id){
                        ui.post(()->releaseSpeechFocus());
                    }
                  });
            }else ttsReady=false;
            if(!ttsReady)send("mediaengineStatus",record("voice",
               "Türkçe ses paketi bulunamadı; anonslar sessiz."));
        });
        WebViewAssetLoader loader=new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web=new WebView(this);
        web.setBackgroundColor(0xffedf2ed);
        setContentView(web,new ViewGroup.LayoutParams(-1,-1));
        WebSettings st=web.getSettings();
        st.setJavaScriptEnabled(true);
        // OSMF tile policy: identify the application instead of sending only the
        // generic embedded browser UA. Leave the Android WebView capabilities
        // intact; do not spoof an unrelated application.
        String standardWebViewAgent=st.getUserAgentString();
        st.setUserAgentString(standardWebViewAgent+
            " EVCOCKPITYolArkadasim/0.5 (+https://github.com/AlpTimucin-cpu/ID4-Media-Hub)");
        st.setCacheMode(WebSettings.LOAD_DEFAULT); // respect server HTTP caching

        // Word's local, device-only incident reports use origin-scoped localStorage.
        // WebViewAssetLoader serves HTTPS assets; file access remains disabled.
        st.setDomStorageEnabled(true);
        st.setAllowFileAccess(false);
        st.setAllowContentAccess(false);
        st.setJavaScriptCanOpenWindowsAutomatically(false);
        web.addJavascriptInterface(new Bridge(),"MediaEngineNative");
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                return loader.shouldInterceptRequest(r.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){
                return !PAGE.equals(r.getUrl().toString());
            }
            @Override public void onPageFinished(WebView v,String url){
                if(!PAGE.equals(url) || destroyed)return;
                send("mediaengineTelemetry",obdPacket(latestObd));
                sendScenarioStatus();
                sendVoiceState();
                applyLocalTomTomKey();
                Intent source=getIntent();
                if(source!=null && source.getBooleanExtra("openObdSettings",false)){
                    source.removeExtra("openObdSettings");
                    ui.postDelayed(()->showObdSettings(),250);
                }
                if(source==null || !source.hasExtra("toLat") || !source.hasExtra("toLon"))return;
                double lat=source.getDoubleExtra("toLat",Double.NaN);
                double lon=source.getDoubleExtra("toLon",Double.NaN);
                if(!valid(lat,lon))return;
                JSONObject station=new JSONObject();
                try{
                    station.put("lat",lat);station.put("lon",lon);
                    station.put("label",source.getStringExtra("toName"));
                    send("mediaengineDestination",station);
                }catch(Exception ignored){}
            }
        });
        web.loadUrl(PAGE);
    }
    /**
     * Keep MediaEngineNative as the only JavascriptInterface. Add event-stream
     * notifications without replacing the tested legacy callback contract.
     */
    private void send(String function, JSONObject data){
        ui.post(()->{
            if(destroyed||web==null||data==null)return;
            web.evaluateJavascript("window."+function+"("+data.toString()+")",null);
            String eventName=null;
            switch(function){
                case "mediaengineTelemetry":eventName="evcockpit:obd";break;
                case "mediaengineRoute":eventName="evcockpit:route";break;
                case "mediaenginePosition":eventName="evcockpit:gps";break;
                case "mediaengineStations":eventName="evcockpit:stations";break;
                case "mediaengineJourneyWeather":eventName="evcockpit:weather";break;
                case "mediaengineManeuver":eventName="evcockpit:maneuver";break;
                default:break;
            }
            if(eventName==null)return;
            try{
                JSONObject payload=new JSONObject(data.toString());
                payload.put("ts",System.currentTimeMillis());
                if("evcockpit:obd".equals(eventName)){
                    boolean fresh=data.optBoolean("fresh",false);
                    payload.put("soc",fresh?metricNumber(data.optString("soc"),0,100):JSONObject.NULL);
                    payload.put("voltage",fresh?metricNumber(data.optString("voltage"),0,1000):JSONObject.NULL);
                    payload.put("batTemp",fresh?metricNumber(data.optString("temperature"),-50,110):JSONObject.NULL);
                    payload.put("power",JSONObject.NULL); // not calibrated
                    payload.put("avgCons",JSONObject.NULL); // not calibrated
                    payload.put("mode",fresh&&data.optLong("modeAt",0)>0?
                        data.optString("chargeMode"):JSONObject.NULL);
                }
                web.evaluateJavascript("window.dispatchEvent(new CustomEvent("+
                    JSONObject.quote(eventName)+",{detail:"+payload.toString()+"}))",null);
            }catch(Exception ignored){}
        });
    }
    private static Object metricNumber(String raw,double min,double max){
        if(raw==null)return JSONObject.NULL;
        String s=raw.trim().replace(",",".");
        java.util.regex.Matcher m=java.util.regex.Pattern
            .compile("^(-?[0-9]+(?:\\.[0-9]+)?)\\s*(?:%|V|°C)?$")
            .matcher(s);
        if(!m.matches())return JSONObject.NULL;
        try{
            double n=Double.parseDouble(m.group(1));
            return Double.isFinite(n)&&n>=min&&n<=max?n:JSONObject.NULL;
        }catch(Exception ignored){return JSONObject.NULL;}
    }
    /**
     * Keep this navigation Activity in the back stack so that the existing
     * OSRM route, WebView map and SoC scenario survive a visit to the original
     * MediaEngine TV/IPTV/audio dashboard. MediaEngine's own MediaSession/engine stays owned
     * by its media component. Do not recreate it in this WebView Activity.
     *
     * This is a PHONE activity transition, not an Android Auto video launch.
     * No CarSensors spoofing, parked restriction bypass or player overrides.
     */
    private void openMediaSafely(){
        openMediaEngineDestination("mediaengine");
    }
    // Package/component identities verified against the supplied APK manifests.
    private static final String[] ORIGINAL_MEDIAENGINE_PACKAGES = {
        "me.app.fermataX.auto.mola.debug", // supplied 4.1.10, versionCode 433
        "me.app.fermataX.auto.mola32.debug"
    };
    private static final String ORIGINAL_MEDIAENGINE_LAUNCHER =
        "me.app.fermatax.auto.PhoneLauncherActivity";

    /**
     * Optional companion handoff into the original, separately installed APK.
     * We do not modify/re-sign the user's APK or copy its libraries/services.
     * Original app controls its own features, state, and playback.
     */
    private void openOriginalMediaUnmodified(){
        if(destroyed || isFinishing() || isDestroyed())return;
        for(String packageName : ORIGINAL_MEDIAENGINE_PACKAGES){
            if(packageName.equals(getPackageName()))continue;
            try{
                Intent other=new Intent(Intent.ACTION_MAIN);
                other.addCategory(Intent.CATEGORY_LAUNCHER);
                other.setClassName(packageName,ORIGINAL_MEDIAENGINE_LAUNCHER);
                other.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(other);
                return;
            }catch(android.content.ActivityNotFoundException | SecurityException ignored){}
        }
        send("mediaengineStatus",record("media","MediaEngine 4.1.10 veya önceki uyumlu sürüm telefonda bulunamadı."));
    }


    private void openMediaEngineDestination(String destination){
        if(destroyed || isFinishing() || isDestroyed())return;
        try{
            Intent media=new Intent(this,me.aap.fermata.ui.activity.MainActivity.class);
            media.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT |
                Intent.FLAG_ACTIVITY_SINGLE_TOP);
            // Explicitly select the existing MediaEngine destination without changing
            // the vehicle navigator's WebView, OBD session or cached route.
            media.putExtra(EvCockpitMediaEngineDestination.EXTRA,destination);
            if("equalizer".equals(destination))media.putExtra("evcockpit_equalizer",true);
            startActivity(media);
        }catch(android.content.ActivityNotFoundException e){
            send("mediaengineStatus",record("media",
                "EV Cockpit medya ekranı bu sürümde bulunamadı."));
        }catch(SecurityException e){
            send("mediaengineStatus",record("media",
                "EV Cockpit medya ekranına erişim izni verilmedi."));
        }
    }

    /**
     * Media catalogue commands are explicit user taps, not remote URLs.
     * Subscription sites open inside the existing web add-on. Never copy account
     * cookies from other apps, decrypt streams or bypass parked-only
     * restrictions. MediaEngine audio/video effects remain in its own MediaSession.
     */
    private void openMediaEngineCatalogueItem(String item){
        if(destroyed || isFinishing() || isDestroyed())return;
        if(item==null)return;
        switch(item){
            case "netflix":
                openEmbeddedVideo("netflix");
                return;
            case "disney":
                openEmbeddedVideo("disney");
                return;
            case "prime":
                openEmbeddedVideo("prime");
                return;
            case "youtube":
            case "tv":
            case "mirror":
            case "media":
            case "radio":
            case "podcast":
            case "web":
            case "windasist":
            case "mediaengine":
            case "shortcuts":
            case "settings":
            case "equalizer":
                // Preserve original MediaEngine player and audio effects; route to
                // an existing fragment, never start a duplicate player.
                openMediaEngineDestination(item);
                return;
            default:
                send("mediaengineStatus",record("media","Bilinmeyen medya kısayolu."));
        }
    }

    /**
     * Fail closed: unknown/stale/inaccurate GPS is NOT evidence of parking.
     * A stationary fix only means motion is not currently observed, and cannot
     * certify Park gear, braking, passenger identity or Android Auto safety.
     */
    private boolean videoLaunchBlockedByMotion(){
        Location p=position;
        if(p==null || !p.hasAccuracy() || p.getAccuracy()>35 ||
           !p.hasSpeed() || !Float.isFinite(p.getSpeed()))return true;
        long elapsed=System.currentTimeMillis()-p.getTime();
        return elapsed<0 || elapsed>15000 || p.getSpeed()>0.4f;
    }

    private void openEmbeddedVideo(String target){
        if(videoLaunchBlockedByMotion()){
            send("mediaengineStatus",record("media",
                "Video açılışı için güncel, doğru ve hareketsiz GPS gerekli. Araç parkta olmalıdır."));
            return;
        }
        // No external app or browser launch: use the existing MediaEngine WebBrowserFragment.
        openMediaEngineDestination(target);
    }

    private JSONObject record(String status,String detail) {
        JSONObject j=new JSONObject();
        try{j.put("status",status);j.put("detail",detail);}catch(Exception ignored){}
        return j;
    }
    private final class Bridge {
        @JavascriptInterface public void location() {ui.post(()->requestGps());}
        /** Phone media Activity handoff only; never overrides driving locks. */
        @JavascriptInterface public void openMedia(){
            ui.post(()->openMediaSafely());
        }
        @JavascriptInterface public void openOriginalMedia(){
            // Fixed user-initiated action; no caller-supplied component names.
            ui.post(()->openOriginalMediaUnmodified());
        }
        @JavascriptInterface public void openMediaEngineCard(String id){
            if(id==null || id.length()>24)return;
            ui.post(()->openMediaEngineCatalogueItem(id));
        }
        @JavascriptInterface public void refreshJourneyWeather(){
            ui.post(()->{
                JSONObject route=activeWeatherRoute;
                long now=System.currentTimeMillis();
                if(route==null){send("mediaengineJourneyWeatherError",record("weather",
                    "Önce bir rota oluştur."));return;}
                if(now-lastJourneyWeatherRefreshAt<120000){
                    send("mediaengineJourneyWeatherLoading",record("weather",
                        "Rota hava modeli az önce istendi; otomatik yenileme açık."));return;
                }
                lastJourneyWeatherRefreshAt=now;
                final int activeGeneration=generation;
                send("mediaengineJourneyWeatherLoading",record("weather",
                    "Rota boyunca güncel tahminler yenileniyor."));
                routeWeatherJobs.execute(()->loadJourneyWeather(route,activeGeneration));
            });
        }
        @JavascriptInterface public void refreshWeather(){
            ui.post(()->{
                Location loc=position;
                if(loc!=null && System.currentTimeMillis()-loc.getTime()<=30000
                   && loc.getAccuracy()<=60){
                    fetchCurrentWeather(loc.getLatitude(),loc.getLongitude(),true,true);
                }else if(cachedAlternatives!=null && cachedAlternatives.length()>0){
                    try{
                        JSONArray pts=cachedAlternatives.getJSONObject(0)
                            .getJSONObject("geometry").getJSONArray("coordinates");
                        JSONArray first=pts.getJSONArray(0);
                        fetchCurrentWeather(first.getDouble(1),first.getDouble(0),
                            false,true);
                    }catch(Exception ignored){send("mediaengineCurrentWeatherError",
                        record("weather","Rota başlangıcı okunamadı."));}
                }else send("mediaengineCurrentWeatherError",
                    record("weather","Hava için GPS konumu veya rota gerekiyor."));
            });
        }
        @JavascriptInterface public void landscape(boolean enabled){
            ui.post(()->{
                if(destroyed || isFinishing())return;
                getSharedPreferences("evcockpit_presentation",MODE_PRIVATE).edit()
                    .putBoolean("landscape",enabled).apply();
                setRequestedOrientation(enabled?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                send("mediaengineStatus",record("display",
                    enabled?"Yatay tam ekran açılıyor.":"Ekran yönü serbest bırakıldı."));
            });
        }
        @JavascriptInterface public void selectObd() {ui.post(()->showObdSettings());}
        @JavascriptInterface public void obdSettings(){ui.post(()->showObdSettings());}
        @JavascriptInterface public void setScenarioSoc(int pct){
            if(pct!=80&&pct!=100)return;
            ui.post(()->{
                if(destroyed)return;
                getSharedPreferences("evcockpit_soc_scenario_v1",MODE_PRIVATE).edit()
                    .putInt("start_soc",pct).apply();
                sendScenarioStatus();
                refreshScenarioRoutes();
                send("mediaengineStatus",record("energy",
                    "%"+pct+" başlangıç değeri senaryodur; ölçülmüş batarya SoC değildir."));
            });
        }
        @JavascriptInterface public void disconnectObd(){
            ui.post(()->{
                getSharedPreferences("evcockpit_obd_pairing_v1",MODE_PRIVATE).edit()
                    .putBoolean("manual_disconnect",true).apply();
                telemetry.stop();
                send("mediaengineStatus",record("obd",
                    "OBD bağlantısı bırakıldı. Car Scanner artık adaptöre bağlanabilir."));
            });
        }
        @JavascriptInterface public void visibleStations(
              double south,double west,double north,double east){
            requestVisibleStations(south,west,north,east,false);
        }
        @JavascriptInterface public void visibleStationsFiltered(
              double south,double west,double north,double east,boolean onlyCcs){
            requestVisibleStations(south,west,north,east,onlyCcs);
        }
        @JavascriptInterface public void saveStation(String title,String operator){
            if(title!=null && title.length()<=160 && operator!=null && operator.length()<=120)
                EvCockpitEnergyData.saveStation(EvCockpitNavigatorActivity.this,title,operator);
        }
        @JavascriptInterface public void back() {
            ui.post(()->{
                if(isTaskRoot()){
                    startActivity(new Intent(EvCockpitNavigatorActivity.this,
                        me.aap.fermata.ui.activity.MainActivity.class));
                }
                finish();
            });
        }
        @JavascriptInterface public void configureTomTom() {
            ui.post(()->showTomTomKeyDialog());
        }
        @JavascriptInterface public void stations() {
            // The user's dock must never navigate to an independent Activity:
            // preserve the route, MediaEngine media state and existing WebView.
            ui.post(()->{
                if(!destroyed)send("mediaengineOpenStations",
                    record("stations","EPDK şarj katmanı mevcut haritada açılıyor."));
            });
        }
        @JavascriptInterface public void tripJournal() {ui.post(()->showTripJournal());}
        @JavascriptInterface public void roadSafety(double south,double west,
                                                   double north,double east){
            ui.post(()->requestSafetyCameraViewport(south,west,north,east));
        }
        @JavascriptInterface public void safetyAnnounce(double cameraLat,
                double cameraLon){
            ui.post(()->{
                if(destroyed||!voice||position==null||!position.hasAccuracy()||
                   position.getAccuracy()>35)return;
                long now=System.currentTimeMillis();
                if(now-position.getTime()<0||now-position.getTime()>15000 ||
                   now-lastSafetySpeechAt<120000)return;
                if(!valid(cameraLat,cameraLon))return;
                float[] distance=new float[1];
                Location.distanceBetween(position.getLatitude(),
                    position.getLongitude(),cameraLat,cameraLon,distance);
                if(distance[0]<=350){
                    lastSafetySpeechAt=now;
                    announce("OpenStreetMap kaydına göre ileride sabit hız kamerası olabilir. Hız sınırına uyun.");
                }
            });
        }
        @JavascriptInterface public void sensors() {
            // Avoid the separate legacy OBD Activity which can terminate on some
            // Android 15 devices. This in-place dialog cannot drop the cockpit.
            ui.post(()->showObdSettings());
        }
        @JavascriptInterface public void voice(boolean on) {
            ui.post(()->{
                voice=on;
                getSharedPreferences("eya_voice_v1",MODE_PRIVATE).edit()
                    .putBoolean("enabled",on).apply();
                if(!on){if(tts!=null)tts.stop();releaseSpeechFocus();}
                sendVoiceState();
                send("mediaengineStatus",record("voice",!on?"Sesli yönlendirme kapalı.":
                    ttsReady?"Sesli yönlendirme açık.":
                    "Türkçe ses paketi eksik. Sesli yönlendirme kullanılamıyor."));
            });
        }
        @JavascriptInterface public void voiceVolume(float volume){
            if(!Float.isFinite(volume)||volume<0f||volume>1f)return;
            ui.post(()->{
                speechVolume=volume;
                getSharedPreferences("eya_voice_v1",MODE_PRIVATE).edit()
                    .putFloat("volume",volume).apply();
                sendVoiceState();
            });
        }
        @JavascriptInterface public void stop() {
            ui.post(()->{
                if(destroyed)return;
                running=false;generation++;stationRequestId++;routeStationRequestId++;
                cachedAlternatives=null;activeIncidentRoute=null;
                lastIncidentPollAt=0;steps.clear();nextStep=0;
            });
        }
        @JavascriptInterface public void suggest(String field,String query,int requestId){
            ui.post(()->{if(!destroyed)suggestPlaces(field,query,requestId);});
        }
        @JavascriptInterface public void route(double fromLat,double fromLon,
              double toLat,double toLon,boolean useGps){
            ui.post(()->{if(!destroyed)plan(fromLat,fromLon,toLat,toLon,useGps);});
        }
        @JavascriptInterface public void chooseAlternative(int index){
            ui.post(()->{
                if(destroyed)return;
                JSONArray available=cachedAlternatives;
                if(available==null||index<0||index>=available.length()){
                    send("mediaengineStatus",record("route","Alternatif rota güncel değil."));
                    return;
                }
                int selectionGeneration=++generation;
                routeStationRequestId++;
                final boolean gps=cachedRouteGps,stop=cachedHasStop;
                jobs.execute(()->{
                    if(destroyed||selectionGeneration!=generation)return;
                    try{renderRouteOption(available,index,gps,stop,selectionGeneration);}
                    catch(Exception e){
                        send("mediaengineStatus",record("error",
                            "Alternatif rota açılamadı: "+e.getClass().getSimpleName()));
                    }
                });
            });
        }
        @JavascriptInterface public void routeVia(double fromLat,double fromLon,
              double viaLat,double viaLon,double toLat,double toLon,boolean useGps){
            if(!valid(viaLat,viaLon)){
                send("mediaengineStatus",record("error","Şarj mediaenginesı konumu geçersiz."));return;
            }
            ui.post(()->{if(!destroyed)planWithStop(fromLat,fromLon,toLat,toLon,useGps,viaLat,viaLon);});
        }
    }
    /** The TomTom browser token stays in app-private storage (Android backup
     * is disabled). Browser keys are recoverable in principle: use a narrow
     * TomTom API restriction and spending cap; never log the token. */
    /**
     * Read user-owned key encrypted at rest. Move the previous private but
     * unencrypted preference into Keystore storage once, then erase it.
     * If encryption cannot be performed, fail closed and ask for a new key.
     */
    private String storedTomTomKey(){
        String secure=EvCockpitApiKeyStore.get(this);
        if(secure!=null)return secure;
        SharedPreferences legacy=getSharedPreferences("evcockpit_traffic_keys",MODE_PRIVATE);
        String previous=legacy.getString("tomtom",null);
        if(previous==null)return null;
        boolean moved=EvCockpitApiKeyStore.valid(previous)&&EvCockpitApiKeyStore.set(this,previous);
        legacy.edit().remove("tomtom").commit();
        return moved?previous:null;
    }

    private void showTomTomKeyDialog(){
        if(destroyed||isFinishing())return;
        EditText field=new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(15);
        // Do not reveal a previously saved secret in an editable text field.
        field.setInputType(InputType.TYPE_CLASS_TEXT|
            InputType.TYPE_TEXT_VARIATION_PASSWORD);
        boolean alreadySaved=storedTomTomKey()!=null;
        field.setHint(alreadySaved?"Yeni anahtar girerek değiştir":"TomTom API anahtarını gir");
        new AlertDialog.Builder(this)
            .setTitle("TomTom Orbis API anahtarı")
            .setMessage((alreadySaved?"Anahtar bu cihazda şifreli kayıtlı. ":"")+
                "Kendi TomTom API anahtarını gir. Ücretsiz kota ve yetkileri "+
                "TomTom hesabından kontrol et. Anahtar APK'ya gömülmez; "+
                "Android Keystore kullanılır. Silersen tekrar girmen gerekir.")
            .setView(field)
            .setNegativeButton("Vazgeç",null)
            .setNeutralButton("Sil",(dialog,which)->{
                EvCockpitApiKeyStore.clear(this);
                getSharedPreferences("evcockpit_traffic_keys",MODE_PRIVATE)
                    .edit().remove("tomtom").commit();
                if(web!=null&&!destroyed)web.evaluateJavascript(
                    "window.EvCockpitTrafficProviders&&window.EvCockpitTrafficProviders.setTomTomKey('');"+
                    "window.EvCockpitBaseMap&&window.EvCockpitBaseMap.setTomTomKey('');",null);
                send("mediaengineStatus",record("traffic","TomTom anahtarı temizlendi."));
            })
            .setPositiveButton("Kaydet",(dialog,which)->{
                String key=field.getText().toString().trim();
                if(!EvCockpitApiKeyStore.valid(key)){
                    send("mediaengineStatus",record("traffic","TomTom anahtar biçimi geçersiz (15-200 karakter)."));
                    return;
                }
                if(!EvCockpitApiKeyStore.set(this,key)){
                    send("mediaengineStatus",record("traffic","Anahtar şifreli kaydedilemedi. Tekrar dene."));
                    return;
                }
                // Do not leave the original plaintext copy behind after update.
                getSharedPreferences("evcockpit_traffic_keys",MODE_PRIVATE)
                    .edit().remove("tomtom").commit();
                applyLocalTomTomKey();
                send("mediaengineStatus",record("traffic","TomTom anahtarı şifreli kaydedildi. Canlı trafik bağlantısı deneniyor."));
            }).show();
    }

    private void applyLocalTomTomKey(){
        if(web==null||destroyed)return;
        String key=storedTomTomKey();
        if(EvCockpitApiKeyStore.valid(key)){
            // The raster HTTP API requires a URL query parameter: this
            // runtime handoff does not turn a browser-visible key into a
            // nonextractable secret. There is deliberately no JS getter.
            web.evaluateJavascript("window.EvCockpitTrafficProviders&&window.EvCockpitTrafficProviders.setTomTomKey("+
                JSONObject.quote(key)+");"+
                "window.EvCockpitBaseMap&&window.EvCockpitBaseMap.setTomTomKey("+
                JSONObject.quote(key)+");"+
                "window.EvCockpitTrafficProviders&&window.EvCockpitTrafficProviders.activate('tomtom');",null);
        }
    }
    /** Only known, freshly read ECU measurements reach the map HUD. */
    private JSONObject obdPacket(EvCockpitLiveTelemetry.Data d) {
        JSONObject p=new JSONObject();
        EvCockpitLiveTelemetry.Data shown=d.displayAt(System.currentTimeMillis());
        boolean fresh=d.connected && d.updatedAt>0 &&
            System.currentTimeMillis()-d.updatedAt<10000;
        try{
            p.put("fresh",fresh);
            p.put("soc",fresh?shown.soc:"—");
            p.put("voltage",fresh?shown.voltage:"—");
            p.put("temperature",fresh?shown.batteryTemp:"—");
            p.put("chargeMode",fresh?shown.chargeMode:"—");
            p.put("status",d.status);
            p.put("measuredAtMs",fresh && d.socAt>0?d.socAt:0L);
            p.put("socAt",fresh && d.socAt>0?d.socAt:0L);
            p.put("voltageAt",fresh && d.voltageAt>0?d.voltageAt:0L);
            p.put("batTempAt",fresh && d.batteryTempAt>0?d.batteryTempAt:0L);
            p.put("modeAt",fresh && d.chargeModeAt>0?d.chargeModeAt:0L);
            p.put("ts",System.currentTimeMillis());
        }catch(Exception ignored){}
        return p;
    }
    private void sendVoiceState(){
        try{send("mediaengineVoiceState",new JSONObject().put("enabled",voice)
            .put("available",ttsReady).put("volume",speechVolume)
            .put("ts",System.currentTimeMillis()));
        }catch(Exception ignored){}
    }
    private int scenarioSoc(){
        int stored=getSharedPreferences("evcockpit_soc_scenario_v1",MODE_PRIVATE)
            .getInt("start_soc",80);
        return stored==100?100:80;
    }
    private void sendScenarioStatus(){
        EvCockpitEnergyData.Snapshot reading=EvCockpitEnergyData.load(this,System.currentTimeMillis());
        JSONObject j=new JSONObject();
        try{
            j.put("measured",reading.hasSoc());
            j.put("percent",reading.hasSoc()?reading.soc:scenarioSoc());
            j.put("userSelected",getSharedPreferences("evcockpit_soc_scenario_v1",
                MODE_PRIVATE).contains("start_soc"));
        }catch(Exception ignored){}
        send("mediaengineSocScenario",j);
    }
    /**
     * The instant, local estimate MUST NOT wait for EPDK download, catalogue
     * parsing or four expensive route-corridor scans. Returning cached source
     * station counts for a new SoC is also unsafe, so mark them unknown here.
     * An asynchronous EPDK enrichment may replace these cards afterwards.
     */
    private void sendFastChargePreview(JSONArray options,int selectedIndex,int version,
                                       int stationTicket){
        if(options==null||options.length()==0)return;
        try{
            EvCockpitEnergyData.Snapshot reading=EvCockpitEnergyData.load(this,System.currentTimeMillis());
            double measured=reading.hasSoc()?reading.soc:Double.NaN;
            int assumption=scenarioSoc();
            int count=Math.min(12,options.length());
            JSONArray rows=new JSONArray();
            int shortestIndex=0,fastestIndex=0;
            double shortest=Double.POSITIVE_INFINITY,fastest=Double.POSITIVE_INFINITY;
            for(int i=0;i<count;i++){
                JSONObject route=options.optJSONObject(i);
                if(route==null)continue;
                double km=route.optDouble("distance",Double.NaN)/1000.0;
                double minutes=route.optDouble("duration",Double.NaN)/60.0;
                if(!Double.isFinite(km)||km<=0||km>4000||
                   !Double.isFinite(minutes)||minutes<=0)continue;
                if(km<shortest){shortest=km;shortestIndex=i;}
                if(minutes<fastest){fastest=minutes;fastestIndex=i;}
                EvCockpitTripChargePlan.Estimate estimate=EvCockpitTripChargePlan.estimate(
                    km,measured,"",java.time.LocalDate.now(),assumption);
                JSONObject row=new JSONObject()
                    .put("index",i)
                    .put("km",Math.round(km*10.0)/10.0)
                    .put("minutes",Math.round(minutes))
                    .put("routePreference",route.optString("evcockpitRoutePreference","standard"))
                    .put("tollVerified",false)
                    .put("energyLowKwh",Math.round(km*1.6)/10.0)
                    .put("energyHighKwh",Math.round(km*2.4)/10.0)
                    .put("socKnown",estimate.measuredSoc)
                    .put("socAssumed",!estimate.measuredSoc)
                    .put("scenarioStartSoc",estimate.startSoc)
                    .put("chargeStopsMin",estimate.minStops)
                    .put("chargeStopsMax",estimate.maxStops)
                    .put("chargeNeededModel",estimate.maxStops>0)
                    .put("chargeLowKwh",Math.round(estimate.chargeMinKwh*10)/10.0)
                    .put("chargeHighKwh",Math.round(estimate.chargeMaxKwh*10)/10.0)
                    .put("stationsKnown",false)
                    .put("preference",route.optString("evcockpitRoutePreference","standard"))
                    .put("tollVerified",route.optBoolean("evcockpitTollVerified",false));
                if(estimate.priceEstimateAvailable){
                    row.put("costMinTry",estimate.costMinTry)
                       .put("costMaxTry",estimate.costMaxTry)
                       .put("priceBasis",estimate.priceBasis)
                       .put("tariffSource",estimate.priceSource)
                       .put("priceType","market-scenario")
                       .put("tariffChecked","2026-10-16");
                }
                rows.put(row);
            }
            JSONObject response=new JSONObject().put("options",rows)
                .put("selectedIndex",selectedIndex)
                .put("fastestIndex",fastestIndex)
                .put("shortestIndex",shortestIndex)
                .put("chargeAwareIndex",-1)
                .put("routeGeneration",version)
                .put("stationTicket",stationTicket)
                .put("livePrices",false).put("liveAvailability",false)
                .put("fastPreview",true);
            if(!destroyed&&generation==version&&
               routeStationRequestId==stationTicket)
                send("mediaengineAlternatives",response);
        }catch(Exception e){
            send("mediaengineAlternativeError",record("alternatives",
                "Şarj senaryosu hesaplanamadı: "+e.getClass().getSimpleName()));
        }
    }

    private void refreshScenarioRoutes(){
        JSONArray options=cachedAlternatives;
        if(options==null||options.length()==0)return;
        int ticket=++routeStationRequestId;
        int version=generation;
        int index=selectedAlternativeIndex;
        boolean stop=cachedHasStop;
        // Immediate value update, before any slow EPDK work.
        sendFastChargePreview(options,index,version,ticket);
        stationJobs.execute(()->loadAlternativeCards(options,version,ticket,index,stop));
        JSONObject choice=options.optJSONObject(index);
        if(choice!=null)stationJobs.execute(()->loadRouteStations(choice,version,ticket));
    }
    private void promptScenarioIfMissing(){
        if(socPromptShown||destroyed||isFinishing())return;
        if(EvCockpitEnergyData.load(this,System.currentTimeMillis()).hasSoc())return;
        socPromptShown=true;
        new AlertDialog.Builder(this)
            .setTitle("Başlangıç bataryası · OBD SoC okunamadı")
            .setMessage("Şarj ve TL tahmini için başlangıç şarjını seç. Gerçek araç ölçümü değildir.")
            .setSingleChoiceItems(new String[]{
                "%80 ile başlayacağım", "%100 ile başlayacağım"},
                scenarioSoc()==100?1:0,(dialog,choice)->{
                    dialog.dismiss();
                    int percent=choice==1?100:80;
                    getSharedPreferences("evcockpit_soc_scenario_v1",MODE_PRIVATE).edit()
                        .putInt("start_soc",percent).apply();
                    sendScenarioStatus();
                    refreshScenarioRoutes();
                })
            .setNegativeButton("Şimdi değil",null).show();
    }
    private void showObdSettings(){
        if(destroyed||isFinishing())return;
        String mac=getSharedPreferences("evcockpit_obd_pairing_v1",MODE_PRIVATE)
            .getString("paired_mac",null);
        String device=mac==null?"Henüz adaptör seçilmedi":"Kaydedilen adaptör: "+mac;
        String status=latestObd==null?"OBD bağlı değil":latestObd.status;
        new AlertDialog.Builder(this)
            .setTitle("EV Cockpit · OBD bağlantısı")
            .setMessage(device+"\n"+status+"\n\n"+
                "Telefon Bluetooth ayarlarından ELM327'yi eşleştir. Car Scanner "
                +"bağlıysa önce bağlantısını kes; iki uygulama aynı Bluetooth soketini "
                +"paylaşamaz. ECU'ya yazma veya kodlama yapılmaz.")
            .setPositiveButton("Adaptör seç / bağlan",(d,w)->selectObdAdapter())
            .setNeutralButton("Bluetooth ayarları",(d,w)->{
                try{
                    startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
                }catch(Exception e){
                    send("mediaengineStatus",record("obd","Bluetooth ayarları açılamadı."));
                }
            })
            .setNegativeButton("Kapat",null).show();
    }
    /** Explicit first selection; only paired adapters, no background scan. */
    @SuppressWarnings("deprecation")
    private void selectObdAdapter() {
        if(Build.VERSION.SDK_INT>=31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},72);return;
        }
        try{
            BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
            if(adapter==null || !adapter.isEnabled()){
                send("mediaengineTelemetry",obdStatus("Önce Bluetooth'u açıp ELM327'yi eşleştir. Car Scanner bağlantısını kapat."));return;
            }
            Set<BluetoothDevice> paired=adapter.getBondedDevices();
            if(paired==null || paired.isEmpty()){
                send("mediaengineTelemetry",obdStatus("Eşleştirilmiş OBD cihazı bulunamadı."));return;
            }
            List<BluetoothDevice> devices=new ArrayList<>(paired);
            String[] labels=new String[devices.size()];
            for(int i=0;i<devices.size();i++){
                String name=devices.get(i).getName();
                labels[i]=name==null?"Eşleştirilmiş cihaz "+(i+1):name;
            }
            new AlertDialog.Builder(this)
                .setTitle("OBD adaptörünü seç")
                .setItems(labels,(dialog,index)->{
                    BluetoothDevice d=devices.get(index);
                    getSharedPreferences("evcockpit_obd_pairing_v1",MODE_PRIVATE).edit()
                        .putString("paired_mac",d.getAddress())
                        .putBoolean("manual_disconnect",false).apply();
                    telemetry.start(d);
                })
                .setNegativeButton("Vazgeç",null).show();
        }catch(SecurityException e){
            send("mediaengineTelemetry",obdStatus("Bluetooth erişim izni gerekli."));
        }
    }
    private JSONObject obdStatus(String status){
        JSONObject j=obdPacket(new EvCockpitLiveTelemetry.Data());
        try{j.put("status",status);}catch(Exception ignored){}
        return j;
    }
    @SuppressWarnings("deprecation")
    private void autoConnectObd(){
        if(getSharedPreferences("evcockpit_obd_pairing_v1",MODE_PRIVATE)
            .getBoolean("manual_disconnect",false))return;
        if(Build.VERSION.SDK_INT>=31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return;
        String address=getSharedPreferences("evcockpit_obd_pairing_v1",MODE_PRIVATE)
            .getString("paired_mac",null);
        if(address==null)return;
        try{
            BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
            if(adapter==null || !adapter.isEnabled())return;
            for(BluetoothDevice d:adapter.getBondedDevices()){
                if(address.equals(d.getAddress())){telemetry.start(d);return;}
            }
        }catch(SecurityException ignored){}
    }

    private void requestVisibleStations(double south,double west,double north,double east,
                                        boolean onlyCcs){
        final int request=++stationRequestId;
        stationJobs.execute(()->{
            try{
                JSONObject packet=stationCatalog.inBounds(south,west,north,east,onlyCcs);
                if(!destroyed && request==stationRequestId)send("mediaengineStations",packet);
            }catch(Exception e){
                if(!destroyed && request==stationRequestId)
                    send("mediaengineStationError",record("stations",
                        "EPDK istasyon verisi alınamadı: "+e.getClass().getSimpleName()));
            }
        });
    }
    private void loadRouteStations(JSONObject route,int routeGeneration,int stationRequest){
        try{
            JSONArray coords=route.getJSONObject("geometry").getJSONArray("coordinates");
            ArrayList<EvCockpitStationCatalog.Point> points=new ArrayList<>();
            int stride=Math.max(1,coords.length()/2400);
            for(int i=0;i<coords.length();i+=stride){
                JSONArray coord=coords.getJSONArray(i);
                points.add(new EvCockpitStationCatalog.Point(coord.getDouble(1),coord.getDouble(0)));
            }
            if(coords.length()>1){
                JSONArray finalPt=coords.getJSONArray(coords.length()-1);
                points.add(new EvCockpitStationCatalog.Point(finalPt.getDouble(1),finalPt.getDouble(0)));
            }
            EvCockpitEnergyData.Snapshot snapshot=EvCockpitEnergyData.load(this,System.currentTimeMillis());
            double soc=snapshot.hasSoc()?snapshot.soc:scenarioSoc();
            double roadKm=route.getDouble("distance")/1000.0;
            JSONObject packet=stationCatalog.alongRoute(points,roadKm,soc);
            packet.put("socAssumed",!snapshot.hasSoc());
            packet.put("routeGeneration",routeGeneration);
            packet.put("scenarioStartSoc",soc);
            if(route.getJSONArray("legs").length()>1){
                packet.remove("suggested"); // already travelling via a stop; avoid second suggestion
                packet.put("stopIncluded",true);
            }
            if(!destroyed && generation==routeGeneration && routeStationRequestId==stationRequest)
                send("mediaengineStations",packet);
        }catch(Exception e){
            if(!destroyed && generation==routeGeneration && routeStationRequestId==stationRequest)
                send("mediaengineStationError",record("stations",
                    "Rota çevresi EPDK verisi yüklenemedi: "+e.getClass().getSimpleName()));
        }
    }
    private void loadAlternativeCards(JSONArray alternatives,int routeGeneration,
                                      int stationTicket,int selectedIndex,boolean hasStop){
        JSONArray rows=new JSONArray();
        double fastest=Double.POSITIVE_INFINITY,shortest=Double.POSITIVE_INFINITY;
        int fastIndex=0,shortIndex=0,chargeIndex=-1;
        double chargeScore=Double.NEGATIVE_INFINITY;
        EvCockpitEnergyData.Snapshot facts=EvCockpitEnergyData.load(this,System.currentTimeMillis());
        double soc=facts.hasSoc()?facts.soc:Double.NaN;
        int assumption=scenarioSoc();
        int count=Math.min(12,alternatives.length());
        try{
            for(int k=0;k<count;k++){
                JSONObject route=alternatives.getJSONObject(k);
                double roadKm=route.getDouble("distance")/1000.0;
                double minutes=route.getDouble("duration")/60.0;
                if(roadKm<=0||roadKm>5000||minutes<=0)continue;
                if(minutes<fastest){fastest=minutes;fastIndex=k;}
                if(roadKm<shortest){shortest=roadKm;shortIndex=k;}
                JSONObject item=new JSONObject();
                item.put("index",k).put("km",Math.round(roadKm*10)/10.0)
                    .put("minutes",Math.round(minutes))
                    .put("routePreference",route.optString("evcockpitRoutePreference","standard"))
                    .put("tollVerified",false)
                    .put("energyLowKwh",Math.round(roadKm*.16*10)/10.0)
                    .put("energyHighKwh",Math.round(roadKm*.24*10)/10.0)
                    .put("socKnown",Double.isFinite(soc))
                    .put("ccsCount",0).put("reachableCcsModel",false)
                    .put("preference",route.optString("evcockpitRoutePreference","standard"))
                    .put("tollVerified",route.optBoolean("evcockpitTollVerified",false));
                // Display an EXPLICIT 80% assumption when BMS SoC is missing.
                // Charge count models energy-equivalent stops, not real socket
                // availability or a verified detour to a specific charger.
                EvCockpitTripChargePlan.Estimate plan=EvCockpitTripChargePlan.estimate(
                    roadKm,soc,"",java.time.LocalDate.now(),assumption);
                item.put("socAssumed",!plan.measuredSoc);
                item.put("scenarioStartSoc",plan.startSoc);
                item.put("chargeNeededModel",plan.maxStops>0);
                item.put("chargeStopsMin",plan.minStops)
                    .put("chargeStopsMax",plan.maxStops);
                item.put("chargeLowKwh",Math.round(plan.chargeMinKwh*10)/10.0)
                    .put("chargeHighKwh",Math.round(plan.chargeMaxKwh*10)/10.0);
                if(plan.priceEstimateAvailable){
                    item.put("costMinTry",plan.costMinTry)
                        .put("costMaxTry",plan.costMaxTry)
                        .put("priceBasis",plan.priceBasis)
                        .put("tariffSource",plan.priceSource)
                        .put("priceType","market-scenario")
                        .put("tariffChecked","2026-10-16");
                }
                try{
                    JSONArray coords=route.getJSONObject("geometry").getJSONArray("coordinates");
                    ArrayList<EvCockpitStationCatalog.Point> line=new ArrayList<>();
                    int stride=Math.max(1,coords.length()/2000);
                    for(int j=0;j<coords.length();j+=stride){
                        JSONArray c=coords.getJSONArray(j);
                        line.add(new EvCockpitStationCatalog.Point(c.getDouble(1),c.getDouble(0)));
                    }
                    if(coords.length()>1){
                        JSONArray last=coords.getJSONArray(coords.length()-1);
                        line.add(new EvCockpitStationCatalog.Point(last.getDouble(1),last.getDouble(0)));
                    }
                    double routePlanningSoc=Double.isFinite(soc)?soc:assumption;
                    JSONObject stationData=stationCatalog.alongRoute(
                        line,roadKm,routePlanningSoc);
                    item.put("stationsKnown",true);
                    item.put("ccsCount",stationData.optInt("total",0));
                    item.put("ccsCoverage","20 km azami kuş uçuşu koridor, erişim doğrulanmadı");
                    JSONObject suggested=stationData.optJSONObject("suggested");
                    if(suggested!=null&&!hasStop){
                        item.put("reachableCcsModel",true);
                        item.put("firstCcs",suggested.optString("title"));
                        item.put("firstCcsOperator",suggested.optString("operator"));
                        item.put("firstCcsKm",suggested.optDouble("routeProgressKm",0));
                        double score=1000.0-Math.abs(roadKm-shortest)*.05-
                            minutes*.18+Math.min(100,stationData.optInt("total",0))*.25;
                        if(score>chargeScore){chargeScore=score;chargeIndex=k;}
                        // Prefer a still-current published network tariff if
                        // available; otherwise retain the dated market scenario.
                        EvCockpitTripChargePlan.Estimate networkPlan=EvCockpitTripChargePlan.estimate(
                            roadKm,soc,suggested.optString("operator"),
                            java.time.LocalDate.now(),assumption);
                        if(networkPlan.operatorRate&&networkPlan.priceEstimateAvailable){
                            item.put("costMinTry",networkPlan.costMinTry)
                                .put("costMaxTry",networkPlan.costMaxTry)
                                .put("priceBasis",networkPlan.priceBasis)
                                .put("tariffOperator",suggested.optString("operator"))
                                .put("tariffSource",networkPlan.priceSource)
                                .put("priceType","published-network")
                                .put("tariffChecked","2026-10-22");
                        }
                    }
                }catch(Exception unavailable){
                    item.put("stationsKnown",false);
                }
                rows.put(item);
            }
            JSONObject response=new JSONObject().put("options",rows)
                .put("selectedIndex",selectedIndex)
                .put("fastestIndex",fastIndex).put("shortestIndex",shortIndex)
                .put("chargeAwareIndex",chargeIndex)
                .put("livePrices",false).put("liveAvailability",false)
                .put("routeGeneration",routeGeneration)
                .put("stationTicket",stationTicket)
                .put("fastPreview",false)
                .put("modelNote","16–24 kWh/100 km, 52 kWh, %15 rezerv, DC hedef %80. "
                   +"OBD yoksa kullanıcı %80 / %100 senaryosu seçebilir. Maliyet kaynaklı pazar/ağ tahmini; "
                   +"CCS yakınlığı gerçek şarj durağı ve yol erişimi değildir.");
            if(!destroyed && generation==routeGeneration && routeStationRequestId==stationTicket)
                send("mediaengineAlternatives",response);
        }catch(Exception e){
            if(!destroyed && generation==routeGeneration && routeStationRequestId==stationTicket)
                send("mediaengineAlternativeError",record("alternatives",
                    "Alternatiflerin şarj analizi yüklenemedi."));
        }
    }

    // OSM public Overpass resources: only explicit user taps, 90s throttle,
    // bounded 0.42-degree viewport; never scrape a country-wide database.
    private void requestSafetyCameraViewport(double south,double west,
            double north,double east){
        if(destroyed)return;
        if(!EvCockpitRoadSafety.validViewport(south,west,north,east)){
            send("mediaengineSafetyError",record("safety",
                "Sabit kamera noktaları için haritayı yakınlaştır (12+)."));
            return;
        }
        long now=System.currentTimeMillis();
        if(now-lastSafetyQueryAt<90000){
            send("mediaengineSafetyError",record("safety",
                "OSM sorguları sınırlıdır. 90 saniye sonra yeniden dene."));
            return;
        }
        lastSafetyQueryAt=now;
        safetyJobs.execute(()->{
            try{
                JSONObject packet=EvCockpitRoadSafety.retrieve(south,west,north,east);
                if(!destroyed)send("mediaengineSafetyCameras",packet);
            }catch(Exception e){
                if(!destroyed)send("mediaengineSafetyError",record("safety",
                    "OSM sabit kamera verisi alınamadı (ağ veya kota)."));
            }
        });
    }

    private void requestGps() {
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},41);return;
        }
        try{
            gpsTrackingRequested=true; // User explicitly requested GPS at least once.
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1500,3,this);
            Location loc=locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if(loc!=null && System.currentTimeMillis()-loc.getTime()<90000)onLocationChanged(loc);
            send("mediaengineStatus",record("gps","Konum izni verildi. GPS aranıyor..."));
        }catch(Exception e){send("mediaengineStatus",record("error","GPS açılamadı"));}
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==72){
            if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED)selectObdAdapter();
            else send("mediaengineTelemetry",obdStatus("Bluetooth izni verilmedi."));
            return;
        }
        if(code==41 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED)requestGps();
        else if(code==41)send("mediaengineStatus",record("error","Konum izni olmadan canlı yönlendirme yapılamaz"));
    }
    @Override public void onLocationChanged(Location location) {
        if(destroyed)return;
        tripJournal.accept(location,System.currentTimeMillis());
        position=location;
        JSONObject j=new JSONObject();
        try{j.put("lat",location.getLatitude());j.put("lon",location.getLongitude());
            j.put("accuracy",location.getAccuracy());
            j.put("timeMs",location.getTime());
            // Speed comes ONLY from a recent GPS speed estimate; never infer
            // it from map geometry or assume a posted road speed limit.
            long age=System.currentTimeMillis()-location.getTime();
            if(location.hasSpeed() && location.hasAccuracy() &&
               location.getAccuracy()<=35 && age>=0&&age<=15000 &&
               Float.isFinite(location.getSpeed()) &&
               location.getSpeed()>=0&&location.getSpeed()<=70)
                j.put("speedKmh",Math.round(location.getSpeed()*3.6));}catch(Exception ignored){}
        send("mediaenginePosition",j);
        // Only fresh GPS fixes on the selected route request licensed incident
        // data. Polls are throttled and do not block GPS navigation or maps.
        maybeCheckTrafficIncidents(location);
        long liveFixAge=System.currentTimeMillis()-location.getTime();
        if(liveFixAge>=0 && liveFixAge<=30000 && location.getAccuracy()<=60)
            fetchCurrentWeather(location.getLatitude(),location.getLongitude(),true,false);
        long fixAge=System.currentTimeMillis()-location.getTime();
        if(!running || location.getAccuracy()>60 || fixAge<0 || fixAge>30000 ||
            nextStep>=steps.size())return;
        Step step=steps.get(nextStep);
        float[] meters=new float[1];
        Location.distanceBetween(location.getLatitude(),location.getLongitude(),step.lat,step.lon,meters);
        JSONObject maneuver=new JSONObject();
        try{
            maneuver.put("instruction",step.instruction);
            maneuver.put("straightLineMeters",Math.round(meters[0]));
            maneuver.put("fixTimeMs",location.getTime());
            maneuver.put("accuracy",location.getAccuracy());
            send("mediaengineManeuver",maneuver);
        }catch(Exception ignored){}
        // OSRM maneuver POINT proximity, not map-matched driving distance.
        // Never speak without a recent, accurate fix and a selected GPS route.
        if(!voice||!ttsReady||!gpsTrackingRequested||!location.hasAccuracy()||
           location.getAccuracy()>35 || fixAge>10000)return;
        final float remaining=meters[0];
        int tier=remaining<=35?3:remaining<=110?2:remaining<=330?1:
                 remaining<=1100?0:-1;
        if(tier>=0){
            int bit=1<<tier;
            long now=System.currentTimeMillis();
            if((step.spokenMask&bit)==0&&now-lastSpeech>1750){
                String prefix=tier==0?"Yaklaşık bir kilometre sonra, ":
                    tier==1?"Yaklaşık üç yüz metre sonra, ":
                    tier==2?"Yaklaşık yüz metre sonra, ":"Şimdi, ";
                announce(prefix+step.instruction);
                step.spokenMask|=bit;lastSpeech=now;
            }
            if(tier==3)nextStep++;
        }
    }

    /** Explicitly opt-in GPS-only trip summary, no storage of GPS track. */
    private void showTripJournal(){
        if(destroyed||isFinishing())return;
        JSONArray past=EvCockpitTripJournal.list(this);
        StringBuilder info=new StringBuilder();
        if(tripJournal.recording())
            info.append("● Kayıt etkin · ").append(tripJournal.distanceKm()).append(" km\\n\\n");
        else info.append("GPS kaydı kapalı\\n\\n");
        for(int i=0;i<Math.min(8,past.length());i++){
            JSONObject row=past.optJSONObject(i);
            if(row==null)continue;
            java.text.SimpleDateFormat fmt=new java.text.SimpleDateFormat(
                "dd.MM.yyyy HH:mm",new Locale("tr","TR"));
            info.append(fmt.format(new java.util.Date(row.optLong("startedAt"))))
                .append(" · ").append(row.optDouble("distanceKm"))
                .append(" km · ").append(row.optLong("durationMin"))
                .append(" dk\\n");
        }
        if(past.length()==0)info.append("Henüz GPS yolculuğu kaydedilmedi.\\n");
        info.append("\\nKonum ve güzergâh saklanmaz. Enerji ve maliyet ölçülmediyse boş bırakılır.");
        new AlertDialog.Builder(this).setTitle("EV Cockpit · Yolculuklar")
            .setMessage(info.toString())
            .setPositiveButton(tripJournal.recording()?"Kaydı durdur":"GPS kaydını başlat",
                (dialog,which)->{
                    if(tripJournal.recording()){
                        boolean saved=tripJournal.finish(this,System.currentTimeMillis());
                        send("mediaengineStatus",record("trip",saved?
                          "GPS yolculuğu kaydedildi.":"GPS mesafesi/süresi yetersiz; kayıt yok."));
                    }else if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=
                             PackageManager.PERMISSION_GRANTED){
                        requestGps();
                        send("mediaengineStatus",record("trip","GPS iznini verdikten sonra yeniden başlat."));
                    }else{
                        tripJournal.start(System.currentTimeMillis());
                        requestGps();
                        send("mediaengineStatus",record("trip","GPS yolculuk kaydı başladı."));
                    }
                })
            .setNeutralButton("Geçmişi temizle",(dialog,which)->
                new AlertDialog.Builder(this).setTitle("Yolculuk geçmişini sil?")
                    .setMessage("Yerel yolculuk özetleri silinir.")
                    .setNegativeButton("İptal",null)
                    .setPositiveButton("Sil",(d,w)->EvCockpitTripJournal.erase(this)).show())
            .setNegativeButton("Kapat",null).show();
    }

    private void releaseSpeechFocus(){
        if(speechAudio!=null && speechFocus!=null){
            speechAudio.abandonAudioFocusRequest(speechFocus);
            speechFocus=null;
        }
    }
    private void announce(String instruction){
        if(!voice||!ttsReady||tts==null||instruction==null||
            instruction.trim().isEmpty()||instruction.length()>160)return;
        ui.post(()->{
            if(!voice||!ttsReady||destroyed)return;
            releaseSpeechFocus();
            try{
                if(speechAudio!=null){
                    speechFocus=new android.media.AudioFocusRequest.Builder(
                        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()).build();
                    speechAudio.requestAudioFocus(speechFocus);
                }
                android.os.Bundle params=new android.os.Bundle();
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,speechVolume);
                tts.speak(instruction,TextToSpeech.QUEUE_FLUSH,params,"eya-route-guidance");
            }catch(Exception e){releaseSpeechFocus();}
        });
    }
    private void maybeCheckTrafficIncidents(Location gps){
        if(destroyed||!weatherForeground||!gpsTrackingRequested ||
           activeIncidentRoute==null||gps==null||!gps.hasAccuracy()||
           gps.getAccuracy()>35)return;
        long now=System.currentTimeMillis(),age=now-gps.getTime();
        if(age<0||age>15000||now-lastIncidentPollAt<INCIDENT_REFRESH_MS)return;
        String key=storedTomTomKey();
        if(!EvCockpitApiKeyStore.valid(key))return;
        lastIncidentPollAt=now;
        final int ticket=generation;
        final JSONArray road=activeIncidentRoute;
        final double lat=gps.getLatitude(),lon=gps.getLongitude();
        try{incidentJobs.execute(()->{
            try{
                JSONObject result=EvCockpitTrafficIncidents.retrieve(key,road,lat,lon)
                    .put("routeGeneration",ticket);
                if(destroyed||!weatherForeground||generation!=ticket)return;
                send("mediaengineTrafficIncidents",result);
                JSONArray rows=result.optJSONArray("items");
                if(rows==null||rows.length()==0||!voice)return;
                JSONObject nearest=null;double meters=Double.POSITIVE_INFINITY;
                for(int i=0;i<rows.length();i++){
                    JSONObject row=rows.optJSONObject(i);
                    if(row==null)continue;
                    double ahead=row.optDouble("aheadMeters",Double.NaN);
                    if(Double.isFinite(ahead)&&ahead>=350&&ahead<=6000&&ahead<meters){
                        nearest=row;meters=ahead;
                    }
                }
                if(nearest==null)return;
                String id=nearest.optString("id","");
                if(id.equals(lastSpokenIncidentId)||
                   System.currentTimeMillis()-lastIncidentSpeechAt<90000)return;
                final String category=nearest.optString("label","trafik olayı");
                final long approxKm=Math.max(1,Math.round(meters/1000));
                ui.post(()->{
                    Location current=position;
                    long t=System.currentTimeMillis();
                    if(destroyed||!weatherForeground||generation!=ticket||!voice||
                        current==null||current.getAccuracy()>35||
                        Math.abs(t-current.getTime())>15000||
                        id.equals(lastSpokenIncidentId))return;
                    // Proximity from road polyline does not prove same carriageway.
                    lastSpokenIncidentId=id;lastIncidentSpeechAt=t;
                    if(tts!=null)tts.speak("TomTom bildirimi: rota yakınında yaklaşık "+
                        approxKm+" kilometre ileride "+category+
                        " bulunuyor olabilir. Yol işaretlerini takip edin.",
                        TextToSpeech.QUEUE_ADD,null,"evcockpit-traffic-incident");
                });
            }catch(Exception e){
                if(!destroyed&&weatherForeground&&generation==ticket)
                    send("mediaengineTrafficIncidentError",record("traffic",
                        "TomTom olay ayrıntıları alınamadı; uyarı üretilmedi."));
            }
        });}catch(java.util.concurrent.RejectedExecutionException ignored){}
    }
    // Both departure and arrival use the same autocomplete lookup.
    // Queries only leave the phone after the user types 3+ characters.
    private void suggestPlaces(String field,String raw,int requestId){
        if(!"from".equals(field) && !"to".equals(field))return;
        final String query=raw==null?"":raw.trim();
        if(query.length()<3||query.length()>100)return;
        searchJobs.execute(()->{
            if(destroyed)return;
            JSONObject packet=new JSONObject();
            try{
                packet.put("field",field);
                packet.put("requestId",requestId);
                // The public Photon demo usually indexes English/local names, not 'tr'.
                // Invalid lang=tr previously caused 400, reported as 'no internet'.
                String u="https://photon.komoot.io/api/?limit=7&lang=en&q="+
                    URLEncoder.encode(query,"UTF-8");
                JSONArray found=new JSONObject(fetch(u,500000)).getJSONArray("features");
                JSONArray items=new JSONArray();
                for(int i=0;i<found.length() && items.length()<7;i++){
                    JSONObject feature=found.optJSONObject(i);
                    if(feature==null)continue;
                    JSONObject geom=feature.optJSONObject("geometry");
                    if(geom==null)continue;
                    JSONArray coords=geom.optJSONArray("coordinates");
                    if(coords==null || coords.length()<2)continue;
                    double lon=coords.optDouble(0,Double.NaN);
                    double lat=coords.optDouble(1,Double.NaN);
                    if(!valid(lat,lon))continue;
                    JSONObject props=feature.optJSONObject("properties");
                    if(props==null)continue;
                    String name=props.optString("name","");
                    String city=props.optString("city","");
                    String district=props.optString("district","");
                    String state=props.optString("state","");
                    String country=props.optString("country","");
                    StringBuilder label=new StringBuilder();
                    for(String part:new String[]{name,district,city,state,country}){
                        if(part.isEmpty())continue;
                        if(label.toString().contains(part))continue;
                        if(label.length()>0)label.append(", ");
                        label.append(part);
                    }
                    if(label.length()==0)continue;
                    JSONObject item=new JSONObject();
                    item.put("label",label.toString());
                    item.put("lat",lat);item.put("lon",lon);
                    items.put(item);
                }
                packet.put("items",items);
                if(items.length()==0)packet.put("error","Türkiye içinde eşleşen konum bulunamadı.");
            }catch(Exception e){
                try{
                    packet.put("items",new JSONArray());
                    packet.put("error","Photon ayrıntılı adres servisi şu an yanıt vermiyor; çevrimdışı iller kullanılabilir.");
                }catch(Exception ignored){}
            }
            send("mediaengineSuggestions",packet);
        });
    }
    private void plan(double fromLat,double fromLon,
                      double toLat,double toLon,boolean useGps) {
        planWithStop(fromLat,fromLon,toLat,toLon,useGps,Double.NaN,Double.NaN);
    }
    private void planWithStop(double fromLat,double fromLon,
                      double toLat,double toLon,boolean useGps,
                      double stopLat,double stopLon) {
        if(!valid(fromLat,fromLon)||!valid(toLat,toLon)){
            send("mediaengineStatus",record("error","Başlangıç ve varış için listeden konum seç."));return;
        }
        boolean verifiedGps=useGps && position!=null && position.getAccuracy()<=60 &&
            System.currentTimeMillis()-position.getTime()<=30000;
        final int myGeneration=++generation;
        cachedAlternatives=null;
        activeWeatherRoute=null;
        activeIncidentRoute=null;
        lastIncidentPollAt=0;
        lastSpokenIncidentId="";
        getSharedPreferences("evcockpit_route_weather_v1",MODE_PRIVATE).edit()
            .remove("packet").apply();
        running=false;
        routeStationRequestId++; // Invalidate the previous ROUTE charger batch only.
        jobs.execute(()->{
            if(destroyed||myGeneration!=generation)return;
            try{
                String a=String.format(Locale.US,"%.6f,%.6f",fromLon,fromLat);
                String b=String.format(Locale.US,"%.6f,%.6f",toLon,toLat);
                boolean hasStop=valid(stopLat,stopLon);
                String via=hasStop?
                    String.format(Locale.US,"%.6f,%.6f",stopLon,stopLat):"";
                String query="/route/v1/driving/"+a+(hasStop?";"+via:"")+";"+b+
                    "?overview=full&geometries=geojson&steps=true&alternatives=3";
                JSONObject out=null;
                StringBuilder problems=new StringBuilder();
                String trafficKey=storedTomTomKey();
                if(EvCockpitApiKeyStore.valid(trafficKey)){
                    try{
                        out=EvCockpitTomTomRouting.request(trafficKey,fromLat,fromLon,
                            toLat,toLon,stopLat,stopLon);
                        activeRouteProvider="TomTom Orbis v3 canlı trafik";
                    }catch(Exception e){
                        // Never put keys, full request URLs, or coordinates in logs.
                        problems.append("TomTom erişimi başarısız; OSRM yedek rota. ");
                    }
                }
                String[] hosts={"https://router.project-osrm.org",
                                "https://routing.openstreetmap.de/routed-car"};
                for(int attempt=0;attempt<hosts.length && out==null;attempt++){
                    try{
                        JSONObject candidate=new JSONObject(fetch(hosts[attempt]+query,3000000));
                        if(!"Ok".equals(candidate.optString("code"))){
                            throw new IllegalStateException("OSRM "+candidate.optString("code"));
                        }
                        JSONArray matches=candidate.optJSONArray("routes");
                        if(matches==null || matches.length()==0)throw new IllegalStateException("Boş güzergâh");
                        out=candidate;
                        activeRouteProvider=hosts[attempt];
                    }catch(Exception e){
                        if(problems.length()>0)problems.append(" | ");
                        problems.append("OSRM ").append(attempt+1).append(": ")
                            .append(routeFailure(e));
                    }
                }
                // FOSSGIS Valhalla is a separate open-source road graph/router.
                // Ask only when primary routing produced fewer than 3 choices.
                // A live TomTom route must never be silently mixed with traffic-free
                // alternatives and presented as traffic-aware.
                if(out==null || (!activeRouteProvider.startsWith("TomTom Orbis")
                        && out.optJSONArray("routes")!=null
                        && out.optJSONArray("routes").length()<3)){
                    try{
                        JSONArray supplemental=EvCockpitValhallaRouting.alternatives(
                            fromLat,fromLon,toLat,toLon,stopLat,stopLon);
                        if(out==null){
                            if(supplemental.length()>0){
                                out=new JSONObject().put("routes",supplemental);
                                activeRouteProvider="Valhalla (trafiksiz)";
                            }
                        }else{
                            JSONArray existing=out.getJSONArray("routes");
                            int added=0;
                            for(int i=0;i<supplemental.length()&&existing.length()<6;i++){
                                JSONObject option=supplemental.getJSONObject(i);
                                boolean isNew=true;
                                for(int j=0;j<existing.length();j++){
                                    if(!EvCockpitValhallaRouting.distinct(existing.getJSONObject(j),option)){
                                        isNew=false;break;
                                    }
                                }
                                if(isNew){existing.put(option);added++;}
                            }
                            if(added>0)activeRouteProvider="OSRM + Valhalla (trafiksiz)";
                        }
                    }catch(Exception ignored){
                        // Demo downtime, invalid response or rate limiting should
                        // never erase a valid route returned by the main engine.
                    }
                }
                if(out==null)throw new IllegalStateException(problems.toString()+
                    " | Valhalla açık kaynak rota alınamadı");
                JSONArray routes=out.getJSONArray("routes");
                if(destroyed||myGeneration!=generation)return;
                // An original OSRM/Orbis route is a genuine geometry, but toll
                // status is unknown until provider toll sections are verified.
                for(int i=0;i<routes.length();i++){
                    JSONObject route=routes.optJSONObject(i);
                    if(route!=null && !route.has("evcockpitRoutePreference")){
                        route.put("evcockpitRoutePreference","standard")
                            .put("evcockpitTollVerified",false);
                    }
                }
                renderRouteOption(routes,0,verifiedGps,hasStop,myGeneration);
                // Add actual (not invented) shorter and toll-avoid geometry on a
                // separate worker, without delaying the first route, forecast,
                // safety pins or user controls. TomTom routes are kept separate.
                if(!activeRouteProvider.startsWith("TomTom Orbis") && !hasStop){
                    variantJobs.execute(()->enrichRouteVariants(routes,myGeneration,
                        fromLat,fromLon,toLat,toLon));
                }
            }catch(Exception e){
                final String detail=routeFailure(e);
                ui.post(()->{
                    if(destroyed||myGeneration!=generation)return;
                    send("mediaengineStatus",record("error","Rota oluşturulamadı · "+detail));
                });
            }
        });
    }

    private void enrichRouteVariants(JSONArray existing,int ticket,
                                     double fromLat,double fromLon,
                                     double toLat,double toLon){
        boolean changed=false;
        for(String mode:new String[]{"shorter","avoid-tolls","avoid-motorways"}){
            if(destroyed||generation!=ticket)return;
            if(existing.length()>=9)break;
            try{
                JSONArray more=EvCockpitValhallaRouting.variant(
                    fromLat,fromLon,toLat,toLon,Double.NaN,Double.NaN,mode);
                for(int i=0;i<more.length()&&existing.length()<9;i++){
                    JSONObject candidate=more.optJSONObject(i);
                    if(candidate==null)continue;
                    boolean novel=true;
                    for(int k=0;k<existing.length();k++){
                        if(!EvCockpitValhallaRouting.distinct(existing.getJSONObject(k),candidate)){
                            novel=false;break;
                        }
                    }
                    if(novel){existing.put(candidate);changed=true;}
                }
            }catch(Exception ignored){
                // Public endpoint may be down or disable hard exclusions;
                // never claim a successfully avoided toll on failures.
            }
        }
        if(!changed || destroyed || generation!=ticket)return;
        ui.post(()->{
            if(destroyed||generation!=ticket)return;
            cachedAlternatives=existing;
            try{
                JSONObject packet=new JSONObject()
                    .put("routeGeneration",ticket)
                    .put("count",existing.length())
                    .put("routePreviews",routePreviewGeometries(existing));
                send("mediaengineRouteVariants",packet);
                int stationTicket=++routeStationRequestId;
                sendFastChargePreview(existing,selectedAlternativeIndex,ticket,stationTicket);
                stationJobs.execute(()->loadAlternativeCards(existing,ticket,
                    stationTicket,selectedAlternativeIndex,cachedHasStop));
            }catch(Exception ignored){}
        });
    }

    /** Every genuine OSRM route, at most 12 for untrusted response protection.
     * Sample geometries to bound phone/WebView memory use; never make up roads. */
    private static JSONArray routePreviewGeometries(JSONArray routes)throws Exception{
        JSONArray result=new JSONArray();
        int count=Math.min(routes.length(),12);
        for(int i=0;i<count;i++){
            JSONObject route=routes.optJSONObject(i);
            JSONObject geo=route==null?null:route.optJSONObject("geometry");
            JSONArray coords=geo==null?null:geo.optJSONArray("coordinates");
            if(coords==null||coords.length()<2)continue;
            JSONArray shape=new JSONArray();
            int stride=Math.max(1,(int)Math.ceil(coords.length()/450.0));
            for(int j=0;j<coords.length();j+=stride){
                JSONArray c=coords.getJSONArray(j);
                shape.put(new JSONArray().put(c.optDouble(1)).put(c.optDouble(0)));
            }
            JSONArray last=coords.getJSONArray(coords.length()-1);
            shape.put(new JSONArray().put(last.optDouble(1)).put(last.optDouble(0)));
            result.put(new JSONObject().put("index",i).put("coordinates",shape)
                .put("km",Math.round(route.optDouble("distance")/100.0)/10.0)
                .put("minutes",Math.round(route.optDouble("duration")/60.0))
                .put("preference",route.optString("evcockpitRoutePreference","standard")));
        }
        return result;
    }
    private void renderRouteOption(JSONArray routes,int optionIndex,boolean verifiedGps,
                                   boolean hasStop,int myGeneration)throws Exception{
                JSONObject selected=routes.getJSONObject(optionIndex);
                JSONObject packet=new JSONObject();
                packet.put("coordinates",selected.getJSONObject("geometry").getJSONArray("coordinates"));
                packet.put("distanceKm",Math.round(selected.getDouble("distance")/100.0)/10.0);
                packet.put("durationMin",Math.round(selected.getDouble("duration")/60.0));
                packet.put("alternatives",routes.length());
                packet.put("routePreviews",routePreviewGeometries(routes));
                packet.put("routeIndex",optionIndex);
                packet.put("routeGeneration",myGeneration);
                packet.put("provider",activeRouteProvider);
                boolean tomtom=selected.optBoolean("trafficAware",false) &&
                    activeRouteProvider.startsWith("TomTom Orbis v3");
                packet.put("trafficAvailable",tomtom);
                double delay=selected.optDouble("trafficDelaySeconds",Double.NaN);
                if(tomtom&&Double.isFinite(delay)&&delay>=0)
                    packet.put("trafficDelayMin",Math.round(delay/60.0));
                packet.put("navigationActive",verifiedGps);
                packet.put("stopIncluded",hasStop);
                // EV profile model, not an ECU claim. No manual entry.
                // A recent measured SoC is optional; assumptions are explicit in the UI.
                EvCockpitEnergyData.Snapshot facts=EvCockpitEnergyData.load(this,System.currentTimeMillis());
                EvCockpitEnergyModel.Result energy=EvCockpitEnergyModel.estimate(
                    packet.getDouble("distanceKm"),facts.hasSoc()?facts.soc:Double.NaN);
                if(energy!=null){
                    packet.put("energyLowKwh",energy.lowRouteKwh);
                    packet.put("energyHighKwh",energy.highRouteKwh);
                    packet.put("energyProfile","Enerji model varsayımı: 16–24 kWh/100 km (araç ölçümü değil)");
                    // On a route with a charger waypoint we do not know how
                    // many kWh will be added, so a terminal SoC would be fictitious.
                    if(energy.hasSoc && !hasStop){
                        packet.put("socMeasuredAt",facts.socAt);
                        double arrivalLow=facts.soc-
                            (100.0*energy.highRouteKwh/EvCockpitEnergyModel.ASSUMED_USABLE_KWH);
                        double arrivalHigh=facts.soc-
                            (100.0*energy.lowRouteKwh/EvCockpitEnergyModel.ASSUMED_USABLE_KWH);
                        // Negative SoC cannot physically arrive; do not render fake 0% arrival.
                        packet.put("chargingNeededByModel",
                            arrivalLow<EvCockpitEnergyModel.ASSUMED_RESERVE_PERCENT);
                        if(arrivalLow>=0 && arrivalHigh>=0){
                            packet.put("arrivalSocLower",arrivalLow);
                            packet.put("arrivalSocUpper",arrivalHigh);
                        }
                    }
                }
                JSONArray legs=selected.getJSONArray("legs");
                ArrayList<Step> parsed=new ArrayList<>();
                JSONArray labels=new JSONArray();
                for(int leg=0;leg<legs.length();leg++){
                    if(leg==1 && hasStop){
                        labels.put("Şarj mediaenginesından sonra hedefe devam et");
                    }
                    JSONArray roadSteps=legs.getJSONObject(leg).getJSONArray("steps");
                    for(int i=0;i<roadSteps.length() && parsed.size()<180;i++){
                        JSONObject row=roadSteps.getJSONObject(i);
                        JSONObject m=row.getJSONObject("maneuver");
                        JSONArray coord=m.getJSONArray("location");
                        String instruction=spoken(m.optString("type"),m.optString("modifier"),
                            row.optString("name"));
                        if(!instruction.isEmpty()){
                            parsed.add(new Step(coord.getDouble(1),coord.getDouble(0),instruction));
                            labels.put(instruction);
                        }
                    }
                }
                packet.put("steps",labels);
                ui.post(()->{
                    if(myGeneration!=generation||destroyed)return;
                    cachedAlternatives=routes;
                    cachedRouteGps=verifiedGps;cachedHasStop=hasStop;
                    selectedAlternativeIndex=optionIndex;
                    steps.clear();steps.addAll(parsed);nextStep=0;
                    running=verifiedGps&&!parsed.isEmpty();
                    EvCockpitEnergyData.saveRoute(this,
                        packet.optDouble("distanceKm",Double.NaN),
                        packet.optDouble("durationMin",Double.NaN),
                        System.currentTimeMillis());
                    send("mediaengineRoute",packet);
                    sendScenarioStatus();
                    // Show source-labeled pricing and stop count immediately;
                    // wait for EPDK only to enrich station availability hints.
                    if(!EvCockpitEnergyData.load(this,System.currentTimeMillis()).hasSoc())
                        ui.postDelayed(this::promptScenarioIfMissing,600);
                    // A route selection starts one bounded, read-only OSM query
                    // for sampled fixed-camera records, independent of GPS.
                    // Never block route display or claim complete camera coverage.
                    long safetyNow=System.currentTimeMillis();
                    if(safetyNow-lastSafetyQueryAt>=90000){
                        lastSafetyQueryAt=safetyNow;
                        final int safetyGeneration=myGeneration;
                        try{
                            JSONArray routeCoords=selected.getJSONObject("geometry")
                                .getJSONArray("coordinates");
                            safetyJobs.execute(()->{
                                try{
                                    JSONObject cameras=EvCockpitRoadSafety.retrieveCorridor(routeCoords)
                                        .put("routeGeneration",safetyGeneration);
                                    if(!destroyed&&generation==safetyGeneration)
                                        send("mediaengineSafetyCameras",cameras);
                                }catch(Exception e){
                                    if(!destroyed&&generation==safetyGeneration)
                                        send("mediaengineSafetyError",record("safety",
                                            "OSM kamera verisi doğrulanamadı; rota korunuyor."));
                                }
                            });
                        }catch(Exception ignored){}
                    }
                    final JSONObject routeData=selected;
                    activeWeatherRoute=routeData;
                    JSONObject activeGeometry=routeData.optJSONObject("geometry");
                    activeIncidentRoute=activeGeometry==null?null:
                        activeGeometry.optJSONArray("coordinates");
                    lastIncidentPollAt=0;
                    lastSpokenIncidentId="";
                    lastJourneyWeatherRefreshAt=System.currentTimeMillis();
                    // Populate a GPS-independent current MODEL weather report at
                    // the selected start; actual GPS takes precedence when fresh.
                    try{
                        JSONArray start=routeData.getJSONObject("geometry")
                            .getJSONArray("coordinates").getJSONArray(0);
                        Location live=position;
                        boolean liveGps=live!=null && live.getAccuracy()<=60
                            && System.currentTimeMillis()-live.getTime()<30000;
                        fetchCurrentWeather(liveGps?live.getLatitude():start.getDouble(1),
                            liveGps?live.getLongitude():start.getDouble(0),liveGps,false);
                    }catch(Exception ignored){}
                    // Weather is a separate best-effort request after the drawn route.
                    routeWeatherJobs.execute(()->loadRouteWeather(routeData,myGeneration));
                    routeWeatherJobs.execute(()->loadJourneyWeather(routeData,myGeneration));
                    // Read-only EPDK snapshot route corridor; never block route rendering.
                    final int stationTicket=++routeStationRequestId;
                    sendFastChargePreview(routes,optionIndex,myGeneration,stationTicket);
                    stationJobs.execute(()->loadRouteStations(routeData,myGeneration,stationTicket));
                    stationJobs.execute(()->loadAlternativeCards(routes,myGeneration,stationTicket,
                        optionIndex,hasStop));
                    if(verifiedGps&&!parsed.isEmpty())announce("Rota hazır. "+packet.optDouble("distanceKm")+
                        " kilometre. Deneysel sesli yönlendirme başlıyor.");
                });
    }

    /**
     * Open-Meteo current-model report refreshed on a verified GPS fix or at the
     * chosen route start. No fake surface station readings or weather scraping.
     */
    private void fetchCurrentWeather(double lat,double lon,boolean fromGps,
                                     boolean userRefresh){
        if(!valid(lat,lon)||destroyed)return;
        long now=System.currentTimeMillis();
        boolean moved=false;
        if(Double.isFinite(lastCurrentWeatherLat)){
            float[] d=new float[1];
            Location.distanceBetween(lat,lon,lastCurrentWeatherLat,
                lastCurrentWeatherLon,d);
            moved=d[0]>15000;
        }
        if(!userRefresh && !moved && now-lastCurrentWeatherAt<600000)return;
        // Tap refresh has a minimum of 30sec to avoid hammering free service.
        if(userRefresh && !moved && now-lastCurrentWeatherAt<30000)return;
        lastCurrentWeatherAt=now;
        lastCurrentWeatherLat=lat;lastCurrentWeatherLon=lon;
        final boolean gps=fromGps;
        currentWeatherJobs.execute(()->{
            try{
                String uri="https://api.open-meteo.com/v1/forecast?latitude="+
                    String.format(Locale.US,"%.5f",lat)+"&longitude="+
                    String.format(Locale.US,"%.5f",lon)+
                    "&current=temperature_2m,relative_humidity_2m,"+
                    "precipitation,weather_code,wind_speed_10m,is_day"+
                    "&timezone=GMT&timeformat=unixtime";
                JSONObject model=new JSONObject(fetch(uri,100000))
                    .getJSONObject("current");
                double temp=model.optDouble("temperature_2m",Double.NaN);
                double precip=model.optDouble("precipitation",Double.NaN);
                double wind=model.optDouble("wind_speed_10m",Double.NaN);
                long modelTime=model.optLong("time",0);
                long age=Math.abs(System.currentTimeMillis()/1000-modelTime);
                if(!Double.isFinite(temp)||!Double.isFinite(precip)||
                    !Double.isFinite(wind)||modelTime<=0||age>5400)
                    throw new IllegalStateException("Güncel model bulunamadı");
                JSONObject result=new JSONObject()
                    .put("temperature",temp).put("precipitation",precip)
                    .put("wind",wind).put("weatherCode",
                        model.optInt("weather_code",-1))
                    .put("isDay",model.optInt("is_day",-1))
                    .put("modelTime",modelTime)
                    .put("receivedAt",System.currentTimeMillis())
                    .put("kind",gps?"gps":"route-start")
                    .put("source","Open-Meteo")
                    .put("modelBased",true).put("liveStationObservation",false);
                double humidity=model.optDouble("relative_humidity_2m",Double.NaN);
                if(Double.isFinite(humidity))result.put("humidity",humidity);
                if(!destroyed)send("mediaengineCurrentWeather",result);
            }catch(Exception e){
                // Retry will be possible after 2 min rather than blanking a
                // still-valid prior report at the first transient failure.
                lastCurrentWeatherAt=System.currentTimeMillis()-480000;
                if(!destroyed)send("mediaengineCurrentWeatherError",
                    record("weather","Güncel hava modeli alınamadı; bağlantı kontrol et."));
            }
        });
    }

    /**
     * Open-Meteo 15-minute MODEL forecast, evaluated at predicted positions
     * along the route for +1,+5,+10,+15 min. Not minute-by-minute observations.
     * Separate network request so a weather outage cannot erase the route.
     */
    private void loadRouteWeather(JSONObject route,int routeGeneration){
        if(destroyed||routeGeneration!=generation)return;
        final int[] minutes={1,5,10,15};
        try{
            JSONArray coords=route.getJSONObject("geometry").getJSONArray("coordinates");
            if(coords.length()<2)throw new IllegalStateException("Rota geometrisi boş");
            double durationSec=route.getDouble("duration");
            double[] lengths=new double[coords.length()];
            double total=0;
            for(int i=1;i<coords.length();i++){
                JSONArray previous=coords.getJSONArray(i-1),current=coords.getJSONArray(i);
                float[] distance=new float[1];
                Location.distanceBetween(previous.getDouble(1),previous.getDouble(0),
                    current.getDouble(1),current.getDouble(0),distance);
                total+=distance[0];lengths[i]=total;
            }
            if(total<10)throw new IllegalStateException("Rota mesafesi hatalı");
            StringBuilder lat=new StringBuilder(),lon=new StringBuilder();
            for(int minute:minutes){
                double fraction=Math.min(1.0,minute*60.0/Math.max(1.0,durationSec));
                double target=total*fraction;int idx=1;
                while(idx<lengths.length-1 && lengths[idx]<target)idx++;
                JSONArray p=coords.getJSONArray(idx-1),q=coords.getJSONArray(idx);
                double ratio=(target-lengths[idx-1])/
                    Math.max(0.0001,lengths[idx]-lengths[idx-1]);
                ratio=Math.max(0,Math.min(1,ratio));
                double plat=p.getDouble(1)+(q.getDouble(1)-p.getDouble(1))*ratio;
                double plon=p.getDouble(0)+(q.getDouble(0)-p.getDouble(0))*ratio;
                if(lat.length()>0){lat.append(',');lon.append(',');}
                lat.append(String.format(Locale.US,"%.5f",plat));
                lon.append(String.format(Locale.US,"%.5f",plon));
            }
            String u="https://api.open-meteo.com/v1/forecast?latitude="+lat+
                "&longitude="+lon+
                "&minutely_15=temperature_2m,precipitation,wind_speed_10m,weather_code"+
                "&hourly=temperature_2m,precipitation,wind_speed_10m,weather_code"+
                "&forecast_minutely_15=8&forecast_hours=4"+
                "&timeformat=unixtime&timezone=GMT";
            JSONArray locations=new JSONArray(fetch(u,350000));
            if(locations.length()<4)throw new IllegalStateException("Tahmin bölgeleri eksik");
            JSONArray cards=new JSONArray();
            long now=System.currentTimeMillis()/1000L;
            for(int i=0;i<4;i++){
                JSONObject item=locations.getJSONObject(i);
                JSONObject series=item.optJSONObject("minutely_15");
                String resolution="15dk model";
                if(series==null || series.optJSONArray("time")==null ||
                    series.optJSONArray("time").length()==0){
                    series=item.optJSONObject("hourly");resolution="saatlik model";
                }
                if(series==null)throw new IllegalStateException("Tahmin serisi yok");
                JSONArray times=series.optJSONArray("time");
                JSONArray temp=series.optJSONArray("temperature_2m");
                JSONArray precip=series.optJSONArray("precipitation");
                JSONArray wind=series.optJSONArray("wind_speed_10m");
                JSONArray code=series.optJSONArray("weather_code");
                if(times==null || temp==null || precip==null || wind==null ||
                    times.length()==0)throw new IllegalStateException("Hava alanları eksik");
                long target=now+minutes[i]*60L;
                int closest=0;long diff=Long.MAX_VALUE;
                for(int j=0;j<times.length();j++){
                    long d=Math.abs(times.optLong(j)-target);
                    if(d<diff){closest=j;diff=d;}
                }
                if(diff>4000)throw new IllegalStateException("Güncel tahmin zamanı bulunamadı");
                double temperature=temp.optDouble(closest,Double.NaN);
                double rainfall=precip.optDouble(closest,Double.NaN);
                double windKmh=wind.optDouble(closest,Double.NaN);
                if(!Double.isFinite(temperature)||!Double.isFinite(rainfall)||
                    !Double.isFinite(windKmh))throw new IllegalStateException("Eksik hava ölçümü");
                JSONObject card=new JSONObject();
                card.put("minutes",minutes[i]);
                card.put("temperature",temperature);
                card.put("precipitation",rainfall);
                card.put("wind",windKmh);
                card.put("weatherCode",code==null?-1:code.optInt(closest,-1));
                card.put("resolution",resolution);
                card.put("forecastTime",times.optLong(closest));
                cards.put(card);
            }
            JSONObject packet=new JSONObject();
            packet.put("cards",cards);
            packet.put("source","Open-Meteo");
            packet.put("updatedAt",System.currentTimeMillis());
            if(!destroyed && routeGeneration==generation)send("mediaengineWeather",packet);
        }catch(Exception e){
            if(!destroyed && routeGeneration==generation)
                send("mediaengineWeatherError",record("weather",
                    "Hava tahmini alınamadı ("+e.getClass().getSimpleName()+")."));
        }
    }

    /**
     * Whole-journey Open-Meteo forecast at 0/25/50/75/100% of the OSRM
     * geometry and projected arrival time. Source resolution is hourly;
     * these are predictions, NOT actual localized weather observations.
     */
    private void loadJourneyWeather(JSONObject route,int routeGeneration){
        if(destroyed||routeGeneration!=generation)return;
        try{
            JSONArray coordinates=route.getJSONObject("geometry").getJSONArray("coordinates");
            if(coordinates.length()<2)throw new IllegalStateException("Rota geometrisi yok");
            double durationSec=route.getDouble("duration");
            if(durationSec<=0 || durationSec>72*3600)
                throw new IllegalStateException("Hava tahmin aralığı dışında");
            double[] cumulative=new double[coordinates.length()];
            for(int i=1;i<coordinates.length();i++){
                JSONArray p=coordinates.getJSONArray(i-1),q=coordinates.getJSONArray(i);
                float[] measured=new float[1];
                Location.distanceBetween(p.getDouble(1),p.getDouble(0),
                    q.getDouble(1),q.getDouble(0),measured);
                cumulative[i]=cumulative[i-1]+measured[0];
            }
            double total=cumulative[cumulative.length-1];
            if(total<10)throw new IllegalStateException("Boş güzergah");
            StringBuilder latitudes=new StringBuilder(),longitudes=new StringBuilder();
            double[] routeLats=new double[7],routeLons=new double[7];
            double[] fractions={0,1.0/6,2.0/6,.5,4.0/6,5.0/6,1};
            long now=System.currentTimeMillis()/1000L;
            for(double fraction:fractions){
                double desired=fraction*total;int j=1;
                while(j<cumulative.length-1 && cumulative[j]<desired)j++;
                JSONArray a=coordinates.getJSONArray(j-1),b=coordinates.getJSONArray(j);
                double divisor=Math.max(.00001,cumulative[j]-cumulative[j-1]);
                double t=Math.max(0,Math.min(1,(desired-cumulative[j-1])/divisor));
                double latitude=a.getDouble(1)+(b.getDouble(1)-a.getDouble(1))*t;
                double longitude=a.getDouble(0)+(b.getDouble(0)-a.getDouble(0))*t;
                int waypoint=(int)Math.round(fraction*6);
                routeLats[waypoint]=latitude;routeLons[waypoint]=longitude;
                if(latitudes.length()>0){latitudes.append(',');longitudes.append(',');}
                latitudes.append(String.format(Locale.US,"%.5f",latitude));
                longitudes.append(String.format(Locale.US,"%.5f",longitude));
            }
            int forecastHours=Math.max(5,Math.min(72,(int)Math.ceil(durationSec/3600)+3));
            String url="https://api.open-meteo.com/v1/forecast?latitude="+latitudes+
                "&longitude="+longitudes+
                "&current=temperature_2m,precipitation,weather_code,"+
                "wind_speed_10m,wind_gusts_10m"+
                "&hourly=temperature_2m,precipitation_probability,precipitation,"+
                "wind_speed_10m,wind_gusts_10m,visibility,snowfall,weather_code"+
                "&forecast_hours="+forecastHours+"&timeformat=unixtime&timezone=GMT";
            JSONArray regions=new JSONArray(fetch(url,700000));
            if(regions.length()!=fractions.length)
                throw new IllegalStateException("Eksik yol hava tahmini");
            JSONArray stations=new JSONArray();
            for(int i=0;i<fractions.length;i++){
                JSONObject location=regions.getJSONObject(i);
                JSONObject data=location.getJSONObject("hourly");
                JSONArray hours=data.getJSONArray("time");
                JSONArray temp=data.getJSONArray("temperature_2m");
                JSONArray rain=data.getJSONArray("precipitation_probability");
                JSONArray wind=data.getJSONArray("wind_speed_10m");
                JSONArray gust=data.optJSONArray("wind_gusts_10m");
                JSONArray visibility=data.optJSONArray("visibility");
                JSONArray snowfall=data.optJSONArray("snowfall");
                JSONArray precipitationMm=data.optJSONArray("precipitation");
                JSONArray codes=data.getJSONArray("weather_code");
                long at=now+(long)(fractions[i]*durationSec);
                long smallest=Long.MAX_VALUE;int closest=0;
                for(int j=0;j<hours.length();j++){
                    long delta=Math.abs(hours.optLong(j)-at);
                    if(delta<smallest){smallest=delta;closest=j;}
                }
                JSONObject item=new JSONObject()
                    .put("percent",(int)Math.round(fractions[i]*100))
                    .put("etaMinutes",(int)Math.round(fractions[i]*durationSec/60.0))
                    .put("latitude",routeLats[i])
                    .put("longitude",routeLons[i])
                    .put("forecastTime",hours.optLong(closest))
                    .put("arrivalTime",at);
                // Current model conditions at *each* of the seven locations,
                // distinct from the forecast for the time we reach that point.
                JSONObject current=location.optJSONObject("current");
                if(current!=null){
                    long modelTime=current.optLong("time",0);
                    double currentC=current.optDouble("temperature_2m",Double.NaN);
                    double currentRain=current.optDouble("precipitation",Double.NaN);
                    double currentWind=current.optDouble("wind_speed_10m",Double.NaN);
                    if(modelTime>0&&Math.abs(modelTime-now)<=5400 &&
                       Double.isFinite(currentC)&&Double.isFinite(currentRain)&&
                       Double.isFinite(currentWind)){
                        item.put("nowAvailable",true)
                            .put("nowTime",modelTime)
                            .put("nowTemperature",currentC)
                            .put("nowPrecipitation",currentRain)
                            .put("nowWind",currentWind)
                            .put("nowWeatherCode",current.optInt("weather_code",-1));
                        double nowGust=current.optDouble("wind_gusts_10m",Double.NaN);
                        if(Double.isFinite(nowGust))item.put("nowWindGust",nowGust);
                    }
                }
                double celsius=temp.optDouble(closest,Double.NaN);
                double precipitation=rain.optDouble(closest,Double.NaN);
                double windKmh=wind.optDouble(closest,Double.NaN);
                if(smallest<=5400 && Double.isFinite(celsius) &&
                   Double.isFinite(precipitation) && Double.isFinite(windKmh)) {
                    double gustKmh=gust==null?Double.NaN:gust.optDouble(closest,Double.NaN);
                    double viewMeters=visibility==null?Double.NaN:
                        visibility.optDouble(closest,Double.NaN);
                    double snowCm=snowfall==null?Double.NaN:
                        snowfall.optDouble(closest,Double.NaN);
                    double rainMm=precipitationMm==null?Double.NaN:
                        precipitationMm.optDouble(closest,Double.NaN);
                    int code=codes.optInt(closest,-1);
                    item.put("available",true).put("temperature",celsius)
                        .put("rainProbability",precipitation)
                        .put("wind",windKmh).put("weatherCode",code);
                    if(Double.isFinite(gustKmh))item.put("windGust",gustKmh);
                    if(Double.isFinite(viewMeters))item.put("visibilityMeters",viewMeters);
                    if(Double.isFinite(snowCm))item.put("snowfallCm",snowCm);
                    if(Double.isFinite(rainMm))item.put("precipitationMm",rainMm);
                    // Forecast risk labels describe MODEL possibilities, not
                    // observed road surface state or a closed road.
                    JSONArray risks=new JSONArray();
                    if(viewMeters>=0 && viewMeters<1000)risks.put("Düşük görüş");
                    if(gustKmh>=60)risks.put("Kuvvetli rüzgâr");
                    if(code>=95 && code<=99)risks.put("Gök gürültülü yağış");
                    if((code>=71&&code<=77)||code==85||code==86 ||
                       (snowCm>0.2))risks.put("Kar tahmini");
                    if((code>=56&&code<=57)||(code>=66&&code<=67))
                        risks.put("Donan yağış olasılığı");
                    if(precipitation>=70 && !(code>=71&&code<=77))
                        risks.put("Yüksek yağış olasılığı");
                    if(risks.length()>0)item.put("risks",risks);
                }else item.put("available",false);
                stations.put(item);
            }
            JSONObject result=new JSONObject().put("cards",stations)
                .put("source","Open-Meteo").put("resolution","güncel model + saatlik varış tahmini")
                .put("updatedAt",System.currentTimeMillis())
                .put("refreshIntervalMinutes",5)
                .put("routeGeneration",routeGeneration)
                .put("liveConditions",false)
                .put("trafficAwareEta",activeRouteProvider.startsWith("TomTom Orbis"))
                .put("etaAssumption","Rota oluşturulurken hesaplanan sürüş süresi; şarj ve duraklama dahil değildir");
            if(!destroyed && generation==routeGeneration){
                getSharedPreferences("evcockpit_route_weather_v1",MODE_PRIVATE).edit()
                    .putString("packet",result.toString()).apply();
                send("mediaengineJourneyWeather",result);
            }
        }catch(Exception e){
            if(!destroyed && generation==routeGeneration)
                send("mediaengineJourneyWeatherError",record("weather",
                    "Yol boyunca saatlik hava tahmini alınamadı ("+
                    e.getClass().getSimpleName()+")."));
        }
    }

    private static String spoken(String type,String modifier,String street){
        String road=street==null||street.trim().isEmpty()?"":(" "+street.trim());
        switch(type){
            case "depart":return "Yola çıkın"+(road.isEmpty()?"":", "+road.trim()+" yolunu takip edin");
            case "arrive":return "Hedefinize ulaştınız";
            case "roundabout":case "rotary":return "Döner kavşağa girin";
            case "turn":case "continue":case "fork":case "new name":
                String m=modifier==null?"":modifier;
                if(m.contains("left"))return "Sola dönün"+road;
                if(m.contains("right"))return "Sağa dönün"+road;
                return "Yola devam edin"+road;
            case "merge":return "Yola katılın"+road;
            case "on ramp":return "Bağlantı yoluna girin"+road;
            case "off ramp":return "Çıkışı kullanın"+road;
            default:return "";
        }
    }
    private static boolean valid(double lat,double lon){
        return Double.isFinite(lat)&&Double.isFinite(lon)&&lat>=35&&lat<=43&&lon>=25&&lon<=46;
    }
    private static String routeFailure(Exception e){
        if(e instanceof java.net.SocketTimeoutException)return "Servis zaman aşımı";
        if(e instanceof java.net.UnknownHostException)return "Sunucu adresi çözülemedi";
        if(e instanceof javax.net.ssl.SSLException)return "Güvenli bağlantı kurulamadı";
        String m=e.getMessage();
        if(m!=null && m.matches("HTTP [0-9]{3}"))return m;
        if(m!=null && m.startsWith("OSRM ") && m.length()<180)return m;
        if(e instanceof org.json.JSONException)return "Rota yanıtı çözümlenemedi";
        return "Rota servisi yanıt vermedi";
    }
    private static String fetch(String address,int max)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
        c.setConnectTimeout(7500);c.setReadTimeout(10000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent","EV-Cockpit/0.3");
        c.setRequestProperty("Accept-Language","en");
        try{
            if(c.getResponseCode()!=200)throw new IllegalStateException("HTTP "+c.getResponseCode());
            ByteArrayOutputStream b=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){
                byte[] data=new byte[4096];int n;
                while((n=in.read(data))!=-1){
                    if(b.size()+n>max)throw new IllegalStateException("Yanıt çok büyük");
                    b.write(data,0,n);
                }
            }
            return b.toString("UTF-8");
        }finally{c.disconnect();}
    }
    // Never start GPS merely because the user opened a manual route preview.
    // Location is requested explicitly via the "Konumumu başlangıç yap" button.
    private void updateImmersiveCockpit(){
        // Only our PHONE/embedded Activity uses immersive mode in landscape.
        // This does not bypass or claim Android Auto host/display support.
        boolean wide=getResources().getConfiguration().orientation==
            Configuration.ORIENTATION_LANDSCAPE;
        if(Build.VERSION.SDK_INT>=30){
            WindowInsetsController controller=getWindow().getInsetsController();
            if(controller!=null){
                controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if(wide)controller.hide(WindowInsets.Type.systemBars());
                else controller.show(WindowInsets.Type.systemBars());
            }
        }else{
            int flags=wide?(View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN):0;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }
    @Override public void onConfigurationChanged(Configuration configuration){
        super.onConfigurationChanged(configuration);
        updateImmersiveCockpit();
        // Landscape switch is user-initiated; retain this WebView's OBD and route state.
        if(web!=null)web.post(()->{
            if(web!=null&&!destroyed)
                web.evaluateJavascript("window.mediaengineLayout&&window.mediaengineLayout()",null);
        });
    }
    @Override public void onWindowFocusChanged(boolean hasFocus){
        super.onWindowFocusChanged(hasFocus);
        if(hasFocus&&!destroyed)updateImmersiveCockpit();
    }
    @Override protected void onResume(){
        super.onResume();updateImmersiveCockpit();
        weatherForeground=true;
        ui.removeCallbacks(routeWeatherTicker);
        ui.post(routeWeatherTicker);
        if(telemetry!=null)autoConnectObd();
        // Restore only GPS monitoring that the user previously opted into.
        if(gpsTrackingRequested &&
           checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==
           PackageManager.PERMISSION_GRANTED)requestGps();
        else if(gpsTrackingRequested)
            send("mediaengineStatus",record("gps","GPS izni yok; canlı takip durduruldu."));
        running=resumeRouteAfterPause && gpsTrackingRequested &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==
            PackageManager.PERMISSION_GRANTED;
        resumeRouteAfterPause=false;
    }
    @Override protected void onPause(){
        weatherForeground=false;
        ui.removeCallbacks(routeWeatherTicker);
        // Consent does not extend to background tracking; save and stop here.
        if(tripJournal.recording())tripJournal.finish(this,System.currentTimeMillis());
        resumeRouteAfterPause=running;
        try{locationManager.removeUpdates(this);}catch(Exception ignored){}
        running=false; if(tts!=null)tts.stop();releaseSpeechFocus();
        if(telemetry!=null)telemetry.stop();super.onPause();
    }
    @Override protected void onDestroy(){
        weatherForeground=false;
        ui.removeCallbacks(routeWeatherTicker);
        destroyed=true;running=false;generation++;stationRequestId++;routeStationRequestId++;
        try{locationManager.removeUpdates(this);}catch(Exception ignored){}
        jobs.shutdownNow();searchJobs.shutdownNow();routeWeatherJobs.shutdownNow();
        stationJobs.shutdownNow();variantJobs.shutdownNow();
        currentWeatherJobs.shutdownNow();
        safetyJobs.shutdownNow();incidentJobs.shutdownNow();
        if(tts!=null){tts.stop();tts.shutdown();}releaseSpeechFocus();
        if(telemetry!=null){telemetry.close();telemetry=null;}
        if(web!=null){web.removeJavascriptInterface("MediaEngineNative");web.destroy();web=null;}
        super.onDestroy();
    }
}
