package me.aap.fermata.ev;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import org.json.JSONArray;
import org.json.JSONObject;

/** Foreground-only, opt-in trip summary. GPS coordinates are NEVER persisted. */
final class EvCockpitTripJournal {
    private static final String PREFS="evcockpit_trip_journal_v1",KEY="recent";
    private static final int MAX=30;
    private long startedAt=0,lastAt=0;
    private Location previous;
    private double meters=0;
    private int fixes=0;

    boolean recording(){return startedAt>0;}
    void start(long now){
        if(recording())return;
        startedAt=now;lastAt=0;previous=null;meters=0;fixes=0;
    }
    void accept(Location point,long now){
        if(!recording()||point==null||!point.hasAccuracy()||
            point.getAccuracy()>35||now<point.getTime()||
            now-point.getTime()>15000)return;
        if(previous!=null){
            long dt=point.getTime()-lastAt;
            if(dt>0&&dt<=90000){
                double length=previous.distanceTo(point);
                if(Double.isFinite(length)&&length>=4 &&
                   length<=Math.min(1000,dt*.045+30))meters+=length;
            }
        }
        previous=new Location(point);lastAt=point.getTime();fixes++;
    }
    double distanceKm(){return Math.round(meters/100.0)/10.0;}
    boolean finish(Context c,long now){
        if(!recording())return false;
        long from=startedAt;double km=distanceKm();int count=fixes;
        startedAt=0;lastAt=0;previous=null;meters=0;fixes=0;
        if(now-from<30000||km<.2||count<2)return false;
        try{
            JSONObject item=new JSONObject().put("startedAt",from)
                .put("endedAt",now).put("distanceKm",km)
                .put("durationMin",Math.round((now-from)/60000.0))
                .put("source","GPS user started")
                .put("actualEnergyKwh",JSONObject.NULL)
                .put("actualChargeCostTry",JSONObject.NULL);
            JSONArray rows=new JSONArray().put(item);
            JSONArray prior=list(c);
            for(int i=0;i<prior.length()&&rows.length()<MAX;i++)
                rows.put(prior.getJSONObject(i));
            c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putString(KEY,rows.toString()).apply();
            return true;
        }catch(Exception e){return false;}
    }
    static JSONArray list(Context c){
        JSONArray result=new JSONArray();
        SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        try{
            JSONArray rows=new JSONArray(p.getString(KEY,"[]"));
            for(int i=0;i<rows.length()&&result.length()<MAX;i++){
                JSONObject item=rows.optJSONObject(i);
                if(item==null)continue;
                double km=item.optDouble("distanceKm",Double.NaN);
                long start=item.optLong("startedAt"),end=item.optLong("endedAt");
                if(Double.isFinite(km)&&km>=.2&&km<1500 &&
                    start>0&&end>start&&end-start<72L*3600000L)
                    result.put(item);
            }
        }catch(Exception ignored){}
        return result;
    }
    static void erase(Context c){
        c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }
}
