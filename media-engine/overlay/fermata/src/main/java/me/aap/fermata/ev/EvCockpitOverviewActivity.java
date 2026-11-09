package me.aap.fermata.ev;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MediaEngine's one-glance EV Cockpit dashboard. No manual capacity, SoC, price or
 * consumption inputs, no fabricated fallback numbers. First-time Bluetooth
 * adapter selection is an explicit user action; later foreground visits
 * reconnect to the previously selected and still-paired adapter.
 */
public final class EvCockpitOverviewActivity extends Activity {
    private static final int BG=0xff091e2a, CARD=0xff153248, AQUA=0xff75e4d5;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private EvCockpitLiveTelemetry telemetry;
    private EvCockpitLiveTelemetry.Data latest=new EvCockpitLiveTelemetry.Data();
    private TextView state, updated, soc, voltage, temp, charge, cooling, range,
      consumption, power, cost, lastRoute, lastRouteInfo, routeEnergy;
    private boolean resumed, connecting, permissionRequestPending;
    private final Runnable refresh=new Runnable(){
        @Override public void run(){
            if(!resumed)return;
            render(latest);
            handler.postDelayed(this,5000);
        }
    };

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
    @Override public void onCreate(Bundle stateBundle){
        super.onCreate(stateBundle);
        applyFullscreen();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        telemetry=new EvCockpitLiveTelemetry(this,this::render);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        setContentView(scroll);
        LinearLayout page=new LinearLayout(this);
        page.setPadding(dp(10),dp(9),dp(10),dp(20));
        page.setOrientation(LinearLayout.VERTICAL);scroll.addView(page);
        TextView heading=text("MEDIAENGINE  •  EV Cockpit",21,true,AQUA);
        page.addView(heading);
        page.addView(text("2024 electric vehicle Pure  ·  Tek bakışta araç ve rota",11,false,0xffa6c7d0));
        state=text("OBD bağlı değil",12,true,Color.WHITE);
        page.addView(state);
        updated=text("Ölçümler doğrulanmadı",11,false,0xff96b6bf);page.addView(updated);

        // Main view is a one-glance instrument panel; details remain below.
        // No estimated SoC, charging power, price or range is invented.
        page.addView(text("CANLI ARAÇ DURUMU",12,true,AQUA));
        LinearLayout primary=new LinearLayout(this);page.addView(primary);
        soc=addCard(primary,"Batarya","—",true);
        range=addCard(primary,"Menzil","—",true);
        charge=addCard(primary,"Şarj / sürüş","—",true);

        page.addView(text("ROTA VE YOLCULUK",12,true,AQUA));
        LinearLayout journey=new LinearLayout(this);page.addView(journey);
        lastRoute=addCard(journey,"Son rota","—",true);
        cost=addCard(journey,"Şarj maliyeti","—",true);
        lastRouteInfo=text("Henüz kaydedilmiş rota yok.",11,false,0xffa6c7d0);
        page.addView(lastRouteInfo);
        routeEnergy=text("Rota enerjisi: ölçüm yok",12,true,AQUA);
        page.addView(routeEnergy);

        // Traffic and pricing are not live until supported provider feeds exist.
        // Weather is requested on a planned route in the existing navigator.
        page.addView(text("HAVA · Rota ekranında Open-Meteo tahmini  |  TRAFİK · Canlı veri bağlı değil",11,false,0xffa6c7d0));

        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(actions);
        addAction(actions,"Rota / harita",()->startActivity(new Intent(this,EvCockpitNavigatorActivity.class)));
        addAction(actions,"İstasyonlar",()->startActivity(new Intent(this,EvCockpitEvActivity.class)));
        addAction(actions,"OBD bağla",this::selectAdapter);

        Button reveal=new Button(this);
        reveal.setText("▣ Teknik ölçüm ayrıntıları ▾");
        reveal.setAllCaps(false);reveal.setTextSize(12);
        page.addView(reveal);
        LinearLayout technical=new LinearLayout(this);
        technical.setOrientation(LinearLayout.VERTICAL);
        technical.setVisibility(android.view.View.GONE);
        reveal.setOnClickListener(v->technical.setVisibility(
            technical.getVisibility()==android.view.View.VISIBLE?
            android.view.View.GONE:android.view.View.VISIBLE));
        page.addView(technical);
        technical.addView(text("BATARYA VE TÜKETİM • AYRINTILAR",12,true,AQUA));
        LinearLayout r2=new LinearLayout(this);technical.addView(r2);
        voltage=addCard(r2,"HV gerilim","—",true);
        temp=addCard(r2,"Batarya sıcaklığı","—",true);
        power=addCard(r2,"Anlık şarj gücü","—",true);
        LinearLayout r3=new LinearLayout(this);technical.addView(r3);
        cooling=addCard(r3,"Soğutma","—",true);
        consumption=addCard(r3,"Tüketim","—",true);
        technical.addView(text("Güncel ölçüm yoksa menzil, tüketim ve güç değeri gösterilmez.",11,false,0xffa6c7d0));

        page.addView(text("TÜM ÖLÇÜMLER",12,true,AQUA));
        Button sensors=new Button(this);sensors.setText("25 MEB sorgusu ve şarj kayıtları  ›");
        sensors.setAllCaps(false);
        sensors.setOnClickListener(v->startActivity(new Intent(this,EvCockpitNavigatorActivity.class).putExtra("openObdSettings",true)));
        page.addView(sensors);
        page.addView(text("Kaynak: Bluetooth ELM327 · salt-okunur deneysel MEB sorguları. Car Scanner ile aynı anda bağlanma. Çalışmayan sensörler boş bırakılır.",11,false,0xff89aab5));
        render(latest);
    }
    private TextView text(String s,int sp,boolean bold,int color){
        TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(sp);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        t.setPadding(dp(3),dp(4),dp(3),dp(3));return t;
    }
    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
    private void addAction(LinearLayout row,String label,Runnable action){
        Button b=new Button(this);b.setAllCaps(false);b.setText(label);
        b.setTextSize(12);b.setOnClickListener(v->action.run());
        row.addView(b,new LinearLayout.LayoutParams(0,dp(45),1f));
    }
    private TextView addCard(LinearLayout row,String label,String init,boolean wide){
        LinearLayout container=new LinearLayout(this);container.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape=new GradientDrawable();shape.setColor(CARD);
        shape.setCornerRadius(dp(14));container.setBackground(shape);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(78),1f);
        lp.setMargins(dp(2),dp(3),dp(2),dp(3));
        row.addView(container,lp);container.setPadding(dp(7),dp(5),dp(4),dp(4));
        container.addView(text(label,10,false,0xffa9c9d2));
        TextView value=text(init,wide?17:16,true,Color.WHITE);
        value.setMaxLines(2);value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        container.addView(value);return value;
    }
    private void render(EvCockpitLiveTelemetry.Data incoming){
        if(incoming!=null)latest=incoming;
        if(state==null)return;
        long age=System.currentTimeMillis()-latest.updatedAt;
        EvCockpitLiveTelemetry.Data shown=latest.displayAt(System.currentTimeMillis());
        boolean fresh=latest.connected && latest.updatedAt>0 && age<25000;
        state.setText(latest.status);
        updated.setText(fresh?"Canlı OBD ölçümü · "+Math.max(0,age/1000)+" sn önce":
            "Geçerli canlı ölçüm yok · sayı uydurulmaz");
        soc.setText(fresh?shown.soc:"—");
        voltage.setText(fresh?shown.voltage:"—");
        temp.setText(fresh?shown.batteryTemp:"—");
        charge.setText(fresh?shown.chargeMode:"—");
        cooling.setText(fresh?shown.cooling:"—");
        // These are NOT defined by the currently validated VW MEB PID formulas.
        power.setText("Veri yok");
        range.setText("Ölçüm yok");
        consumption.setText("Ölçüm yok");
        cost.setText("Tarife/veri yok");
        EvCockpitEnergyData.Snapshot snapshot=EvCockpitEnergyData.load(this,System.currentTimeMillis());
        if(snapshot.hasRoute()){
            lastRoute.setText(String.format(Locale.forLanguageTag("tr-TR"),
                "%.1f km",snapshot.routeKm));
            lastRouteInfo.setText(String.format(Locale.forLanguageTag("tr-TR"),
                "Son rota: %.0f dk · %s · Trafik hariç",snapshot.routeMinutes,
                new SimpleDateFormat("dd.MM HH:mm",Locale.forLanguageTag("tr-TR"))
                    .format(new Date(snapshot.routeAt))));
            EvCockpitEnergyModel.Result model=EvCockpitEnergyModel.estimate(snapshot.routeKm,
                snapshot.hasSoc()?snapshot.soc:Double.NaN);
            if(model!=null){
                routeEnergy.setText(String.format(Locale.forLanguageTag("tr-TR"),
                    "Rota enerjisi: %.1f–%.1f kWh · PROFİL TAHMİNİ (ölçüm değil)",
                    model.lowRouteKwh,model.highRouteKwh));
            }else routeEnergy.setText("Rota enerji tahmini hesaplanamadı.");
        }else{
            lastRoute.setText("Rota yok");
            lastRouteInfo.setText("Haritadan rota oluşturulduğunda buraya otomatik gelir.");
            routeEnergy.setText("Rota enerjisi: planlanan rota yok");
        }
    }
    @SuppressWarnings("deprecation")
    private void selectAdapter(){
        if(Build.VERSION.SDK_INT>=31 &&
           checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
            permissionRequestPending=true;
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},701);
            return;
        }
        try{
            BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
            if(adapter==null||!adapter.isEnabled()){
                state.setText("Telefon Bluetooth'unu açıp ELM327'yi eşleştir.");return;
            }
            Set<BluetoothDevice> paired=adapter.getBondedDevices();
            if(paired==null || paired.isEmpty()){
                state.setText("Önce Bluetooth ayarlarında ELM327'yi eşleştir.");return;
            }
            List<BluetoothDevice> devices=new ArrayList<>(paired);
            String[] names=new String[devices.size()];
            for(int i=0;i<devices.size();i++){
                BluetoothDevice d=devices.get(i);
                names[i]=d.getName()==null?"Eşleştirilmiş cihaz "+(i+1):d.getName();
            }
            new AlertDialog.Builder(this).setTitle("OBD adaptörünü bir kez seç")
                .setItems(names,(dialog,index)->{
                    BluetoothDevice device=devices.get(index);
                    getSharedPreferences("evcockpit_obd_pairing_v1",MODE_PRIVATE).edit().putString("paired_mac",device.getAddress()).apply();
                    telemetry.start(device);
                })
                .setNegativeButton("Vazgeç",null).show();
        }catch(SecurityException e){state.setText("Bluetooth erişim izni gerekli");}
    }
    @SuppressWarnings("deprecation")
    private void autoConnect(){
        if(Build.VERSION.SDK_INT>=31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return;
        String mac=getSharedPreferences("evcockpit_obd_pairing_v1",MODE_PRIVATE)
            .getString("paired_mac",null);
        // One-time migration from the old Activity-local preference key.
        if(mac==null){
            mac=getPreferences(MODE_PRIVATE).getString("paired_mac",null);
            if(mac!=null)getSharedPreferences("evcockpit_obd_pairing_v1",MODE_PRIVATE)
                .edit().putString("paired_mac",mac).apply();
        }
        if(mac==null)return;
        try{
            BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
            if(adapter==null||!adapter.isEnabled())return;
            for(BluetoothDevice device:adapter.getBondedDevices()){
                if(mac.equals(device.getAddress())){
                    telemetry.start(device);return;
                }
            }
            state.setText("Önceki OBD artık eşleştirilmiş değil");
        }catch(SecurityException e){state.setText("Bluetooth izni gerekli");}
    }
    @Override public void onRequestPermissionsResult(int request,String[] perms,int[] results){
        super.onRequestPermissionsResult(request,perms,results);
        if(request==701 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED){
            permissionRequestPending=false;selectAdapter();
        }else if(request==701){
            permissionRequestPending=false;state.setText("Bluetooth izni verilmedi");
        }
    }
    @Override protected void onResume(){
        super.onResume();resumed=true;handler.post(refresh);autoConnect();
    }
    @Override protected void onPause(){
        resumed=false;handler.removeCallbacks(refresh);telemetry.stop();super.onPause();
    }
    @Override protected void onDestroy(){
        telemetry.close();super.onDestroy();
    }
}
