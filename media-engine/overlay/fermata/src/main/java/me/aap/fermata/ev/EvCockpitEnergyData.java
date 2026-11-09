package me.aap.fermata.ev;

import android.content.Context;
import android.content.SharedPreferences;

/** Cross-screen facts for EV Cockpit energy. No sample/demo telemetry is persisted. */
public final class EvCockpitEnergyData {
    private static final String PREFS="evcockpit_energy_facts_v1";
    public static final long SOC_TTL_MS=10_000L; // last OBD value, not a live socket
    public static final long ROUTE_TTL_MS=12L*60*60*1000;
    private EvCockpitEnergyData(){}

    public static void saveSoc(Context context,double soc,long at){
        if(!Double.isFinite(soc)||soc<0||soc>100||at<=0)return;
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putLong("soc_bits",Double.doubleToRawLongBits(soc))
            .putLong("soc_at",at).apply();
    }
    public static void saveRoute(Context context,double km,double minutes,long at){
        if(!Double.isFinite(km)||km<=0||km>4000||
            !Double.isFinite(minutes)||minutes<=0||at<=0)return;
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putLong("route_km_bits",Double.doubleToRawLongBits(km))
            .putLong("route_min_bits",Double.doubleToRawLongBits(minutes))
            .putLong("route_at",at).apply();
    }
    public static void saveStation(Context context,String station,String operator){
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putString("station",station==null?"":station)
            .putString("operator",operator==null?"":operator).apply();
    }
    public static Snapshot load(Context context,long now){
        SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        double soc=Double.longBitsToDouble(p.getLong("soc_bits",Double.doubleToRawLongBits(Double.NaN)));
        double km=Double.longBitsToDouble(p.getLong("route_km_bits",Double.doubleToRawLongBits(Double.NaN)));
        double minutes=Double.longBitsToDouble(p.getLong("route_min_bits",Double.doubleToRawLongBits(Double.NaN)));
        long at=p.getLong("soc_at",0),routeAt=p.getLong("route_at",0);
        boolean socFresh=at>0&&now>=at&&now-at<=SOC_TTL_MS;
        boolean routeFresh=routeAt>0&&now>=routeAt&&now-routeAt<=ROUTE_TTL_MS;
        return new Snapshot(socFresh?soc:Double.NaN,routeFresh?km:Double.NaN,
            routeFresh?minutes:Double.NaN,at,routeAt,
            p.getString("station",""),p.getString("operator",""));
    }
    public static final class Snapshot {
        public final double soc, routeKm,routeMinutes;
        public final long socAt,routeAt;
        public final String station,operator;
        Snapshot(double soc,double routeKm,double routeMinutes,long socAt,long routeAt,
                String station,String operator){
            this.soc=soc;this.routeKm=routeKm;this.routeMinutes=routeMinutes;
            this.socAt=socAt;this.routeAt=routeAt;
            this.station=station;this.operator=operator;
        }
        public boolean hasSoc(){return Double.isFinite(soc)&&soc>=0&&soc<=100;}
        public boolean hasRoute(){return Double.isFinite(routeKm)&&routeKm>0;}
    }
}
