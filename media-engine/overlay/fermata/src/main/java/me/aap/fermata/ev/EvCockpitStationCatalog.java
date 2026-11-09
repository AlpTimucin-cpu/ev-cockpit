package me.aap.fermata.ev;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Read-only EPDK public station snapshot. Operates outside the Android UI thread.
 * No realtime availability or connector-specific price is claimed.
 * Downloads only a bounded gzip snapshot; returns a capped set of pins to WebView.
 */
final class EvCockpitStationCatalog {
    private static final String SNAPSHOT_URL =
        "https://raw.githubusercontent.com/AlpTimucin-cpu/ID4-Media-Hub/" +
        "feature/media-engine-migration/data/charging/epdk_public_stations.json.gz";
    private static final long FETCH_TTL_MS = 6L*60*60*1000;
    private static final int MAX_GZIP = 10_000_000, MAX_JSON = 22_000_000;
    private final Context context;
    private JSONArray stations;
    private String verifiedAt = "";
    private long snapshotAgeHours;

    EvCockpitStationCatalog(Context context) {this.context=context.getApplicationContext();}

    static final class Point {
        final double lat,lon;
        Point(double lat,double lon){this.lat=lat;this.lon=lon;}
    }
    private static final class Candidate {
        final JSONObject station;
        final double rank;
        final int segment;
        final double progressKm;
        final EvCockpitChargeAdvisor.Assessment assessment;
        Candidate(JSONObject station,double rank,int segment,
                  double progressKm,EvCockpitChargeAdvisor.Assessment assessment){
            this.station=station;this.rank=rank;this.segment=segment;
            this.progressKm=progressKm;this.assessment=assessment;
        }
    }
    private static boolean valid(double lat,double lon){
        return Double.isFinite(lat)&&Double.isFinite(lon)&&
               lat>=35&&lat<=43&&lon>=25&&lon<=46;
    }

    private static byte[] readLimited(InputStream stream,int limit)throws Exception {
        try(InputStream in=stream;ByteArrayOutputStream result=new ByteArrayOutputStream()){
            byte[] chunk=new byte[8192];
            for(int n;(n=in.read(chunk))!=-1;){
                if(result.size()+n>limit)throw new IllegalStateException("EPDK dosyası çok büyük");
                result.write(chunk,0,n);
            }
            return result.toByteArray();
        }
    }

    private byte[] download()throws Exception{
        HttpURLConnection conn=(HttpURLConnection)new URL(SNAPSHOT_URL).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(9000);conn.setReadTimeout(12000);
        conn.setRequestProperty("Accept-Encoding","identity");
        conn.setRequestProperty("User-Agent","MediaEngine-EVCOCKPIT-EPDK-Reader/1.0");
        try {
            if(conn.getResponseCode()!=200)throw new IllegalStateException("EPDK HTTP "+conn.getResponseCode());
            return readLimited(conn.getInputStream(),MAX_GZIP);
        } finally {conn.disconnect();}
    }

    private void parse(byte[] gzip)throws Exception {
        byte[] bytes=readLimited(new GZIPInputStream(new ByteArrayInputStream(gzip)),MAX_JSON);
        JSONObject root=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        if(root.optInt("schema_version")!=1 ||
           root.optBoolean("prices_included",true) ||
           root.optBoolean("live_status_included",true)){
            throw new IllegalStateException("EPDK istasyon şeması uyumsuz");
        }
        JSONArray data=root.getJSONArray("stations");
        if(data.length()<5000 || data.length()>100000)
            throw new IllegalStateException("Eksik EPDK verisi");
        Instant stamp=Instant.parse(root.getString("verified_at_utc"));
        long h=Duration.between(stamp,Instant.now()).toHours();
        if(h<0)throw new IllegalStateException("İleri tarihli istasyon verisi");
        stations=data;
        verifiedAt=stamp.toString();
        snapshotAgeHours=h;
    }

    synchronized void ensureLoaded()throws Exception {
        if(stations!=null)return;
        File file=new File(context.getCacheDir(),"mediaengine_evcockpit_epdk.gz");
        boolean fresh=file.isFile() &&
            System.currentTimeMillis()-file.lastModified()>=0 &&
            System.currentTimeMillis()-file.lastModified()<FETCH_TTL_MS;
        byte[] zipped=null;
        if(fresh)try{zipped=readLimited(new FileInputStream(file),MAX_GZIP);parse(zipped);}
            catch(Exception ignored){zipped=null;stations=null;}
        if(stations!=null)return;
        try {
            zipped=download();
            parse(zipped);
            try(FileOutputStream out=new FileOutputStream(file)){out.write(zipped);}
        } catch(Exception downloadError){
            if(file.isFile()){
                zipped=readLimited(new FileInputStream(file),MAX_GZIP);
                parse(zipped);
            }else throw downloadError;
        }
    }

    private static boolean hasCCS(JSONObject s){
        JSONArray connectors=s.optJSONArray("sockets");
        if(connectors==null)return false;
        for(int i=0;i<connectors.length();i++){
            JSONObject c=connectors.optJSONObject(i);
            if(c==null)continue;
            String type=c.optString("type").toLowerCase(Locale.ROOT);
            if(type.contains("ccs")||type.contains("combo"))return true;
        }
        return false;
    }
    private static String types(JSONObject station){
        JSONArray sockets=station.optJSONArray("sockets");
        int ccs=0,ac=0;
        if(sockets!=null)for(int i=0;i<sockets.length();i++){
            JSONObject s=sockets.optJSONObject(i);if(s==null)continue;
            String t=s.optString("type").toLowerCase(Locale.ROOT);
            if(t.contains("ccs")||t.contains("combo"))ccs++;
            if(t.contains("type 2")||t.contains("type2"))ac++;
        }
        return "CCS "+ccs+" · Type 2 "+ac;
    }
    private static JSONObject pin(JSONObject s,double distanceKm)throws Exception{
        return new JSONObject().put("lat",s.getDouble("lat")).put("lon",s.getDouble("lon"))
            .put("title",s.optString("name")).put("operator",s.optString("operator"))
            .put("address",s.optString("address")).put("sockets",types(s))
            .put("ccs",hasCCS(s)).put("distanceKm",distanceKm);
    }
    private JSONObject response(String kind,List<Candidate> candidates,int total,
                                boolean onlyCcs)throws Exception {
        candidates.sort(Comparator.comparingDouble(c->c.rank));
        JSONArray result=new JSONArray();
        int cap="route".equals(kind)?120:180;
        List<Candidate> selected=new ArrayList<>();
        if("route".equals(kind)){
            // First choose one nearby CCS stop from each longitudinal route sector.
            // This prevents 45 city-centre pins from hiding rural highway stations.
            java.util.HashSet<Integer> seen=new java.util.HashSet<>();
            for(Candidate c:candidates) {
                if(seen.add(c.segment))selected.add(c);
            }
        }
        for(Candidate c:candidates){
            if(selected.size()>=cap)break;
            if(!selected.contains(c))selected.add(c);
        }
        for(Candidate c:selected){
            if(result.length()>=cap)break;
            JSONObject item=pin(c.station,c.rank);
            if("route".equals(kind)){
                item.put("routeProgressKm",Math.round(c.progressKm*10.0)/10.0);
                item.put("corridorKm",Math.round(c.rank*10.0)/10.0);
                item.put("snapshotAt",verifiedAt);
                item.put("detourMinutes",JSONObject.NULL); // no road detour API evidence
                if(c.assessment!=null && c.assessment.hasSoc){
                    item.put("modelCandidate",c.assessment.modelCandidate);
                    if(c.assessment.modelCandidate){
                        item.put("arrivalSocLower",Math.max(0,c.assessment.projectedArrivalSocLower));
                        item.put("arrivalSocUpper",Math.min(100,c.assessment.projectedArrivalSocUpper));
                    }
                }
            }
            result.put(item);
        }
        return new JSONObject()
            .put("kind",kind).put("items",result).put("total",total)
            .put("updatedAt",verifiedAt).put("snapshotAgeHours",snapshotAgeHours)
            .put("liveAvailability",false).put("livePrices",false)
            .put("onlyCcs",onlyCcs);
    }

    synchronized JSONObject inBounds(double south,double west,double north,double east)throws Exception{
        return inBounds(south,west,north,east,false);
    }
    synchronized JSONObject inBounds(double south,double west,double north,double east,
                                     boolean onlyCcs)throws Exception{
        if(!Double.isFinite(south)||!Double.isFinite(west)||
           !Double.isFinite(north)||!Double.isFinite(east)||
           south>north||west>east)
            throw new IllegalArgumentException("Harita sınırları geçersiz");
        // A wide zoom level often includes Greece/Black Sea: safely clip to Türkiye.
        south=Math.max(south,35);north=Math.min(north,43);
        west=Math.max(west,25);east=Math.min(east,46);
        if(south>north||west>east)
            return new JSONObject().put("kind","visible").put("items",new JSONArray())
                .put("total",0).put("updatedAt",verifiedAt).put("snapshotAgeHours",snapshotAgeHours)
                .put("liveAvailability",false).put("livePrices",false);
        ensureLoaded();
        List<Candidate> candidates=new ArrayList<>();
        int total=0;
        double centerLat=(south+north)/2.0,centerLon=(west+east)/2.0;
        for(int i=0;i<stations.length();i++){
            JSONObject station=stations.optJSONObject(i);
            if(station==null)continue;
            double lat=station.optDouble("lat",Double.NaN);
            double lon=station.optDouble("lon",Double.NaN);
            if(!valid(lat,lon)||lat<south||lat>north||lon<west||lon>east||
                (onlyCcs && !hasCCS(station)))continue;
            total++;
            candidates.add(new Candidate(station,haversineKm(lat,lon,centerLat,centerLon),
                0,Double.NaN,null));
        }
        // A nationwide map must represent EVERY matching station, not silently
        // drop all but the 55 geographically central pins. Aggregate into <=96
        // clickable cells; users zoom to obtain individual pins.
        if(candidates.size()>160){
            final int cols=12,rows=8;
            int[][] counts=new int[rows][cols],ccsCounts=new int[rows][cols];
            double[][] sumLat=new double[rows][cols],sumLon=new double[rows][cols];
            double height=Math.max(0.00001,north-south),width=Math.max(0.00001,east-west);
            for(Candidate c:candidates){
                double lat=c.station.optDouble("lat"),lon=c.station.optDouble("lon");
                int row=Math.max(0,Math.min(rows-1,(int)((lat-south)/height*rows)));
                int col=Math.max(0,Math.min(cols-1,(int)((lon-west)/width*cols)));
                counts[row][col]++;
                if(hasCCS(c.station))ccsCounts[row][col]++;
                sumLat[row][col]+=lat;sumLon[row][col]+=lon;
            }
            JSONArray markers=new JSONArray();
            for(int r=0;r<rows;r++)for(int c=0;c<cols;c++){
                int count=counts[r][c];
                if(count==0)continue;
                markers.put(new JSONObject().put("cluster",true)
                    .put("count",count).put("ccsCount",ccsCounts[r][c])
                    .put("lat",sumLat[r][c]/count).put("lon",sumLon[r][c]/count));
            }
            return new JSONObject().put("kind","visible").put("items",markers)
                .put("total",total).put("displayed",markers.length())
                .put("clustered",true).put("onlyCcs",onlyCcs)
                .put("updatedAt",verifiedAt).put("snapshotAgeHours",snapshotAgeHours)
                .put("liveAvailability",false).put("livePrices",false);
        }
        JSONObject data=response("visible",candidates,total,onlyCcs);
        data.put("displayed",data.getJSONArray("items").length())
            .put("clustered",false);
        return data;
    }

    synchronized JSONObject alongRoute(List<Point> path,double roadKm,double observedSoc)throws Exception{
        if(path==null||path.size()<2 || !Double.isFinite(roadKm)||roadKm<=0)
            throw new IllegalArgumentException("Rota yok");
        ensureLoaded();
        List<Point> selected=new ArrayList<>();
        final int steps=Math.min(180,path.size()-1);
        for(int i=0;i<=steps;i++){
            Point p=path.get((int)Math.round((path.size()-1)*(double)i/steps));
            if(valid(p.lat,p.lon))selected.add(p);
        }
        if(selected.size()<2)throw new IllegalArgumentException("Rota koordinatları geçersiz");
        double[] cumulative=new double[selected.size()];
        for(int j=1;j<selected.size();j++)
            cumulative[j]=cumulative[j-1]+haversineKm(
                selected.get(j-1).lat,selected.get(j-1).lon,
                selected.get(j).lat,selected.get(j).lon);
        if(cumulative[selected.size()-1]<=0)throw new IllegalArgumentException("Boş rota geometrisi");
        double south=90,north=-90,west=180,east=-180;
        for(Point p:selected){
            south=Math.min(south,p.lat);north=Math.max(north,p.lat);
            west=Math.min(west,p.lon);east=Math.max(east,p.lon);
        }
        // Bounding-box prefilter for 5 km straight-line corridor; road access not inferred.
        south-=0.07;north+=0.07;west-=0.10;east+=0.10;
        List<Candidate> candidates=new ArrayList<>();
        int total=0;
        for(int i=0;i<stations.length();i++){
            JSONObject s=stations.optJSONObject(i);if(s==null||!hasCCS(s))continue;
            double lat=s.optDouble("lat",Double.NaN),lon=s.optDouble("lon",Double.NaN);
            if(!valid(lat,lon)||lat<south||lat>north||lon<west||lon>east)continue;
            double nearest=Double.POSITIVE_INFINITY;
            int closestSegment=0;
            for(int j=1;j<selected.size();j++){
                double d=distanceToSegmentKm(lat,lon,selected.get(j-1),selected.get(j));
                if(d<nearest){nearest=d;closestSegment=j;}
            }
            if(nearest>5.0)continue; // 5 km lateral corridor: not a road-access or detour-time claim
            total++;
            double frac=projectionFraction(lat,lon,
                selected.get(closestSegment-1),selected.get(closestSegment));
            double progress=(cumulative[closestSegment-1]+
                frac*(cumulative[closestSegment]-cumulative[closestSegment-1]))/
                cumulative[selected.size()-1]*roadKm;
            EvCockpitChargeAdvisor.Assessment advice=EvCockpitChargeAdvisor.assess(
                roadKm,observedSoc,Math.min(roadKm,progress),nearest);
            int sector=(int)(20.0*progress/roadKm);
            candidates.add(new Candidate(s,nearest,sector,progress,advice));
        }
        JSONObject packet=response("route",candidates,total,true);
        boolean socKnown=Double.isFinite(observedSoc)&&observedSoc>=0&&observedSoc<=100;
        boolean chargeLikely=socKnown &&
            EvCockpitChargeAdvisor.assess(roadKm,observedSoc,0,0).chargeLikely;
        packet.put("chargeModel",!socKnown?"no-soc":chargeLikely?"charge-likely":"no-stop-model");
        packet.put("modelReserveSoc",EvCockpitChargeAdvisor.RESERVE_SOC);
        packet.put("ccsCorridorRadiusKm",5.0);
        packet.put("modelNote","52 kWh, 16–24 kWh/100 km ve %15 rezerv varsayımı. "
            +"Gerçek sapma, yol erişimi ve soket doluluğu doğrulanmadı.");
        Candidate recommended=null;
        for(Candidate c:candidates) {
            if(c.assessment.modelCandidate &&
               (recommended==null||c.assessment.score<recommended.assessment.score))
                recommended=c;
        }
        if(recommended!=null){
            JSONObject p=pin(recommended.station,recommended.rank);
            p.put("routeProgressKm",Math.round(recommended.progressKm*10)/10.0);
            p.put("corridorKm",Math.round(recommended.rank*10)/10.0);
            p.put("snapshotAt",verifiedAt);
            p.put("arrivalSocLower",
                Math.max(0,recommended.assessment.projectedArrivalSocLower));
            p.put("arrivalSocUpper",
                Math.min(100,recommended.assessment.projectedArrivalSocUpper));
            packet.put("suggested",p);
        }
        return packet;
    }

    private static double haversineKm(double latA,double lonA,double latB,double lonB){
        double dLat=Math.toRadians(latB-latA),dLon=Math.toRadians(lonB-lonA);
        double a=Math.pow(Math.sin(dLat/2),2)+Math.cos(Math.toRadians(latA))*
            Math.cos(Math.toRadians(latB))*Math.pow(Math.sin(dLon/2),2);
        return 12742.0*Math.asin(Math.min(1,Math.sqrt(Math.max(0,a))));
    }
    /** Returns the closest projected position on one polyline segment. */
    private static double projectionFraction(double lat,double lon,Point a,Point b){
        double scale=111.2,cos=Math.cos(Math.toRadians((lat+a.lat+b.lat)/3.0));
        double x=(lon-a.lon)*cos*scale, y=(lat-a.lat)*scale;
        double vx=(b.lon-a.lon)*cos*scale,vy=(b.lat-a.lat)*scale;
        double len=vx*vx+vy*vy;
        return len>0?Math.max(0,Math.min(1,(x*vx+y*vy)/len)):0;
    }
    /** Equirectangular projection is adequate for a 12-km corridor shortlist. */
    static double distanceToSegmentKm(double lat,double lon,Point a,Point b){
        double scale=111.2,cos=Math.cos(Math.toRadians((lat+a.lat+b.lat)/3.0));
        double x=(lon-a.lon)*cos*scale, y=(lat-a.lat)*scale;
        double vx=(b.lon-a.lon)*cos*scale,vy=(b.lat-a.lat)*scale;
        double t=projectionFraction(lat,lon,a,b);
        return Math.hypot(x-t*vx,y-t*vy);
    }
}
