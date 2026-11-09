package me.aap.fermata.ev;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Foreground-only, read-only ELM327 observer for the EV Cockpit overview.
 * Bluetooth must already be paired. Does not share an active socket with Car Scanner.
 * Every displayed measurement is tied to a fresh, positive ECU answer.
 */
final class EvCockpitLiveTelemetry {
    interface Listener { void onData(Data data); }
    static final class Data {
        String status="OBD bağlı değil";
        String soc="—", voltage="—", batteryTemp="—", chargeMode="—", cooling="—";
        long updatedAt=0L;
        long ecuReplyAt=0L;
        int ecuReplyCount=0;
        long socAt=0L, voltageAt=0L, batteryTempAt=0L, chargeModeAt=0L, coolingAt=0L;
        boolean connected=false;
        Data copy(){
            Data d=new Data();d.status=status;d.soc=soc;d.voltage=voltage;
            d.batteryTemp=batteryTemp;d.chargeMode=chargeMode;d.cooling=cooling;
            d.updatedAt=updatedAt;d.connected=connected;
            d.ecuReplyAt=ecuReplyAt;d.ecuReplyCount=ecuReplyCount;
            d.socAt=socAt;d.voltageAt=voltageAt;d.batteryTempAt=batteryTempAt;
            d.chargeModeAt=chargeModeAt;d.coolingAt=coolingAt;return d;
        }
        private static boolean recent(long at,long now){
            return at>0 && now>=at && now-at<10000;
        }
        /** Timestamp each sensor separately: a fresh voltage must not make stale SoC look live. */
        Data displayAt(long now){
            Data d=copy();
            if(!connected || !recent(socAt,now))d.soc="—";
            if(!connected || !recent(voltageAt,now))d.voltage="—";
            if(!connected || !recent(batteryTempAt,now))d.batteryTemp="—";
            if(!connected || !recent(chargeModeAt,now))d.chargeMode="—";
            if(!connected || !recent(coolingAt,now))d.cooling="—";
            return d;
        }
    }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
    private final Listener listener;
    private final Context context;
    private volatile boolean enabled=false;
    private volatile BluetoothSocket socket;
    private Thread thread;
    private long sessionId;
    private Data data=new Data();
    private long lastRawResponseAt=0L;
    private static final UUID SPP=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    EvCockpitLiveTelemetry(Context context,Listener listener){
        this.context=context.getApplicationContext();this.listener=listener;
    }
    synchronized void start(BluetoothDevice device){
        stop();
        enabled=true;
        final long session=++sessionId;
        lastRawResponseAt=0L;
        publish("ELM327 bağlanıyor...",false);
        thread=new Thread(()->loop(device,session),"MediaEngine-EVCOCKPIT-dashboard-readonly");
        thread.start();
    }
    private synchronized boolean active(long session){
        return enabled && sessionId==session;
    }
    private synchronized void publishForSession(long session,String status,boolean connected){
        if(active(session))publish(status,connected);
    }
    private synchronized void publish(String status,boolean connected){
        data.status=status;
        data.connected=connected;
        if(!connected) {
            data.soc="—";data.voltage="—";data.batteryTemp="—";
            data.chargeMode="—";data.cooling="—";data.updatedAt=0L;
            data.ecuReplyAt=0L;data.ecuReplyCount=0;
            data.socAt=0;data.voltageAt=0;data.batteryTempAt=0;
            data.chargeModeAt=0;data.coolingAt=0;
        }
        Data result=data.displayAt(System.currentTimeMillis());
        main.post(()->listener.onData(result));
    }
    private synchronized void measurement(long session,String did,String response){
        if(!active(session))return;
        String decoded=null;
        for(EvCockpitMebDecoder.Sensor sensor:EvCockpitMebDecoder.SENSORS){
            if(did.equals(sensor.did)) {decoded=EvCockpitMebDecoder.decode(sensor,response);break;}
        }
        if(decoded==null){
            if(!EvCockpitMebDecoder.isVehicleProfileValidated() &&
                EvCockpitMebDecoder.hasPositiveReply(did,response)){
                lastRawResponseAt=System.currentTimeMillis();
                data.ecuReplyAt=lastRawResponseAt;
                if(data.ecuReplyCount<1000000)data.ecuReplyCount++;
                publishForSession(session,
                    "ELM327: ECU yanıtı var · Car Scanner ile PID doğrulaması gerekli",true);
            }
            return;
        }
        long now=System.currentTimeMillis();
        switch(did){
            case "028C":
                data.soc=decoded;data.socAt=now;
                Double verified=EvCockpitMebDecoder.bmsSoc(response);
                if(verified!=null)EvCockpitEnergyData.saveSoc(context,verified,System.currentTimeMillis());
                break;
            case "1E3B": data.voltage=decoded;data.voltageAt=now;break;
            case "2A0B": data.batteryTemp=decoded;data.batteryTempAt=now;break;
            case "7448": data.chargeMode=decoded;data.chargeModeAt=now;break;
            case "189D": data.cooling=decoded;data.coolingAt=now;break;
            default:return;
        }
        data.updatedAt=now;
        data.status="ELM327 bağlı · araç üzerinde onaylanmış PID verisi";
        Data result=data.displayAt(now);main.post(()->listener.onData(result));
    }
    private void loop(BluetoothDevice device,long session){
        try{
            BluetoothSocket s=device.createRfcommSocketToServiceRecord(SPP);
            synchronized(this){
                if(!active(session)){
                    try{s.close();}catch(Exception ignored){}
                    return;
                }
                socket=s;
            }
            s.connect();
            if(!active(session))return;
            InputStream in=s.getInputStream();OutputStream out=s.getOutputStream();
            for(String cmd:new String[]{"ATZ","ATE0","ATL0","ATS0","ATH0","ATSP7","ATSH17FC007B","ATCRA17FE007B"}){
                String answer=ask(s,in,out,cmd);
                if(answer.toUpperCase(Locale.ROOT).contains("ERROR")||
                    answer.contains("?"))throw new IllegalStateException("Adaptör komutu reddedildi");
            }
            publishForSession(session,"ELM327 bağlı · veriler okunuyor",true);
            String[] dids={"028C","7448","1E3B","2A0B","189D"};
            int cyclesWithoutValidResponse=0;
            boolean ecuWarningSent=false;
            while(active(session)){
                for(String did:dids){
                    if(!active(session))break;
                    String answer=ask(s,in,out,"22"+did);
                    measurement(session,did,answer);
                    Thread.sleep(90);
                }
                boolean received;
                synchronized(this){
                    received=active(session) &&
                        ((data.updatedAt>0 && System.currentTimeMillis()-data.updatedAt<6000) ||
                         (lastRawResponseAt>0 && System.currentTimeMillis()-lastRawResponseAt<6000));
                }
                if(received){
                    cyclesWithoutValidResponse=0;
                    ecuWarningSent=false;
                }else if(++cyclesWithoutValidResponse>=5 && !ecuWarningSent){
                    // Bluetooth pairing alone does NOT prove a valid MEB
                    // response. Surface the distinction in the cockpit.
                    publishForSession(session,
                        "ELM327 bağlı; EV Cockpit ECU yanıtı yok. Profil, DID ve CAN adresini kontrol et.",
                        true);
                    ecuWarningSent=true;
                }
                Thread.sleep(1600);
            }
        }catch(Exception e){
            publishForSession(session,"OBD verisi alınamadı: "+e.getClass().getSimpleName(),false);
        }finally{
            // Previous worker must never disconnect a newly opened OBD session.
            synchronized(this){if(sessionId==session)stop();}
        }
    }
    private String ask(BluetoothSocket current,InputStream in,OutputStream out,String command)
            throws Exception{
        ScheduledFuture<?> timeout=watchdog.schedule(()->{
            try{if(current!=null)current.close();}catch(Exception ignored){}
        },8,TimeUnit.SECONDS);
        try{
            out.write((command+"\r").getBytes(StandardCharsets.US_ASCII));out.flush();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            while(enabled && bytes.size()<8192){
                int c=in.read();if(c<0)throw new IllegalStateException("Adapter bağlantısı koptu");
                if(c=='>')return bytes.toString("US-ASCII");
                bytes.write(c);
            }
            throw new IllegalStateException("OBD yanıtı eksik");
        }finally{timeout.cancel(false);}
    }
    synchronized void stop(){
        sessionId++;
        enabled=false;
        BluetoothSocket s=socket;socket=null;
        if(s!=null)try{s.close();}catch(Exception ignored){}
        if(thread!=null && thread!=Thread.currentThread())thread.interrupt();
        thread=null;
        publish("OBD bağlı değil",false);
    }
    void close(){stop();watchdog.shutdownNow();}
}
