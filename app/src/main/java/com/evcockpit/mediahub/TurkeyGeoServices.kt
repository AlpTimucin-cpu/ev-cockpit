package com.evcockpit.mediahub

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * One-off, user-initiated searches for a single-user beta, not a production
 * map service backend. Map tiles, geocoding and OSM station data are distinct.
 *
 * Never sends VIN, OBD data, private IPTV credentials or phone location.
 * No background polling. For multi-user distribution use a hosted geocoder,
 * routing provider and contracted charge-point data source instead.
 */
object TurkeyGeoServices {
    const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
    const val EPDK_PUBLIC = "https://www.epdk.gov.tr/Detay/Icerik/1-3428/serbest-erisim-platformu--sarj%40tr"
    const val EPDK_STATIONS = "https://lisans.epdk.gov.tr/epvys-web/faces/pages/lisans/elektrikSarjAgiIsletmeci/sarjIstasyonuOzetSorgula.xhtml"
    private const val USER_AGENT =
        "EV-Cockpit/0.60 (personal beta; https://github.com/AlpTimucin-cpu/ID4-Media-Hub)"
    private const val NOMINATIM = "https://nominatim.openstreetmap.org"
    private const val OVERPASS = "https://overpass-api.de/api/interpreter"
    private var lastGeocodingRequest = 0L
    private var lastStationRequest = 0L

    data class Place(val title: String, val lat: Double, val lon: Double) {
        fun coordinates() = "%.5f,%.5f".format(java.util.Locale.US,lat,lon)
    }

    data class Station(val osmId: String, val title: String,
        val lat: Double, val lon: Double, val operator: String,
        val ccs: String, val type2: String, val rawPower: String,
        val source: String = "OpenStreetMap") {
        fun detail(): String = buildString {
            append(title)
            if(operator.isNotBlank()) append("\nİşletmeci: ").append(operator)
            if(ccs.isNotBlank()) append("\nCCS: ").append(ccs)
            if(type2.isNotBlank()) append("\nType 2: ").append(type2)
            if(rawPower.isNotBlank()) append("\nEtiketlenen güç: ").append(rawPower)
            append("\nKaynak: ").append(source).append(". Canlı doluluk/fiyat doğrulanmaz.")
        }
        fun asPlace()=Place(title,lat,lon)
    }

    data class Bounds(val south: Double,val west: Double,
                      val north: Double,val east: Double) {
        fun isSmallEnough(): Boolean = south < north && west < east &&
            (north-south) <= 0.50 && (east-west) <= 0.65 &&
            south >= 35.0 && north <= 43.0 && west >= 25.0 && east <= 46.0
    }

    /** Public Nominatim terms prohibit autocomplete; calls only on button tap. */
    @Synchronized
    fun searchAddress(query: String): List<Place> {
        val term=query.trim().take(160)
        require(term.length >= 3) { "En az üç karakter gir." }
        val now=System.currentTimeMillis()
        require(now - lastGeocodingRequest >= 5000L) {
            "Adres araması için birkaç saniye bekle."
        }
        lastGeocodingRequest=now
        val target=NOMINATIM+"/search?format=jsonv2&limit=8&countrycodes=tr&addressdetails=1&q="+
            URLEncoder.encode(term,"UTF-8")
        return parsePlaces(readHttps(target,450_000))
    }

    /** Visible, user-selected small map viewport only; no nationwide scraping. */
    @Synchronized
    fun nearbyStations(bounds: Bounds): List<Station> {
        require(bounds.isSmallEnough()) {
            "Şarj istasyonları için haritayı ilçe/semt düzeyine yakınlaştır."
        }
        val now=System.currentTimeMillis()
        require(now - lastStationRequest >= 45_000L) {
            "Topluluk verisi için 45 saniye sonra tekrar dene."
        }
        lastStationRequest=now
        fun v(x: Double)=String.format(java.util.Locale.US,"%.5f",x)
        val box=listOf(bounds.south,bounds.west,bounds.north,bounds.east)
            .joinToString(","){v(it)}
        val q="[out:json][timeout:17];("+
            "node[amenity=charging_station]($box);"+
            "way[amenity=charging_station]($box);"+
            "relation[amenity=charging_station]($box);"+
            ");out center 180;"
        val url=OVERPASS+"?data="+URLEncoder.encode(q,"UTF-8")
        return parseStations(readHttps(url,900_000))
    }

    fun parsePlaces(json: String): List<Place> {
        val array=JSONArray(json)
        return buildList {
            for(i in 0 until minOf(array.length(),8)){
                val o=array.optJSONObject(i)?:continue
                val lat=o.optString("lat").toDoubleOrNull()?:continue
                val lon=o.optString("lon").toDoubleOrNull()?:continue
                if(!turkey(lat,lon))continue
                add(Place(o.optString("display_name").take(170),lat,lon))
            }
        }
    }

    fun parseStations(json: String):List<Station> {
        val array=JSONObject(json).optJSONArray("elements")?:return emptyList()
        val ids=HashSet<String>()
        return buildList {
            for(i in 0 until minOf(array.length(),350)){
                val o=array.optJSONObject(i)?:continue
                val t=o.optJSONObject("tags")?:continue
                val center=o.optJSONObject("center")
                val lat=if(o.has("lat"))o.optDouble("lat") else center?.optDouble("lat")?:Double.NaN
                val lon=if(o.has("lon"))o.optDouble("lon") else center?.optDouble("lon")?:Double.NaN
                if(!turkey(lat,lon))continue
                val id=o.optString("type")+":"+o.optLong("id")
                if(!ids.add(id))continue
                val name=t.optString("name").trim().ifBlank {
                    t.optString("operator").trim().ifBlank { "Şarj istasyonu" }
                }.take(110)
                add(Station(id,name,lat,lon,
                    t.optString("operator").take(90),
                    t.optString("socket:ccs").take(35),
                    t.optString("socket:type2").take(35),
                    t.optString("socket:ccs:output").ifBlank {
                        t.optString("capacity").take(35)
                    }.take(40)))
                if(size>=180)break
            }
        }
    }

    private fun turkey(lat:Double,lon:Double): Boolean =
        lat.isFinite() && lon.isFinite() &&
        lat in 35.0..43.0 && lon in 25.0..46.0

    private fun readHttps(address:String,cap:Int):String {
        val uri=java.net.URI(address)
        require(uri.scheme=="https" &&
            uri.host in setOf("nominatim.openstreetmap.org","overpass-api.de"))
        val connection=URL(address).openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects=false
        connection.connectTimeout=12000
        connection.readTimeout=22000
        connection.setRequestProperty("User-Agent",USER_AGENT)
        connection.setRequestProperty("Accept","application/json")
        try {
            require(connection.responseCode==200){"HTTP "+connection.responseCode}
            val output=ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer=ByteArray(8192)
                while(true){
                    val n=input.read(buffer)
                    if(n<0)break
                    require(output.size()+n<=cap){"Response too large"}
                    output.write(buffer,0,n)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally { connection.disconnect() }
    }
}
