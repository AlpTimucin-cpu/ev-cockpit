package com.evcockpit.mediahub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

/**
 * EPDK's *official* public station snapshot built by our scheduled importer.
 * Offline-compatible after first verified download. Does not contain prices
 * or live socket availability. No phone GPS / VIN / log data is transferred.
 */
object EpdkPublicStations {
    private const val PUBLIC_SNAPSHOT =
        "https://raw.githubusercontent.com/AlpTimucin-cpu/ID4-Media-Hub/" +
        "feature/media-engine-migration/data/charging/epdk_public_stations.json.gz"
    private const val MAX_COMPRESSED_BYTES = 10_000_000
    private const val MAX_UNCOMPRESSED_BYTES = 18_000_000
    private const val FILE_NAME = "evcockpit_epdk_official_public_stations.json.gz"
    private const val REFRESH_MS = 6L * 60 * 60 * 1000

    data class Snapshot(val verifiedUtc: String,
        val allCount: Int, val publicCount: Int, val geocodedCount: Int,
        val stations: List<Entry>) {
        fun fresh(at: Instant = Instant.now()): Boolean {
            val timestamp=try{Instant.parse(verifiedUtc)}catch(_:Exception){return false}
            val elapsed=Duration.between(timestamp,at)
            return !elapsed.isNegative && elapsed < Duration.ofHours(72)
        }
    }
    data class Entry(
        val stationId: String, val name: String, val operator: String,
        val operatorLicense: String,
        val address: String, val lat: Double, val lon: Double,
        val sockets: List<Socket>,
        val brand: String = "",
        val stationOperator: String = "",
        val greenStationRaw: String = "",
        val distributionCompany: String = "",
        val distributionLicense: String = "",
        val distributionApprovalDocument: String = ""
    ) {
        fun asPoint():TurkeyGeoServices.Station {
            val ccs=sockets.count{
                it.type.contains("CCS",true) || it.type.contains("COMBO",true)
            }
            val type2=sockets.count{
                it.type.contains("TYPE2",true) || it.type.contains("TYPE 2",true) ||
                    it.type.contains("TIP 2",true)
            }
            val power=sockets.mapNotNull{
                it.rawPower.replace(',','.').toDoubleOrNull()
            }.filter{it.isFinite() && it in 1.0..1000.0}.maxOrNull()
            // Socket power unit is not guaranteed by EPDK schema;
            // do not display guessed "kW" when unit is undocumented.
            return TurkeyGeoServices.Station(
                "epdk:$stationId",name,lat,lon,operator,
                if(ccs>0) "$ccs soket" else "",
                if(type2>0) "$type2 soket" else "",
                power?.let{"Güç alanı: $it (birim doğrulanmadı)"}.orEmpty(),
                source="EPDK • resmî halka açık"
            )
        }
    }
    data class Socket(val id:String, val type:String,
        val kind:String, val rawPower:String)

    fun parse(content:String):Snapshot {
        val root=JSONObject(content)
        require(root.optInt("schema_version")==1)
        require(root.optString("source_url")==
            "https://apigateway.epdk.gov.tr/sarjIstasyonlari")
        require(!root.optBoolean("prices_included",true) &&
            !root.optBoolean("live_status_included",true))
        val all=root.optInt("all_rows_count")
        val public=root.optInt("public_rows_count")
        val geocoded=root.optInt("geocoded_public_rows_count")
        require(all >= 10000 && public >= 5000 && geocoded >= public * 0.8)
        val arr=root.optJSONArray("stations") ?:error("Missing stations")
        require(arr.length()==geocoded && arr.length()<=100000)
        val seen=HashSet<String>()
        val entries=ArrayList<Entry>(arr.length())
        for(i in 0 until arr.length()) {
            val o=arr.optJSONObject(i)?:continue
            if(!o.optBoolean("public",false) || o.optString("source")!="EPDK")continue
            val lat=o.optDouble("lat",Double.NaN)
            val lon=o.optDouble("lon",Double.NaN)
            val stationId=o.optString("official_station_id")
            if(!lat.isFinite() || !lon.isFinite() ||
                lat !in 35.0..43.0 || lon !in 25.0..46.0 ||
                stationId.isBlank() || !seen.add(stationId)) continue
            val rawSockets=o.optJSONArray("sockets") ?:JSONArray()
            val sockets=buildList {
                for(j in 0 until minOf(24,rawSockets.length())) {
                    val s=rawSockets.optJSONObject(j)?:continue
                    add(Socket(
                        s.optString("socket_id").take(48),
                        s.optString("type").take(60),
                        s.optString("kind").take(50),
                        s.optString("power_raw").take(24)))
                }
            }
            entries.add(Entry(stationId,
                o.optString("name").take(150),
                o.optString("operator").take(160),
                o.optString("operator_license").take(60),
                o.optString("address").take(260),
                lat,lon,sockets,
                brand=o.optString("brand").take(130),
                stationOperator=o.optString("station_operator").take(160),
                greenStationRaw=o.optString("green_station_raw").take(30),
                distributionCompany=o.optString("distribution_company").take(160),
                distributionLicense=o.optString("distribution_license").take(80),
                distributionApprovalDocument=o.optString("distribution_approval_doc").take(90)))
        }
        require(entries.size >= geocoded * 0.90){"Insufficient valid EPDK rows"}
        return Snapshot(root.optString("verified_at_utc"),
            all,public,geocoded,entries)
    }

    fun nearby(context:Context, bounds:TurkeyGeoServices.Bounds,
        limit:Int=200): Pair<List<TurkeyGeoServices.Station>, Snapshot> {
        require(bounds.isSmallEnough())
        val snapshot=load(context)
        require(snapshot.fresh()){"Official station data has expired"}
        val matches=snapshot.stations.asSequence()
            .filter{it.lat in bounds.south..bounds.north &&
                    it.lon in bounds.west..bounds.east}
            .take(limit.coerceIn(1,200))
            .map { it.asPoint() }.toList()
        return matches to snapshot
    }

    @Synchronized
    fun load(context:Context):Snapshot {
        val cache=File(context.cacheDir,FILE_NAME)
        if(cache.isFile && System.currentTimeMillis() - cache.lastModified() < REFRESH_MS){
            try{return parse(decode(cache.readBytes()))}catch(_:Exception){}
        }
        try {
            val data=fetch()
            val snapshot=parse(decode(data))
            require(snapshot.fresh()){"EPDK snapshot outdated"}
            cache.writeBytes(data)
            return snapshot
        }catch(ex:Exception){
            if(cache.isFile){
                val stale=parse(decode(cache.readBytes()))
                if(stale.fresh())return stale
            }
            throw ex
        }
    }

    private fun fetch():ByteArray {
        val conn=URL(PUBLIC_SNAPSHOT).openConnection() as HttpsURLConnection
        conn.instanceFollowRedirects=false
        conn.connectTimeout=13000
        conn.readTimeout=17000
        conn.setRequestProperty("Accept-Encoding","identity")
        try {
            require(conn.responseCode==200){"Public EPDK snapshot not available"}
            val out=ByteArrayOutputStream()
            conn.inputStream.use{input->
                val buffer=ByteArray(8192)
                while(true){
                    val n=input.read(buffer)
                    if(n<0)break
                    require(out.size()+n<=MAX_COMPRESSED_BYTES)
                    out.write(buffer,0,n)
                }
            }
            return out.toByteArray()
        }finally{conn.disconnect()}
    }

    private fun decode(bytes:ByteArray):String {
        require(bytes.size in 100..MAX_COMPRESSED_BYTES)
        val out=ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(bytes)).use{input->
            val buffer=ByteArray(8192)
            while(true){
                val n=input.read(buffer)
                if(n<0)break
                require(out.size()+n<=MAX_UNCOMPRESSED_BYTES)
                out.write(buffer,0,n)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
