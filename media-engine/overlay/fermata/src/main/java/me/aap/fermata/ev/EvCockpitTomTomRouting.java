package me.aap.fermata.ev;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Official TomTom Orbis Routing v3, NOT the Node.js MCP server.
 * Geometry/traffic data only from verified HTTP response; no fake maneuvers. */
final class EvCockpitTomTomRouting {
    private static final String URL_V3="https://api.tomtom.com/maps/orbis/routing/routes/calculate?apiVersion=3";
    private EvCockpitTomTomRouting(){}
    private static JSONObject point(double lat,double lon)throws Exception{
        return new JSONObject().put("type","Point")
            .put("coordinates",new JSONArray().put(lon).put(lat));
    }
    static JSONObject request(String key,double a,double b,double c,double d,
                              double viaLat,double viaLon)throws Exception{
        if(key==null||!key.matches("[A-Za-z0-9_-]{15,200}"))
            throw new IllegalArgumentException("Missing TomTom key");
        JSONObject locations=new JSONObject().put("origin",point(a,b))
            .put("destination",point(c,d));
        if(Double.isFinite(viaLat)&&Double.isFinite(viaLon))
            locations.put("waypoints",new JSONObject().put("type","MultiPoint")
                .put("coordinates",new JSONArray()
                    .put(new JSONArray().put(viaLon).put(viaLat))));
        JSONObject request=new JSONObject()
            .put("routePlanningLocations",locations)
            .put("maxPathAlternativeRoutes",5).put("traffic","live")
            .put("routeType","efficient").put("travelMode","car")
            .put("vehicleEngineType","electric").put("departureDateTime","now");
        HttpURLConnection connection=(HttpURLConnection)new URL(URL_V3).openConnection();
        connection.setConnectTimeout(8000);connection.setReadTimeout(16000);
        connection.setInstanceFollowRedirects(false);connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("TomTom-Api-Key",key);
        // Orbis Routing v3 requires Attributes to select route geometry and
        // summary fields; without this header the endpoint can reject POST.
        connection.setRequestProperty("Attributes","routes.summary,routes.legs");
        connection.setRequestProperty("Content-Type","application/json");
        connection.setRequestProperty("Accept","application/json");
        byte[] body=request.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try{
            try(OutputStream output=connection.getOutputStream()){output.write(body);}
            if(connection.getResponseCode()!=200)
                throw new IllegalStateException("TomTom HTTP "+connection.getResponseCode());
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            try(InputStream input=connection.getInputStream()){
                byte[] bytes=new byte[8192];int size;
                while((size=input.read(bytes))!=-1){
                    if(buffer.size()+size>3000000)
                        throw new IllegalStateException("TomTom response exceeds 3MB");
                    buffer.write(bytes,0,size);
                }
            }
            return adapt(new JSONObject(buffer.toString("UTF-8")));
        }finally{connection.disconnect();}
    }
    /** v3 geometry lives in legs[].path.coordinates as [longitude,latitude]. */
    static JSONObject adapt(JSONObject response)throws Exception{
        JSONArray input=response.getJSONArray("routes"),result=new JSONArray();
        for(int i=0;i<input.length()&&result.length()<6;i++){
            JSONObject route=input.optJSONObject(i);
            JSONObject summary=route==null?null:route.optJSONObject("summary");
            if(summary==null)continue;
            double distance=summary.optDouble("lengthInMeters",Double.NaN);
            double duration=summary.optDouble("travelDurationInSeconds",Double.NaN);
            if(!Double.isFinite(distance)||distance<=0||distance>5e6||
               !Double.isFinite(duration)||duration<=0||duration>604800)continue;
            JSONArray legs=route.optJSONArray("legs");
            JSONArray all=new JSONArray(),parsed=new JSONArray();
            if(legs!=null)for(int j=0;j<legs.length();j++){
                JSONObject leg=legs.optJSONObject(j);
                JSONObject path=leg==null?null:leg.optJSONObject("path");
                JSONArray coords=path==null?null:path.optJSONArray("coordinates");
                if(coords==null||coords.length()<2)continue;
                for(int n=0;n<coords.length();n++){
                    JSONArray pair=coords.optJSONArray(n);
                    if(pair==null||pair.length()<2)continue;
                    double lon=pair.optDouble(0,Double.NaN);
                    double lat=pair.optDouble(1,Double.NaN);
                    if(!Double.isFinite(lat)||!Double.isFinite(lon)||
                       lat < -90||lat>90||lon < -180||lon>180)
                        throw new IllegalArgumentException("Invalid TomTom coordinate");
                    if(j>0&&n==0)continue;
                    all.put(new JSONArray().put(lon).put(lat));
                }
                parsed.put(new JSONObject().put("steps",new JSONArray()));
            }
            if(all.length()<2)continue;
            JSONObject converted=new JSONObject().put("distance",distance)
                .put("duration",duration)
                .put("geometry",new JSONObject().put("coordinates",all))
                .put("legs",parsed).put("trafficAware",true);
            double delay=summary.optDouble("trafficDelayDurationInSeconds",Double.NaN);
            if(Double.isFinite(delay)&&delay>=0&&delay<=duration)
                converted.put("trafficDelaySeconds",delay);
            result.put(converted);
        }
        if(result.length()==0)throw new IllegalStateException("No valid TomTom routes");
        return new JSONObject().put("routes",result);
    }
}
