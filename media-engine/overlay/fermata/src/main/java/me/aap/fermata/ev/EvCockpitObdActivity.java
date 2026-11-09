package me.aap.fermata.ev;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Optional, read-only VW MEB OBD monitor. Must be validated on the user's specific EV Cockpit.
 *  No ECU write, diagnostic reset, coding, fault erasure or vehicle control.
 *  Uses a paired classic Bluetooth ELM327; do not run Car Scanner simultaneously.
 */
public final class EvCockpitObdActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService timeout = Executors.newSingleThreadScheduledExecutor();
    private final UUID spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private volatile boolean active;
    private volatile BluetoothSocket socket;
    private TextView status;
    private TextView heroSoc, heroRange, heroSource, heroVoltage, heroTemp, heroMode;
    private TextView heroConsumption, heroPower, heroCost, heroHealth;
    private TextView routeDistance, routeEnergyEstimate;
    private LinearLayout sensorDetails;
    private boolean detailsVisible;
    private static final String ADAPTER_PREFS="mediaengine_evcockpit_obd_device";
    private LinearLayout readings;
    private Thread worker;
    private final EvCockpitChargeHistory chargeHistory=new EvCockpitChargeHistory();
    private TextView chargeSummary;
    private TextView pastSessions;
    private final TextView[] fields = new TextView[EvCockpitMebDecoder.SENSORS.size()];
    private final long[] updatedAt = new long[EvCockpitMebDecoder.SENSORS.size()];

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
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        applyFullscreen();
        getWindow().setStatusBarColor(0xff091e2a);
        getWindow().setNavigationBarColor(0xff091e2a);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff091e2a);
        root.setPadding(dp(9),dp(5),dp(9),dp(5));
        setContentView(root);

        LinearLayout navigation=new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(navigation);
        Button back=button("‹ MediaEngine",()->finish());
        navigation.addView(back,new LinearLayout.LayoutParams(0,dp(48),1));
        Button route=button("🗺 Rota",()->startActivity(new Intent(this,EvCockpitNavigatorActivity.class)));
        navigation.addView(route,new LinearLayout.LayoutParams(0,dp(48),1));
        Button stations=button("⚡ İstasyon",()->startActivity(new Intent(this,EvCockpitEvActivity.class)));
        navigation.addView(stations,new LinearLayout.LayoutParams(0,dp(48),1));

        ScrollView scroll=new ScrollView(this);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout body=new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0,dp(6),0,dp(32));
        scroll.addView(body);

        TextView title=label("EV Cockpit PURE · ARAÇ ÖLÇÜMLERİ",19);
        title.setTextColor(0xff6de4d2);
        body.addView(title);
        TextView subtitle=label("2024 · 125 kW · ELM327 / Car Scanner tanılama",11);
        subtitle.setTextColor(0xffb7cdd4);
        body.addView(subtitle);

        LinearLayout hero=panel();
        body.addView(hero,new LinearLayout.LayoutParams(-1,-2));
        heroSource=label("● OBD bağlı değil • Güncel veri yok",14);
        heroSource.setTextColor(0xffffcb78);hero.addView(heroSource);
        hero.addView(label("BATARYA DOLULUK",12));
        heroSoc=label("— %",43);heroSoc.setTextColor(0xff70f1cb);hero.addView(heroSoc);
        hero.addView(label("TAHMİNİ MENZİL",12));
        heroRange=label("— km  •  Veri yok",19);hero.addView(heroRange);
        hero.addView(label("Menzil ancak doğrulanmış araç verisiyle gösterilir.",11));

        LinearLayout cards=new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        body.addView(cards);
        cards.setVisibility(View.GONE);
        body.addView(button("▣ Araç ölçümlerinin ayrıntıları ▾",()->{
            cards.setVisibility(cards.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);
        }));
        LinearLayout row1=new LinearLayout(this);cards.addView(row1);
        heroVoltage=slot(row1,"HV BATARYA GERİLİMİ","— V");
        heroTemp=slot(row1,"BATARYA SICAKLIĞI","— °C");
        LinearLayout row2=new LinearLayout(this);cards.addView(row2);
        heroMode=slot(row2,"ÇALIŞMA / ŞARJ","—");
        heroPower=slot(row2,"ANLIK GÜÇ","— kW");
        LinearLayout row3=new LinearLayout(this);cards.addView(row3);
        heroConsumption=slot(row3,"ORTALAMA TÜKETİM","— kWh/100 km");
        heroCost=slot(row3,"ŞARJ MALİYETİ","— TL");
        LinearLayout row4=new LinearLayout(this);cards.addView(row4);
        heroHealth=slot(row4,"BATARYA SAĞLIĞI (SOH)","— %");
        routeDistance=slot(row4,"SON HESAPLANAN ROTA","— km");
        LinearLayout row5=new LinearLayout(this);cards.addView(row5);
        routeEnergyEstimate=slot(row5,"YOL ENERJİSİ · MODEL","— kWh");
        // ECU kaynak durumu üstte gösterilir; gereksiz ikinci panel yok.
        refreshPlan();

        body.addView(label("SOH, güç ve kesin maliyet: doğrulanmış veri bekleniyor.",11));

        LinearLayout buttons=new LinearLayout(this);
        body.addView(buttons);
        Button connect=button("🔌 OBD bağlan",this::selectAdapter);
        buttons.addView(connect,new LinearLayout.LayoutParams(0,dp(52),2));
        Button stop=button("Bağlantıyı kes",()->{
            disconnect();showStatus("Bağlantı kapatıldı");
        });
        buttons.addView(stop,new LinearLayout.LayoutParams(0,dp(52),1));

        status=label("OBD bağlı değil. Car Scanner'ı kapatıp eşleştirilmiş ELM327'yi seç.",13);
        body.addView(status);
        EvCockpitChargeHistoryStore.load(this,chargeHistory);

        TextView overview=label("⚡  ŞARJ GEÇMİŞİ",18);
        overview.setTextColor(0xff6de4d2);
        body.addView(overview);
        chargeSummary=label("Şarj algılanmadı · OBD gerekli",15);
        body.addView(chargeSummary);
        pastSessions=label("Henüz kayıt yok",13);
        body.addView(pastSessions);
        updateChargeHistory();

        Button sensorsButton=button("▤  TÜM SENSÖRLERİ GÖSTER",()->{
            detailsVisible=!detailsVisible;
            sensorDetails.setVisibility(detailsVisible?View.VISIBLE:View.GONE);
        });
        body.addView(sensorsButton,new LinearLayout.LayoutParams(-1,dp(54)));
        sensorDetails=new LinearLayout(this);
        sensorDetails.setOrientation(LinearLayout.VERTICAL);
        sensorDetails.setVisibility(View.GONE);
        body.addView(sensorDetails);
        readings=sensorDetails;
        String category="";
        for(int i=0;i<EvCockpitMebDecoder.SENSORS.size();i++){
            EvCockpitMebDecoder.Sensor item=EvCockpitMebDecoder.SENSORS.get(i);
            if(!item.category.equals(category)){
                category=item.category;
                TextView heading=label("▰ "+category.toUpperCase(Locale.forLanguageTag("tr-TR")),16);
                heading.setTextColor(0xff6de4d2);readings.addView(heading);
            }
            fields[i]=label(item.name+": — (okunmadı)",15);
            readings.addView(fields[i]);
        }
        body.addView(label("OBD değerleri VIN bazında henüz doğrulanmadı. Uygulama ECU'ya yazmaz, hata silmez, kodlama yapmaz. Car Scanner ile aynı anda bağlanma.",11));
        Button clearHistory=button("Yerel şarj geçmişini sil",()->{
            new AlertDialog.Builder(this).setTitle("Şarj geçmişini sil")
                .setMessage("Yalnızca bu telefondaki şarj kayıtları silinecek.")
                .setNegativeButton("Vazgeç",null)
                .setPositiveButton("Sil",(d,w)->{
                    chargeHistory.clear();EvCockpitChargeHistoryStore.clear(this);updateChargeHistory();
                }).show();
        });
        body.addView(clearHistory);
        // Connect automatically only to a device explicitly selected earlier.
        ui.post(this::autoConnectLastAdapter);
    }

    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
    private GradientDrawable background(int color){
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(color);shape.setCornerRadius(dp(15));return shape;
    }
    private LinearLayout panel(){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(background(0xff112e42));
        box.setPadding(dp(10),dp(7),dp(10),dp(9));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(7),0,dp(7));box.setLayoutParams(p);
        return box;
    }
    private TextView slot(LinearLayout row,String heading,String initial){
        LinearLayout card=panel();
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1f);
        lp.setMargins(dp(3),dp(3),dp(3),dp(3));
        row.addView(card,lp);
        TextView label=label(heading,11);label.setTextColor(0xffb9d0da);card.addView(label);
        TextView value=label(initial,18);card.addView(value);
        return value;
    }
    private Button button(String title,Runnable action){
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);
        b.setTextColor(Color.WHITE);b.setTextSize(12);
        b.setBackground(background(0xff13536b));
        b.setOnClickListener(v->action.run());
        return b;
    }
    private void refreshPlan(){
        EvCockpitEnergyData.Snapshot source=EvCockpitEnergyData.load(this,System.currentTimeMillis());
        if(routeDistance==null || routeEnergyEstimate==null)return;
        if(!source.hasRoute()){
            routeDistance.setText("— km");
            routeEnergyEstimate.setText("— kWh");
            return;
        }
        routeDistance.setText(String.format(Locale.forLanguageTag("tr-TR"),
            "%.1f km",source.routeKm));
        EvCockpitEnergyModel.Result est=EvCockpitEnergyModel.estimate(source.routeKm,
            source.hasSoc()?source.soc:Double.NaN);
        if(est==null){routeEnergyEstimate.setText("— kWh");return;}
        routeEnergyEstimate.setText(String.format(Locale.forLanguageTag("tr-TR"),
            "%.1f – %.1f kWh (tahmin)",est.lowRouteKwh,est.highRouteKwh));
    }
    @Override protected void onResume(){
        super.onResume();
        refreshPlan();
    }
    private void renderCore(String did,String value){
        if(!active || isFinishing())return;
        switch(did){
            case "028C":heroSoc.setText(value);break;
            case "1E3B":heroVoltage.setText(value);break;
            case "2A0B":heroTemp.setText(value);break;
            case "7448":heroMode.setText(value);break;
            default:break;
        }
        if("028C".equals(did))refreshPlan();
        heroSource.setText("● CANLI OBD · Doğrulanmamış MEB profili");
        heroSource.setTextColor(0xff70f1cb);
    }
    private void resetCore(){
        if(heroSoc==null || isFinishing())return;
        heroSource.setText("● OBD bağlı değil • Güncel veri yok");
        heroSource.setTextColor(0xffffcb78);
        heroSoc.setText("— %");heroRange.setText("— km  •  Veri yok");
        heroVoltage.setText("— V");heroTemp.setText("— °C");
        heroMode.setText("—");heroPower.setText("— kW");
        heroConsumption.setText("— kWh/100 km");
        heroCost.setText("— TL");heroHealth.setText("— %");
    }
    @SuppressWarnings("deprecation")
    private void autoConnectLastAdapter(){
        if(active)return;
        if(Build.VERSION.SDK_INT>=31 &&
           checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return;
        String address=getSharedPreferences(ADAPTER_PREFS,MODE_PRIVATE)
            .getString("selected_address",null);
        if(address==null)return;
        try{
            BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
            if(adapter==null || !adapter.isEnabled())return;
            for(BluetoothDevice d:adapter.getBondedDevices()){
                if(address.equals(d.getAddress())){connect(d);return;}
            }
            showStatus("Önceki OBD cihazı eşleşmiş değil; adaptörü yeniden seç.");
        }catch(SecurityException e){showStatus("Bluetooth izni gerekli");}
    }

    private void updateChargeHistory(){
        if(chargeSummary==null || pastSessions==null || isFinishing())return;
        if(chargeHistory.isCharging()){
            double soc=chargeHistory.observedSocChange();
            String change=Double.isFinite(soc)?
                String.format(Locale.forLanguageTag("tr-TR")," · +%.1f%% SoC",soc):"";
            chargeSummary.setText("Şarj oturumu sürüyor • "+chargeHistory.method()+change);
        }else chargeSummary.setText("Aktif şarj oturumu yok");
        List<EvCockpitChargeHistory.Session> history=chargeHistory.completed();
        if(history.isEmpty()){pastSessions.setText("Henüz tamamlanmış şarj kaydı yok.");return;}
        SimpleDateFormat fmt=new SimpleDateFormat("dd.MM.yyyy HH:mm",Locale.forLanguageTag("tr-TR"));
        StringBuilder b=new StringBuilder("SON ŞARJLAR (yalnızca bu cihaz)\n");
        for(EvCockpitChargeHistory.Session h:history){
            b.append(fmt.format(new Date(h.endedAtMs)))
             .append(" • ").append(h.chargingMethod)
             .append(" • ").append(String.format(Locale.forLanguageTag("tr-TR"),
                 "%.1f%% → %.1f%% (%.1f puan)",h.socStart,h.socEnd,h.socChangePercent()))
             .append(" • ").append(h.durationMinutes()).append(" dk\n");
        }
        b.append("KWh ve TL bu kayıtlardan kesin olarak ölçülmez. Fatura için operatör sayacı gerekir.");
        pastSessions.setText(b.toString());
    }
    private TextView label(String s,int sp){
        TextView t=new TextView(this);t.setTextColor(Color.WHITE);t.setTextSize(sp);
        t.setText(s);t.setPadding(12,14,12,14);return t;
    }
    private void showStatus(String text){ui.post(()->{if(!isFinishing()&&status!=null)status.setText(text);});}
    @SuppressWarnings("deprecation")
    private void selectAdapter(){
        if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},701);return;
        }
        try {
            BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
            if(adapter==null){showStatus("Bluetooth donanımı bulunamadı");return;}
            if(!adapter.isEnabled()){showStatus("Önce telefonda Bluetooth'u aç");return;}
            Set<BluetoothDevice> bonded=adapter.getBondedDevices();
            if(bonded.isEmpty()){showStatus("Önce ELM327 adaptörünü Android Bluetooth ayarlarında eşleştir");return;}
            List<BluetoothDevice> devices=new ArrayList<>(bonded);
            String[] names=new String[devices.size()];
            for(int i=0;i<names.length;i++){
                String n=devices.get(i).getName();names[i]=n==null?"Eşleştirilmiş adaptör "+(i+1):n;
            }
            new AlertDialog.Builder(this).setTitle("ELM327 adaptörünü seç")
                .setItems(names,(dialog,index)->{
                    BluetoothDevice chosen=devices.get(index);
                    getSharedPreferences(ADAPTER_PREFS,MODE_PRIVATE).edit()
                        .putString("selected_address",chosen.getAddress()).apply();
                    connect(chosen);
                })
                .setNegativeButton("Vazgeç",null).show();
        }catch(SecurityException e){showStatus("Bluetooth izinleri gerekli");}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==701 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) selectAdapter();
        else if(request==701) showStatus("Bluetooth izni verilmedi");
    }
    private synchronized void connect(BluetoothDevice device){
        disconnect();active=true;
        worker=new Thread(()->readLoop(device),"MediaEngine-OBD-read-only");worker.start();
    }
    private void readLoop(BluetoothDevice device){
        try {
            showStatus("Bluetooth bağlantısı kuruluyor...");
            BluetoothSocket s=device.createRfcommSocketToServiceRecord(spp);
            socket=s;s.connect();
            InputStream in=s.getInputStream();OutputStream out=s.getOutputStream();
            for(String command:new String[]{"ATZ","ATE0","ATL0","ATS0","ATH0","ATSP7","ATSH17FC007B","ATCRA17FE007B"}){
                String response=exchange(in,out,command);
                if(response.indexOf('?')>=0 || response.toUpperCase(Locale.ROOT).contains("ERROR"))
                    throw new IllegalStateException("Adaptör komutu desteklemiyor: "+command);
            }
            showStatus("ELM327 bağlı · MEB DID yanıtları kontrol ediliyor; değerler kalibre edilmedi");
            while(active){
                for(int i=0;i<EvCockpitMebDecoder.SENSORS.size() && active;i++){
                    EvCockpitMebDecoder.Sensor item=EvCockpitMebDecoder.SENSORS.get(i);
                    String answer=exchange(in,out,"22"+item.did);
                    String result=EvCockpitMebDecoder.decode(item,answer);
                    long sampleTime=System.currentTimeMillis();
                    if("028C".equals(item.did)){
                        Double soc=EvCockpitMebDecoder.bmsSoc(answer);
                        if(soc!=null){
                            chargeHistory.acceptSoc(sampleTime,soc);
                            EvCockpitEnergyData.saveSoc(this,soc,sampleTime);
                        }
                    }else if("7448".equals(item.did)){
                        Integer mode=EvCockpitMebDecoder.chargeModeCode(answer);
                        if(mode!=null){
                            EvCockpitChargeHistory.Session saved=chargeHistory.acceptMode(sampleTime,mode);
                            if(saved!=null)EvCockpitChargeHistoryStore.save(this,chargeHistory);
                            ui.post(this::updateChargeHistory);
                        }
                    }
                    final int idx=i;
                    if(result!=null){
                        updatedAt[i]=System.currentTimeMillis();
                        final String line=item.name+": "+result+" • VIN için onaylı";
                        final String did=item.did,displayValue=result;
                        ui.post(()->{
                            if(active&&!isFinishing()){
                                fields[idx].setText(line);
                                renderCore(did,displayValue);
                            }
                        });
                    }else if(EvCockpitMebDecoder.hasPositiveReply(item.did,answer)){
                        final String line=item.name+": DID 0x"+item.did+
                            " · ECU yanıtı var (Car Scanner karşılaştırması gerekli)";
                        ui.post(()->{if(active&&!isFinishing())fields[idx].setText(line);});
                    }else if(System.currentTimeMillis()-updatedAt[i]>25000){
                        final String line=item.name+": —  (ECU yanıtı yok)";
                        ui.post(()->{if(active&&!isFinishing())fields[idx].setText(line);});
                    }
                    Thread.sleep(100);
                }
                Thread.sleep(2000);
            }
        }catch(Exception e){
            if(active)showStatus("OBD ölçümü durdu: "+e.getClass().getSimpleName());
        }finally{disconnect();}
    }
    private String exchange(InputStream in,OutputStream out,String command)throws Exception{
        BluetoothSocket current=socket;
        ScheduledFuture<?> cancel=timeout.schedule(()->{try{if(current!=null)current.close();}catch(Exception ignored){}},8,TimeUnit.SECONDS);
        try{
            out.write((command+"\r").getBytes(StandardCharsets.US_ASCII));out.flush();
            ByteArrayOutputStream b=new ByteArrayOutputStream();
            while(active && b.size()<8192){
                int c=in.read();if(c<0)throw new IllegalStateException("Soket kapandı");
                if(c=='>')return b.toString("US-ASCII");
                b.write(c);
            }
            throw new IllegalStateException("Eksik adaptör yanıtı");
        }finally{cancel.cancel(false);}
    }
    private synchronized void disconnect(){
        active=false;
        chargeHistory.telemetryLost();
        ui.post(this::updateChargeHistory);
        ui.post(this::resetCore);
        ui.post(()->{
            if(!isFinishing())for(int i=0;i<fields.length;i++){
                if(fields[i]!=null){
                    EvCockpitMebDecoder.Sensor item=EvCockpitMebDecoder.SENSORS.get(i);
                    fields[i].setText(item.name+": — (bağlantı yok)");
                }
            }
        });
        BluetoothSocket s=socket;socket=null;
        if(s!=null)try{s.close();}catch(Exception ignored){}
        if(worker!=null && worker!=Thread.currentThread())worker.interrupt();
        worker=null;
    }
    @Override protected void onDestroy(){
        disconnect();timeout.shutdownNow();super.onDestroy();
    }
}
