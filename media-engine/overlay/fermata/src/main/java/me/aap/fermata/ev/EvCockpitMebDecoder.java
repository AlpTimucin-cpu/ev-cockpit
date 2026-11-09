package me.aap.fermata.ev;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Data-only VW MEB sensor registry. Positive UDS 0x22/0x62 replies only.
 * Formulas from public VW MEB community PID catalog: not validated for all
 * 2024 EV Cockpit Pure ECUs. Never returns a value from a negative/incomplete reply.
 */
public final class EvCockpitMebDecoder {
    // No vehicle-specific Car Scanner/ECU response comparison has been signed off.
    // Never turn this on based solely on plausible values or a successful build.
    private static final boolean VEHICLE_PROFILE_VALIDATED=false;
    public static boolean isVehicleProfileValidated(){return VEHICLE_PROFILE_VALIDATED;}
    /** Diagnostic handshake only; not a calibrated sensor measurement. */
    public static boolean hasPositiveReply(String did,String response){
        if(did==null||!did.matches("[0-9A-Fa-f]{4}")||response==null)return false;
        String up=response.toUpperCase(Locale.ROOT);
        if(up.contains("NO DATA")||up.contains("ERROR")||up.contains("7F22")||
           up.contains("STOPPED")||up.contains("UNABLE"))return false;
        String compact=up.replaceAll("[^0-9A-F]","");
        return compact.contains("62"+did.toUpperCase(Locale.ROOT));
    }
    public static final class Sensor {
        public final String did, category, name;
        private final int kind;
        private Sensor(String did,String category,String name,int kind){
            this.did=did;this.category=category;this.name=name;this.kind=kind;
        }
    }
    private static final int SOC=0, VOLT=1, TEMPM=2, MODE=3, COOL=4,
        PUMP=5, TEMPPOINT=6, CURRENT_UNCALIBRATED=7;
    public static final List<Sensor> SENSORS;
    static {
        ArrayList<Sensor> x=new ArrayList<>();
        x.add(new Sensor("028C","Batarya","BMS doluluk",SOC));
        x.add(new Sensor("1E3B","Batarya","HV paket gerilimi",VOLT));
        x.add(new Sensor("2A0B","Batarya","Batarya ana sıcaklık",TEMPM));
        x.add(new Sensor("7448","Şarj","Sürüş / AC / DC modu",MODE));
        x.add(new Sensor("189D","Termal","Soğutma giriş/çıkış",COOL));
        x.add(new Sensor("743B","Termal","Batarya sirkülasyon pompası",PUMP));
        for(int i=0;i<18;i++){
            String did=(i<16)?String.format(Locale.ROOT,"1E%02X",0xAE+i):
                String.format(Locale.ROOT,"74%02X",0x25+i-16);
            x.add(new Sensor(did,"Termal","Sıcaklık noktası "+(i+1),TEMPPOINT));
        }
        x.add(new Sensor("1E3D","Batarya","HV akım ham verisi",CURRENT_UNCALIBRATED));
        SENSORS=Collections.unmodifiableList(x);
    }
    private EvCockpitMebDecoder(){}
    public static String decode(Sensor sensor,String response){
        if(!VEHICLE_PROFILE_VALIDATED || response==null || response.isEmpty() || sensor==null)return null;
        String p=response.toUpperCase(Locale.ROOT);
        if(p.contains("NO DATA") || p.contains("ERROR") || p.contains("STOPPED")
          || p.contains("UNABLE") || p.contains("7F22"))return null;
        String hex=p.replaceAll("[^0-9A-F]","");
        int a=hex.indexOf("62"+sensor.did);
        if(a<0)return null;
        String d=hex.substring(a+6);
        try {
            double n;
            switch(sensor.kind){
                case SOC:
                    if(d.length()<2)return null;
                    n=Integer.parseInt(d.substring(0,2),16)/2.5;
                    return n>=0&&n<=100?format(n,"%"):null;
                case VOLT:
                    if(d.length()<4)return null;
                    n=Integer.parseInt(d.substring(0,4),16)/4.0;
                    return n>=150&&n<=550?format(n,"V"):null;
                case TEMPM:
                    if(d.length()<2)return null;
                    n=Integer.parseInt(d.substring(0,2),16)/2.0-40;
                    return n>=-40&&n<=100?format(n,"°C"):null;
                case TEMPPOINT:
                    if(d.length()<4)return null;
                    n=Integer.parseInt(d.substring(0,4),16)/8.0-40;
                    return n>=-40&&n<=100?format(n,"°C"):null;
                case MODE:
                    if(d.length()<2)return null;
                    switch(Integer.parseInt(d.substring(0,2),16)){
                        case 0:return "Beklemede";case 1:return "Sürüş";
                        case 4:return "AC şarj";case 6:return "DC şarj";
                        default:return null;
                    }
                case PUMP:
                    if(d.length()<2)return null;
                    n=Integer.parseInt(d.substring(0,2),16);
                    return n>=0&&n<=100?format(n,"%"):null;
                case COOL:
                    if(d.length()<8)return null;
                    double input=Integer.parseInt(d.substring(0,4),16)/64.0;
                    double output=Integer.parseInt(d.substring(4,8),16)/64.0;
                    if(input<-40||input>100||output<-40||output>100)return null;
                    return "Giriş "+format(input,"°C")+" · Çıkış "+format(output,"°C");
                case CURRENT_UNCALIBRATED:
                    if(d.length()<8)return null;
                    // Public community current scaling formula has inconsistent
                    // byte weights. Do not expose an unverified amperage/kW.
                    return "Ham 0x"+d.substring(0,8)+" · A/kW doğrulanmadı";
                default:return null;
            }
        }catch(RuntimeException ignored){return null;}
    }
    /** Numeric BMS reading for local session statistics; null on invalid UDS. */
    public static Double bmsSoc(String response){
        if(!VEHICLE_PROFILE_VALIDATED)return null;
        String payload=positivePayload("028C",response,2);
        if(payload==null)return null;
        try{
            double soc=Integer.parseInt(payload.substring(0,2),16)/2.5;
            return soc>=0&&soc<=100?soc:null;
        }catch(RuntimeException e){return null;}
    }
    /** Verified UDS 0x62 status byte only. Unknown code is NOT charging off. */
    public static Integer chargeModeCode(String response){
        if(!VEHICLE_PROFILE_VALIDATED)return null;
        String payload=positivePayload("7448",response,2);
        if(payload==null)return null;
        try{
            int mode=Integer.parseInt(payload.substring(0,2),16);
            return mode==0||mode==1||mode==4||mode==6?mode:null;
        }catch(RuntimeException e){return null;}
    }
    private static String positivePayload(String did,String response,int min){
        if(response==null)return null;
        String up=response.toUpperCase(Locale.ROOT);
        if(up.contains("NO DATA")||up.contains("ERROR")||up.contains("7F22")||
           up.contains("STOPPED")||up.contains("UNABLE"))return null;
        String compact=up.replaceAll("[^0-9A-F]","");
        int n=compact.indexOf("62"+did);
        if(n<0 || compact.length()<n+6+min)return null;
        return compact.substring(n+6);
    }
    private static String format(double n,String unit){
        return String.format(Locale.forLanguageTag("tr-TR"),"%.1f %s",n,unit);
    }
}
