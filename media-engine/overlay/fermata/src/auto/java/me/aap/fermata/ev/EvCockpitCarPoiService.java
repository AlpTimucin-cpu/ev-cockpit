package me.aap.fermata.ev;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.SessionInfo;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Metadata;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.model.Place;
import androidx.car.app.model.CarLocation;
import androidx.car.app.validation.HostValidator;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Android Auto point-of-interest entry for genuine EPDK CCS charge points.
 * Uses approved templates, never projects the arbitrary phone WebView or video.
 * Navigation-template map rendering is deliberately NOT claimed here.
 */
public final class EvCockpitCarPoiService extends CarAppService {
    @NonNull @Override public HostValidator createHostValidator() {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE)!=0)
            return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
        return new HostValidator.Builder(this)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build();
    }

    @NonNull @Override public Session onCreateSession() {
        return new JourneySession();
    }
    @NonNull @Override public Session onCreateSession(@NonNull SessionInfo sessionInfo) {
        return new JourneySession();
    }

    private static final class JourneySession extends Session {
        @NonNull @Override public Screen onCreateScreen(@NonNull Intent intent) {
            return new ChargingScreen(getCarContext());
        }
    }

    private static final class ChargingScreen extends Screen {
        private final Handler main=new Handler(Looper.getMainLooper());
        private volatile JSONArray stations=new JSONArray();
        private volatile String message="Şarj noktaları yükleniyor";
        private volatile boolean loading=true;
        private boolean started=false;
        private int page=0;
        private int requests=0;
        private String ageLabel="Güncellik bilinmiyor";

        ChargingScreen(@NonNull CarContext context){super(context);}

        @NonNull @Override public Template onGetTemplate() {
            if(!started){
                started=true;
                requestStationRefresh();
            }
            ListTemplate.Builder builder=new ListTemplate.Builder()
                .setTitle("EV Cockpit · Şarj Noktaları")
                .setHeaderAction(Action.APP_ICON)
                .setActionStrip(new ActionStrip.Builder()
                    .addAction(new Action.Builder().setTitle("Yenile")
                        .setOnClickListener(this::requestStationRefresh).build())
                    .build());
            // Never block the OBD entry behind a slow EPDK network download.

            ItemList.Builder list=new ItemList.Builder();
            // The car template shows a passive weather status only *after*
            // a phone route has loaded forecast data: never a weather button.
            try {
                String saved=getCarContext().getSharedPreferences(
                    "evcockpit_route_weather_v1",CarContext.MODE_PRIVATE)
                    .getString("packet","");
                JSONObject weather=new JSONObject(saved);
                JSONArray cards=weather.optJSONArray("cards");
                long age=System.currentTimeMillis()-weather.optLong("updatedAt",0);
                if(cards!=null && cards.length()>=2 && cards.length()<=9 &&
                   age>=0 && age<3600000) {
                    int available=0,risks=0;
                    for(int i=0;i<cards.length();i++){
                        JSONObject c=cards.optJSONObject(i);
                        if(c==null||!c.optBoolean("available",false))continue;
                        available++;
                        JSONArray warnings=c.optJSONArray("risks");
                        if(warnings!=null&&warnings.length()>0)risks++;
                    }
                    list.addItem(new Row.Builder()
                        .setTitle("☁ Yol havası · otomatik")
                        .addText(available+"/"+cards.length()+" noktada saatlik tahmin"+
                            (risks>0?" · "+risks+" noktada olası risk":""))
                        .build());
                }
            } catch(Exception ignored){
                // Never invent forecast conditions from an absent or stale cache.
            }
            list.addItem(new Row.Builder()
                .setTitle("🚙 EV Cockpit · OBD bağlantısı")
                .addText("Adaptör bağla · ECU durumunu kontrol et")
                .setOnClickListener(()->getScreenManager().push(
                    new EvCockpitCarObdScreen(getCarContext())))
                .build());
            if(loading){
                list.addItem(new Row.Builder().setTitle("Şarj istasyonları yükleniyor")
                    .addText("OBD bağlantısı bu sırada kullanılabilir.").build());
            }else if(stations.length()==0){
                list.addItem(new Row.Builder().setTitle("İstasyon bilgisi")
                    .addText(message).build());
            }else{
                // Host-safe pagination: never flood the driving UI with >6 rows.
                int first=page*3;
                if(first>=stations.length()){page=0;first=0;}
                int count=Math.min(stations.length(),first+3);
                for(int i=first;i<count;i++){
                    JSONObject station=stations.optJSONObject(i);
                    if(station==null)continue;
                    String title=station.optString("title","Şarj istasyonu").trim();
                    if(title.isEmpty())title="Şarj istasyonu";
                    String operator=station.optString("operator","EPDK");
                    String sockets=station.optString("sockets","Soket türü belirtilmedi");
                    double distance=station.optDouble("distanceKm",Double.NaN);
                    Row.Builder row=new Row.Builder()
                        .setTitle(title)
                        .addText(operator+" · "+sockets)
                        .addText(Double.isFinite(distance)?
                            String.format(java.util.Locale.forLanguageTag("tr-TR"),
                                "Yaklaşık %.1f km · EPDK kaydı",distance):
                            "EPDK kaydı · canlı doluluk doğrulanmadı");
                    double lat=station.optDouble("lat",Double.NaN);
                    double lon=station.optDouble("lon",Double.NaN);
                    if(Double.isFinite(lat)&&Double.isFinite(lon)){
                        row.setMetadata(new Metadata.Builder().setPlace(
                            new Place.Builder(CarLocation.create(lat,lon)).build()
                        ).build());
                    }
                    final JSONObject selected=station;
                    row.setOnClickListener(()->getScreenManager().push(
                        new EvCockpitCarStationDetailsScreen(getCarContext(),selected,ageLabel)));
                    list.addItem(row.build());
                }
                if(stations.length()>3){
                    final int pageCount=(stations.length()+2)/3;
                    list.addItem(new Row.Builder()
                        .setTitle("Diğer şarj noktaları")
                        .addText("Sayfa "+(page+1)+"/"+pageCount+" · sonraki 3 nokta")
                        .setOnClickListener(()->{
                            page=(page+1)%pageCount;
                            invalidate();
                        }).build());
                }
            }
            return builder.setSingleList(list.build()).build();
        }

        private void requestStationRefresh(){
            final int request=++requests;
            loading=true;
            page=0;
            invalidate();
            new Thread(()->loadStationData(request),"evcockpit-auto-epdk").start();
        }

        private void loadStationData(int request){
            JSONArray found=new JSONArray();
            String age="Güncellik bilinmiyor";
            String detail="Konum izni veya yakın tarihli GPS bilgisi bulunamadı. "+
                "Telefonda EV Cockpit konum iznini açın.";
            try{
                CarContext context=getCarContext();
                if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==
                       PackageManager.PERMISSION_GRANTED ||
                   context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==
                       PackageManager.PERMISSION_GRANTED){
                    LocationManager lm=(LocationManager)context.getSystemService(
                        android.content.Context.LOCATION_SERVICE);
                    Location best=null;
                    if(lm!=null)for(String name:new String[]{
                        LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER}){
                        try{
                            Location loc=lm.getLastKnownLocation(name);
                            if(loc!=null && (best==null||loc.getTime()>best.getTime()))best=loc;
                        }catch(Exception ignored){}
                    }
                    if(best!=null&&System.currentTimeMillis()-best.getTime()<600000 &&
                       best.getAccuracy()<=250){
                        double lat=best.getLatitude(),lon=best.getLongitude();
                        EvCockpitStationCatalog catalog=new EvCockpitStationCatalog(context);
                        JSONObject packet=catalog.inBounds(lat-.22,lon-.27,lat+.22,lon+.27,true);
                        found=packet.optJSONArray("items");
                        if(found==null)found=new JSONArray();
                        long hours=packet.optLong("snapshotAgeHours",-1);
                        if(hours>=0)age=hours+" saat önce güncellenmiş kayıt";
                        detail=found.length()==0?
                            "Bu bölgede güncel EPDK listesinde CCS kaydı bulunamadı.":
                            "EPDK · canlı doluluk veya anlık tarife içermez";
                    }else{
                        detail="Güncel konum yok. EV Cockpit uygulamasından konum alıp yeniden bağlanın.";
                    }
                }
            }catch(Exception e){
                detail="EPDK istasyon listesi şu anda alınamadı. Telefon internetini kontrol edin.";
            }
            final JSONArray ready=found;
            final String status=detail;
            final String sourceAge=age;
            main.post(()->{
                if(request!=requests)return; // stale asynchronous reply must not replace new data
                stations=ready;message=status;ageLabel=sourceAge;
                loading=false;page=0;invalidate();
            });
        }
    }
}
