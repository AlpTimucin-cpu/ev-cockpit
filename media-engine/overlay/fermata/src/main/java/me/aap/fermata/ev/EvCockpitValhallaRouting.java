package me.aap.fermata.ev;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Secondary open-source routing engine. Only real roads from Valhalla's OSRM-
 * compatible response are used; no artificial alternative routes are drawn.
 * FOSSGIS's public endpoint is for fair-use testing, NOT a production SLA.
 */
final class EvCockpitValhallaRouting {
    private static final String ENDPOINT="https://valhalla1.openstreetmap.de/route?json=";
    private EvCockpitValhallaRouting(){}

    static JSONArray alternatives(double a,double b,double c,double d,
                                  double viaLat,double viaLon) throws Exception {
        return variant(a,b,c,d,viaLat,viaLon,"standard");
    }

    /**
     * Returns only actual engine geometry. Toll exclusion is EXPERIMENTAL
     * on public Valhalla servers: hard exclusions can be ignored by a host.
     * Consequently "avoid-tolls" means preference, never verified free travel.
     */
    static JSONArray variant(double a,double b,double c,double d,
                             double viaLat,double viaLon,String mode)throws Exception {
        if(!"standard".equals(mode)&&!"shorter".equals(mode)&&
           !"avoid-tolls".equals(mode)&&!"avoid-motorways".equals(mode))
            throw new IllegalArgumentException("Unknown route mode");
        JSONArray points=new JSONArray()
            .put(new JSONObject().put("lat",a).put("lon",b));
        boolean hasStop=valid(viaLat,viaLon);
        if(hasStop)points.put(new JSONObject().put("lat",viaLat).put("lon",viaLon));
        points.put(new JSONObject().put("lat",c).put("lon",d));
        JSONObject parameters=new JSONObject()
            .put("locations",points)
            .put("costing","shorter".equals(mode)?"auto_shorter":"auto")
            .put("units","km")
            .put("format","osrm")
            .put("shape_format","geojson")
            .put("alternates",hasStop||!"standard".equals(mode)?0:2)
            .put("directions_options",new JSONObject().put("language","tr-TR"));
        if("avoid-tolls".equals(mode)||"avoid-motorways".equals(mode)){
            // Many public servers disable this Valhalla *hard exclusion*;
            // record it only as a request, not a verified road attribute.
            parameters.put("costing_options",new JSONObject()
                .put("auto",new JSONObject()
                    .put("exclude_tolls","avoid-tolls".equals(mode))
                    .put("exclude_highways","avoid-motorways".equals(mode))));
        }
        String encoded=URLEncoder.encode(parameters.toString(),"UTF-8");
        HttpURLConnection connection=(HttpURLConnection)new URL(ENDPOINT+encoded).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept","application/json");
        connection.setRequestProperty("X-Client-Id","EV-Cockpit/preview");
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(12000);
        connection.setInstanceFollowRedirects(false);
        try {
            if(connection.getResponseCode()!=200)
                throw new IllegalStateException("Valhalla HTTP "+connection.getResponseCode());
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            try(InputStream input=connection.getInputStream()){
                byte[] bytes=new byte[8192];int size;
                while((size=input.read(bytes))!=-1){
                    if(buffer.size()+size>3000000)
                        throw new IllegalStateException("Valhalla response too large");
                    buffer.write(bytes,0,size);
                }
            }
            JSONObject root=new JSONObject(buffer.toString("UTF-8"));
            JSONArray routes=root.optJSONArray("routes");
            if(routes==null)throw new IllegalStateException("Valhalla OSRM routes missing");
            JSONArray validRoutes=new JSONArray();
            for(int n=0;n<routes.length()&&validRoutes.length()<3;n++){
                JSONObject route=routes.optJSONObject(n);
                if(route==null)continue;
                JSONObject geometry=route.optJSONObject("geometry");
                JSONArray coordinates=geometry==null?null:geometry.optJSONArray("coordinates");
                JSONArray legs=route.optJSONArray("legs");
                double dist=route.optDouble("distance",Double.NaN);
                double secs=route.optDouble("duration",Double.NaN);
                if(coordinates==null||coordinates.length()<2||coordinates.length()>100000
                    ||legs==null||legs.length()==0||!Double.isFinite(dist)
                    ||!Double.isFinite(secs)||dist<=0||dist>5000000
                    ||secs<=0||secs>604800)continue;
                boolean correct=true;
                for(int i=0;i<coordinates.length();i++){
                    JSONArray pair=coordinates.optJSONArray(i);
                    if(pair==null||pair.length()<2){correct=false;break;}
                    double lon=pair.optDouble(0,Double.NaN);
                    double lat=pair.optDouble(1,Double.NaN);
                    if(!valid(lat,lon)){correct=false;break;}
                }
                if(correct){
                    route.put("evcockpitRoutePreference",mode);
                    route.put("evcockpitTollVerified",false);
                    route.put("evcockpitSource","Valhalla OSM public test");
                    validRoutes.put(route);
                }
            }
            return validRoutes;
        }finally{connection.disconnect();}
    }
    private static boolean valid(double lat,double lon){
        return Double.isFinite(lat)&&Double.isFinite(lon)
            &&lat>=-90&&lat<=90&&lon>=-180&&lon<=180;
    }

    /** Reject duplicate routes from different map engines by their sampled shapes. */
    static boolean distinct(JSONObject a, JSONObject b){
        try {
            JSONArray x=a.getJSONObject("geometry").getJSONArray("coordinates");
            JSONArray y=b.getJSONObject("geometry").getJSONArray("coordinates");
            for(int fraction=1;fraction<=3;fraction++){
                JSONArray p=x.getJSONArray((x.length()-1)*fraction/4);
                JSONArray q=y.getJSONArray((y.length()-1)*fraction/4);
                double diffLat=p.getDouble(1)-q.getDouble(1);
                double diffLon=(p.getDouble(0)-q.getDouble(0))*
                    Math.cos(Math.toRadians(p.getDouble(1)));
                if(Math.hypot(diffLat,diffLon)>0.005)return true;
            }
            double da=a.optDouble("distance",0),db=b.optDouble("distance",0);
            return da>0&&db>0&&Math.abs(da-db)/Math.max(da,db)>.04;
        }catch(Exception e){return false;}
    }
}
