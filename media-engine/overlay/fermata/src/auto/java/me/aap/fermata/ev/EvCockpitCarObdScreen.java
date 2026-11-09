package me.aap.fermata.ev;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;

/**
 * Manual, foreground-only classic Bluetooth ELM327 connection in Android Auto.
 * No car-bus writes, DTC erase or unverified BMS values. The adapter must first
 * be paired and explicitly chosen in the PHONE UI.
 */
final class EvCockpitCarObdScreen extends Screen {
    private final EvCockpitLiveTelemetry telemetry;
    private EvCockpitLiveTelemetry.Data latest=new EvCockpitLiveTelemetry.Data();
    private boolean connecting=false;

    EvCockpitCarObdScreen(@NonNull CarContext context) {
        super(context);
        telemetry=new EvCockpitLiveTelemetry(context,data->{
            latest=data;
            if(data.connected || !data.status.contains("bağlanıyor"))connecting=false;
            invalidate();
        });
        getLifecycle().addObserver((LifecycleEventObserver)(owner,event)->{
            if(event==Lifecycle.Event.ON_STOP){
                telemetry.stop();
                connecting=false;
            }else if(event==Lifecycle.Event.ON_DESTROY){
                telemetry.close();
            }
        });
    }

    private static String clean(String value) {
        return value==null || value.trim().isEmpty()?"—":value;
    }

    @NonNull @Override public Template onGetTemplate() {
        EvCockpitLiveTelemetry.Data measured=latest.displayAt(System.currentTimeMillis());
        ItemList.Builder rows=new ItemList.Builder();

        rows.addItem(new Row.Builder().setTitle("Bağlantı durumu")
            .addText(clean(latest.status)).build());

        rows.addItem(new Row.Builder().setTitle("Batarya doluluğu (SoC)")
            .addText(clean(measured.soc))
            .build());
        rows.addItem(new Row.Builder().setTitle("Batarya sıcaklığı / HV gerilimi")
            .addText(clean(measured.batteryTemp)+" · "+clean(measured.voltage))
            .build());

        long now=System.currentTimeMillis();
        boolean ecuFresh=latest.connected && latest.ecuReplyAt>0 &&
            now>=latest.ecuReplyAt && now-latest.ecuReplyAt<12000;
        rows.addItem(new Row.Builder().setTitle("MEB ECU cevap kontrolü")
            .addText(ecuFresh?
                "Pozitif UDS yanıtları: "+latest.ecuReplyCount+" · PID kalibrasyonu bekleniyor":
                "Henüz güncel pozitif ECU yanıtı yok")
            .build());

        rows.addItem(new Row.Builder()
            .setTitle(latest.connected || connecting?"OBD bağlantısını ayır":"OBD bağla")
            .addText(latest.connected || connecting?
                "Car Scanner için adaptörü serbest bırak":
                "Telefonunda önceden eşleştirdiğin ELM327'yi kullan")
            .setOnClickListener(()->{
                if(latest.connected || connecting)disconnect(); else connect();
            }).build());

        if(!EvCockpitMebDecoder.isVehicleProfileValidated()){
            rows.addItem(new Row.Builder()
                .setTitle("MEB PID profili doğrulanmadı")
                .addText("Sayılar Car Scanner kayıtlarıyla doğrulanana kadar boş kalır.")
                .build());
        }
        return new ListTemplate.Builder()
            .setTitle("EV Cockpit · OBD Araç Bağlantısı")
            .setHeaderAction(Action.BACK)
            .setSingleList(rows.build()).build();
    }

    @SuppressWarnings("deprecation")
    private void connect(){
        CarContext c=getCarContext();
        if(Build.VERSION.SDK_INT>=31 &&
           c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=
               PackageManager.PERMISSION_GRANTED){
            message("Bluetooth izni yok. Telefonun uygulama ayarlarından izin ver.");
            return;
        }
        // A direct tap in Android Auto explicitly overrides the previous
        // phone-only auto-reconnect block. Never connect merely on screen open.
        c.getSharedPreferences("evcockpit_obd_pairing_v1",CarContext.MODE_PRIVATE)
            .edit().putBoolean("manual_disconnect",false).apply();
        String pairedMac=c.getSharedPreferences("evcockpit_obd_pairing_v1",CarContext.MODE_PRIVATE)
            .getString("paired_mac","");
        if(pairedMac==null || !pairedMac.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")){
            message("Önce telefondaki Yol Arkadaşım'da OBD adaptörünü seç.");
            return;
        }
        try{
            BluetoothAdapter bt=BluetoothAdapter.getDefaultAdapter();
            if(bt==null||!bt.isEnabled()){
                message("Telefonun Bluetooth bağlantısı kapalı.");return;
            }
            for(BluetoothDevice device:bt.getBondedDevices()){
                if(pairedMac.equalsIgnoreCase(device.getAddress())){
                    connecting=true;
                    message("ELM327 aranıyor · Car Scanner bağlı olmamalı");
                    telemetry.start(device);
                    return;
                }
            }
            message("Kaydedilen ELM327 eşleşmiş aygıtlarda bulunamadı.");
        }catch(SecurityException ex){
            message("Bluetooth izni gerekli. Telefon ayarlarını kontrol et.");
        }catch(RuntimeException ex){
            message("ELM327 bağlantısı başlatılamadı.");
        }
    }

    private void disconnect(){
        telemetry.stop();
        connecting=false;
        // Deliberately do not change the phone's saved pairing or manual
        // disconnect setting: the next car-screen connection must be explicit.
        message("OBD bağlantısı kapatıldı. Adaptör serbest.");
    }

    private void message(String text){
        latest=new EvCockpitLiveTelemetry.Data();
        latest.status=text;
        invalidate();
    }
}
