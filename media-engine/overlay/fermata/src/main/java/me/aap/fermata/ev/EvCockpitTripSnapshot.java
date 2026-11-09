package me.aap.fermata.ev;

import android.content.Context;
import android.content.SharedPreferences;

/** Last planned route metadata; no GPS traces or addresses are stored.
 * Only show recent results; OSRM durations are NOT traffic-aware.
 */
public final class EvCockpitTripSnapshot {
    private static final String KEY="mediaengine_evcockpit_last_route";
    private static final long MAX_AGE_MS=24L*60L*60L*1000L;
    public static final class Trip {
        public final double km, minutes;
        public final long timeMs;
        Trip(double km,double minutes,long timeMs){
            this.km=km;this.minutes=minutes;this.timeMs=timeMs;
        }
    }
    private EvCockpitTripSnapshot(){}
    public static void record(Context c,double km,double minutes){
        if(!Double.isFinite(km)||!Double.isFinite(minutes)||
            km<=0||km>5000||minutes<=0||minutes>10000)return;
        c.getSharedPreferences(KEY,Context.MODE_PRIVATE).edit()
            .putLong("at",System.currentTimeMillis())
            .putLong("distance_01km",Math.round(km*10))
            .putLong("duration_min",Math.round(minutes))
            .apply();
    }
    public static Trip last(Context c){
        SharedPreferences p=c.getSharedPreferences(KEY,Context.MODE_PRIVATE);
        long when=p.getLong("at",0L);
        if(when<=0 || System.currentTimeMillis()<when ||
            System.currentTimeMillis()-when>MAX_AGE_MS)return null;
        double km=p.getLong("distance_01km",0L)/10.0;
        double mins=p.getLong("duration_min",0L);
        if(km<=0||mins<=0)return null;
        return new Trip(km,mins,when);
    }
}
