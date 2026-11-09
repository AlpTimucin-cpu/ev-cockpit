package com.evcockpit.mediahub

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * Address search and experimental driving-route preview for EmbeddedMapActivity.
 * Both explicitly triggered by the user and run off the UI thread.
 *
 * Nominatim / OSRM public services are single-user beta dependencies, not
 * guaranteed production routing or voice guidance. No background polling.
 */
object EmbeddedMapServices {
    private const val USER_AGENT =
        "EV-Cockpit-EmbeddedMap/1.2 (+https://github.com/AlpTimucin-cpu/ID4-Media-Hub)"
    private const val GEOCODER="nominatim.openstreetmap.org"
    private const val ROUTER="router.project-osrm.org"
    @Volatile private var lastLookupMs=0L

    data class Address(val name:String,val lat:Double,val lon:Double)
    fun valid(lat:Double,lon:Double):Boolean=
        lat.isFinite()&&lon.isFinite() && lat in 35.0..43.0 && lon in 25.0..46.0

    @Synchronized fun lookup(text:String):List<Address> {
        val term=text.trim().take(160)
        require(term.length>=3)
        val now=System.currentTimeMillis()
        require(now-lastLookupMs >= 3500L) {"Adres sorgu hız sınırı"}
        lastLookupMs=now
        val path="/search?format=jsonv2&countrycodes=tr&limit=7&q="+
            URLEncoder.encode(term,"UTF-8")
        val result=JSONArray(request(GEOCODER,path,250_000,16000))
        return buildList{
            for(i in 0 until minOf(result.length(),7)){
                val o=result.optJSONObject(i)?:continue
                val lat=o.optString("lat").toDoubleOrNull()?:continue
                val lon=o.optString("lon").toDoubleOrNull()?:continue
                if(valid(lat,lon)){
                    add(Address(o.optString("display_name").take(190),lat,lon))
                }
            }
        }
    }

    /** Only route with explicitly selected start and end. No GPS required. */
    fun route(fromLon:Double,fromLat:Double,toLon:Double,toLat:Double):JSONObject {
        require(valid(fromLat,fromLon)&&valid(toLat,toLon))
        fun n(value:Double)=String.format(Locale.US,"%.6f",value)
        val path="/route/v1/driving/"+n(fromLon)+","+n(fromLat)+";"+
            n(toLon)+","+n(toLat)+"?overview=simplified&geometries=geojson&steps=false"
        val root=JSONObject(request(ROUTER,path,1_200_000,18000))
        require(root.optString("code")=="Ok"){"Route service not available"}
        val first=root.optJSONArray("routes")?.optJSONObject(0)?:error("No route")
        val meters=first.optDouble("distance",Double.NaN)
        val seconds=first.optDouble("duration",Double.NaN)
        require(meters.isFinite()&&seconds.isFinite()&&
            meters>=0 && meters<=4_000_000 && seconds>=0 && seconds<=500_000)
        val coords=first.optJSONObject("geometry")?.optJSONArray("coordinates")
            ?:error("No route geometry")
        require(coords.length() in 2..12000)
        // Don't display unexpected or malicious geometry outside Turkey.
        for(i in 0 until coords.length()){
            val p=coords.optJSONArray(i)?:error("Coordinate invalid")
            if(p.length()<2||!valid(p.optDouble(1,Double.NaN),p.optDouble(0,Double.NaN)))
                error("Route point outside expected region")
        }
        return JSONObject()
            .put("coordinates",coords)
            .put("distanceKm",String.format(Locale("tr","TR"),"%.1f",meters/1000.0))
            .put("durationMin",String.format(Locale("tr","TR"),"%.0f",seconds/60.0))
    }

    private fun request(host:String,path:String,maxBytes:Int,timeoutMs:Int):String {
        require(host==GEOCODER || host==ROUTER)
        require(path.startsWith("/")&&!path.contains("\r")&&!path.contains("\n"))
        val url=URL("https://"+host+path)
        val connection=url.openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects=false
        connection.connectTimeout=timeoutMs
        connection.readTimeout=timeoutMs
        connection.setRequestProperty("User-Agent",USER_AGENT)
        connection.setRequestProperty("Accept","application/json")
        try{
            require(connection.responseCode==200){"Map lookup service unavailable"}
            val bytes=ByteArrayOutputStream()
            connection.inputStream.use{input->
                val buf=ByteArray(8192)
                while(true){
                    val n=input.read(buf)
                    if(n<0)break
                    require(bytes.size()+n<=maxBytes){"Response too large"}
                    bytes.write(buf,0,n)
                }
            }
            return bytes.toString(Charsets.UTF_8.name())
        }finally{connection.disconnect()}
    }
}
