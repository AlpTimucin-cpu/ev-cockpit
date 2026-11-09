package me.aap.fermata.ev;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Route-triggered, sampled OpenStreetMap fixed speed-camera map layer.
 *
 * Radarbot's APK does not grant a license to its private database or APIs.
 * This retrieves ONLY publicly ODbL-licensed OSM "highway=speed_camera"
 * nodes in bounded sample areas. A public Overpass instance is rate-limited.
 * It is NOT a radar detector, a verified enforcement database or a source
 * for active speed limits, mobile radars or speed-camera-free routes.
 */
final class EvCockpitRoadSafety {
    private static final String OVERPASS=
        "https://overpass-api.de/api/interpreter?data=";
    private static final int LIMIT=120,RESPONSE_LIMIT=320000;
    private EvCockpitRoadSafety(){}

    static boolean validViewport(double south,double west,double north,double east){
        return Double.isFinite(south)&&Double.isFinite(north)&&
            Double.isFinite(west)&&Double.isFinite(east) &&
            south>=35&&north<=43&&west>=25&&east<=46&&
            south<north&&west<east&&(north-south)<=.42&&(east-west)<=.42;
    }

    static String queryText(double south,double west,double north,double east){
        if(!validViewport(south,west,north,east))
            throw new IllegalArgumentException("Zoom in to load fixed cameras");
        // 'out body 120' bounds the response; no relations or national crawls.
        return String.format(Locale.US,
            "[out:json][timeout:13];node[\"highway\"=\"speed_camera\"]"+
            "(%.6f,%.6f,%.6f,%.6f);out body 120;",
            south,west,north,east);
    }

    /**
     * Automatically inspect small, sampled neighborhoods of a *real* route.
     * One bounded Overpass union request; deliberately not a nationwide camera
     * crawl. Coverage is partial: missing pins MUST NOT imply no cameras.
     */
    static JSONObject retrieveCorridor(JSONArray routeCoordinates)throws Exception {
        if(routeCoordinates==null || routeCoordinates.length()<2)
            throw new IllegalArgumentException("No road geometry");
        StringBuilder query=new StringBuilder("[out:json][timeout:13];(");
        int samples=0;
        for(int i=0;i<=8;i++){
            int index=(int)Math.round((routeCoordinates.length()-1)*i/8.0);
            JSONArray xy=routeCoordinates.optJSONArray(index);
            if(xy==null||xy.length()<2)continue;
            double longitude=xy.optDouble(0,Double.NaN);
            double latitude=xy.optDouble(1,Double.NaN);
            if(!Double.isFinite(latitude)||!Double.isFinite(longitude)||
               latitude<35||latitude>43||longitude<25||longitude>46)continue;
            double south=Math.max(35,latitude-.055);
            double north=Math.min(43,latitude+.055);
            double west=Math.max(25,longitude-.065);
            double east=Math.min(46,longitude+.065);
            if(!validViewport(south,west,north,east))continue;
            query.append(String.format(Locale.US,
                "node[\"highway\"=\"speed_camera\"](%.6f,%.6f,%.6f,%.6f);",
                south,west,north,east));
            // Limit display is derived from OSM maxspeed tags only. A nearby
            // way is NOT matched to the driven lane or the direction of travel.
            query.append(String.format(Locale.US,
                "way[\"highway\"][\"maxspeed\"](around:180,%.6f,%.6f);",
                latitude,longitude));
            samples++;
        }
        if(samples<2)throw new IllegalArgumentException("Insufficient route sample");
        query.append(");out center 120;");
        JSONObject result=fetchCameras(query.toString(),35,25,43,46);
        result.put("coverage","sampled route neighborhoods only")
              .put("sampleCount",samples)
              .put("detail","Rota çevresinde sınırlı OSM örneklemesi; yolun tümü taranmadı.");
        return result;
    }

    static JSONObject retrieve(double south,double west,double north,double east)
        throws Exception{
        String query=queryText(south,west,north,east);
        return fetchCameras(query,south,west,north,east);
    }

    private static JSONObject fetchCameras(String query,double south,double west,
        double north,double east)throws Exception{
        String request=OVERPASS+URLEncoder.encode(query,"UTF-8");
        HttpURLConnection c=(HttpURLConnection)new URL(request).openConnection();
        c.setConnectTimeout(8000);c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("User-Agent",
            "EVCOCKPITYolArkadasim-RoadSafety/0.1 (+https://github.com/AlpTimucin-cpu/ID4-Media-Hub)");
        try{
            int status=c.getResponseCode();
            if(status!=200)throw new IllegalStateException("Overpass HTTP "+status);
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){
                byte[] chunk=new byte[4096];int count;
                while((count=in.read(chunk))!=-1){
                    if(buffer.size()+count>RESPONSE_LIMIT)
                        throw new IllegalStateException("Too many OSM records");
                    buffer.write(chunk,0,count);
                }
            }
            JSONObject json=new JSONObject(buffer.toString("UTF-8"));
            JSONArray items=new JSONArray(),speedLimits=new JSONArray(),
                elements=json.optJSONArray("elements");
            if(elements!=null)for(int i=0;i<elements.length()&&items.length()<LIMIT;i++){
                JSONObject node=elements.optJSONObject(i);
                if(node==null)continue;
                String kind=node.optString("type");
                if("way".equals(kind)){
                    JSONObject tags=node.optJSONObject("tags");
                    JSONObject center=node.optJSONObject("center");
                    if(tags==null||center==null||speedLimits.length()>=60)continue;
                    String max=tags.optString("maxspeed","").trim();
                    // Parse ONLY numeric km/h OSM limits; never assume units for
                    // "national", "signals", direction-specific or conditional tags.
                    if(!max.matches("[0-9]{2,3}( km/h)?"))continue;
                    int speed=Integer.parseInt(max.split(" ")[0]);
                    if(speed<20||speed>160)continue;
                    double lat=center.optDouble("lat",Double.NaN);
                    double lon=center.optDouble("lon",Double.NaN);
                    if(!Double.isFinite(lat)||!Double.isFinite(lon)||
                       lat<south||lat>north||lon<west||lon>east)continue;
                    speedLimits.put(new JSONObject().put("id",node.optLong("id"))
                        .put("lat",lat).put("lon",lon).put("speedKmh",speed)
                        .put("source","OpenStreetMap maxspeed")
                        .put("roadMatched",false).put("verifiedLive",false)
                        .put("description","Yakın OSM yol limiti etiketi; sürülen yola eşleşmedi."));
                    continue;
                }
                if(!"node".equals(kind))continue;
                long id=node.optLong("id",0);
                double lat=node.optDouble("lat",Double.NaN);
                double lon=node.optDouble("lon",Double.NaN);
                if(id<=0||!Double.isFinite(lat)||!Double.isFinite(lon)||
                   lat<south||lat>north||lon<west||lon>east)continue;
                JSONObject tags=node.optJSONObject("tags");
                if(tags==null||!"speed_camera".equals(tags.optString("highway")))
                    continue;
                JSONObject item=new JSONObject().put("id",id)
                    .put("lat",lat).put("lon",lon).put("kind","fixed")
                    .put("source","OpenStreetMap");
                String maxspeed=tags.optString("maxspeed","");
                if(maxspeed.matches("[0-9]{1,3}"))
                    item.put("cameraTaggedSpeedKmh",
                        Math.min(180,Integer.parseInt(maxspeed)));
                items.put(item);
            }
            return new JSONObject().put("items",items)
                .put("speedLimits",speedLimits)
                .put("queriedAt",System.currentTimeMillis())
                .put("source","© OpenStreetMap contributors (ODbL)")
                .put("verifiedLive",false)
                .put("coverage","map viewport only")
                .put("detail","Topluluk kayıtları; eksik veya güncelliğini yitirmiş olabilir.");
        }finally{c.disconnect();}
    }
}
