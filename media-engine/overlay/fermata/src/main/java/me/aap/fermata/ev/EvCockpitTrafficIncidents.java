package me.aap.fermata.ev;

import android.location.Location;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Read-only, official TomTom Orbis Traffic Incident Details v2 integration.
 * Queries a small CURRENT GPS vicinity, not a countrywide scrape.
 * Only GPS-ahead, polyline-near events are annotated; lane and direction
 * are NEVER verified. No fabricated accidents, closure or radar reports.
 */
final class EvCockpitTrafficIncidents {
    private static final String API=
        "https://api.tomtom.com/maps/orbis/traffic/incidents/details?apiVersion=2";
    private EvCockpitTrafficIncidents(){}
    private static boolean valid(double lat,double lon){
        return Double.isFinite(lat)&&Double.isFinite(lon)&&
            lat>=-90&&lat<=90&&lon>=-180&&lon<=180;
    }
    static JSONObject retrieve(String key,JSONArray route,double gpsLat,double gpsLon)
            throws Exception {
        if(key==null||!key.matches("[A-Za-z0-9_-]{15,200}"))
            throw new IllegalArgumentException("Traffic API key missing");
        if(route==null||route.length()<2||!valid(gpsLat,gpsLon))
            throw new IllegalArgumentException("Route or GPS missing");
        // ~25 km wide sample. This cannot claim complete corridor coverage.
        double deltaLat=0.115,deltaLon=Math.min(.165,.115/
            Math.max(.45,Math.cos(Math.toRadians(gpsLat))));
        String bbox=String.format(Locale.US,"%.5f,%.5f,%.5f,%.5f",
            gpsLon-deltaLon,gpsLat-deltaLat,gpsLon+deltaLon,gpsLat+deltaLat);
        String url=API+"&bbox="+bbox+"&timeValidity=present"+
            "&iconCategories=accident,roadClosed,roadWorks,laneClosed,jam";
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(7500);c.setReadTimeout(11000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("TomTom-Api-Key",key);
        c.setRequestProperty("TomTom-Api-Version","2");
        c.setRequestProperty("Attributes",
            "incidents(type,geometry(type,coordinates),properties("+
            "id,iconCategory,events(description),timeValidity,probabilityOfOccurrence))");
        c.setRequestProperty("Accept","application/json");
        try{
            int code=c.getResponseCode();
            if(code!=200)throw new IllegalStateException("Traffic API HTTP "+code);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){
                byte[] buf=new byte[4096];int n;
                while((n=in.read(buf))!=-1){
                    if(bytes.size()+n>650000)throw new IllegalStateException("Traffic payload too large");
                    bytes.write(buf,0,n);
                }
            }
            JSONObject root=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            return filter(root,route,gpsLat,gpsLon);
        }finally{c.disconnect();}
    }
    /** Pure geometry / response filter, independently testable without networking. */
    static JSONObject filter(JSONObject response,JSONArray route,double gpsLat,double gpsLon)
            throws Exception {
        JSONArray original=response.optJSONArray("incidents");
        if(original==null)throw new IllegalArgumentException("Missing incidents");
        // Sample up to ~600 segments along the OSRM/Orbis road geometry.
        int stride=Math.max(1,route.length()/600);
        int n=(route.length()-1)/stride+2;
        double[] lat=new double[n],lon=new double[n],km=new double[n];
        int count=0;
        for(int i=0;i<route.length();i+=stride){
            JSONArray p=route.optJSONArray(i);
            if(p==null||p.length()<2)continue;
            double y=p.optDouble(1,Double.NaN),x=p.optDouble(0,Double.NaN);
            if(!valid(y,x))continue;
            if(count>0)km[count]=km[count-1]+dist(lat[count-1],lon[count-1],y,x)/1000.0;
            lat[count]=y;lon[count]=x;count++;
        }
        JSONArray end=route.optJSONArray(route.length()-1);
        if(end!=null&&count<n){
            double y=end.optDouble(1,Double.NaN),x=end.optDouble(0,Double.NaN);
            if(valid(y,x)&&count>0){
                km[count]=km[count-1]+dist(lat[count-1],lon[count-1],y,x)/1000.0;
                lat[count]=y;lon[count]=x;count++;
            }
        }
        if(count<2)throw new IllegalArgumentException("Empty geometry");
        double[] position=nearest(lat,lon,km,count,gpsLat,gpsLon);
        if(position[0]>700) return new JSONObject().put("items",new JSONArray())
            .put("updatedAt",System.currentTimeMillis())
            .put("source","TomTom Orbis Incident Details")
            .put("coverage","GPS rota dışında; uyarılar devre dışı")
            .put("liveObservations",false);
        double gpsProgress=position[1];
        JSONArray items=new JSONArray();
        Set<String> seen=new HashSet<>();
        for(int i=0;i<original.length()&&i<200&&items.length()<24;i++){
            JSONObject incident=original.optJSONObject(i);
            if(incident==null)continue;
            JSONObject properties=incident.optJSONObject("properties");
            JSONObject geometry=incident.optJSONObject("geometry");
            if(properties==null||geometry==null)continue;
            String category=properties.optString("iconCategory","");
            String label=label(category);
            if(label==null)continue;
            if(!"present".equals(properties.optString("timeValidity","present")))continue;
            String probability=properties.optString("probabilityOfOccurrence","certain");
            if(!"certain".equals(probability)&&!"probable".equals(probability))continue;
            JSONArray coords=geometry.optJSONArray("coordinates");
            if(coords==null)continue;
            JSONArray point="Point".equals(geometry.optString("type"))?
                coords:coords.optJSONArray(Math.max(0,coords.length()/2));
            if(point==null||point.length()<2)continue;
            double x=point.optDouble(0,Double.NaN),y=point.optDouble(1,Double.NaN);
            if(!valid(y,x))continue;
            double[] nearest=nearest(lat,lon,km,count,y,x);
            double ahead=(nearest[1]-gpsProgress)*1000.0;
            // "Ahead" is approximate progress along route; not lane matching.
            if(nearest[0]>650||ahead<350||ahead>12000)continue;
            String id=properties.optString("id","").trim();
            if(id.isEmpty())id=category+"-"+Math.round(y*10000)+"-"+Math.round(x*10000);
            if(!seen.add(id))continue;
            JSONObject out=new JSONObject().put("id",id)
                .put("lat",y).put("lon",x)
                .put("category",category).put("label",label)
                .put("aheadMeters",Math.round(ahead))
                .put("routeOffsetMeters",Math.round(nearest[0]))
                .put("roadDirectionVerified",false)
                .put("laneVerified",false)
                .put("source","TomTom Orbis Incident Details");
            JSONArray events=properties.optJSONArray("events");
            if(events!=null&&events.length()>0){
                String detail=events.optJSONObject(0)==null?"":events.optJSONObject(0)
                    .optString("description","");
                if(detail.length()>180)detail=detail.substring(0,180);
                out.put("description",detail);
            }
            items.put(out);
        }
        return new JSONObject().put("items",items)
            .put("updatedAt",System.currentTimeMillis())
            .put("source","TomTom Orbis Incident Details")
            .put("coverage","GPS çevresinde sınırlı alan; tüm rota ve yol yönü doğrulanmadı")
            .put("liveObservations",false);
    }
    private static String label(String category){
        switch(category){
            case "accident":return "Kaza bildirimi";
            case "roadClosed":return "Yol kapanması bildirimi";
            case "roadWorks":return "Yol çalışması bildirimi";
            case "laneClosed":return "Şerit kapanması bildirimi";
            case "jam":return "Yoğun trafik bildirimi";
            default:return null;
        }
    }
    private static double dist(double a,double b,double c,double d){
        float[] m=new float[1];Location.distanceBetween(a,b,c,d,m);return m[0];
    }
    /** Returns [distance meters, route progress km] using projected segment position. */
    private static double[] nearest(double[] lat,double[] lon,double[] km,
                                    int count,double y,double x){
        double min=Double.POSITIVE_INFINITY,progress=0;
        for(int i=1;i<count;i++){
            double mx=Math.cos(Math.toRadians((lat[i-1]+lat[i]+y)/3.0))*111200;
            double yscale=111200;
            double vx=(lon[i]-lon[i-1])*mx,vy=(lat[i]-lat[i-1])*yscale;
            double dx=(x-lon[i-1])*mx,dy=(y-lat[i-1])*yscale;
            double dot=vx*vx+vy*vy;
            double t=dot<=0?0:Math.max(0,Math.min(1,(dx*vx+dy*vy)/dot));
            double away=Math.hypot(dx-t*vx,dy-t*vy);
            if(away<min){min=away;progress=km[i-1]+(km[i]-km[i-1])*t;}
        }
        return new double[]{min,progress};
    }
}
