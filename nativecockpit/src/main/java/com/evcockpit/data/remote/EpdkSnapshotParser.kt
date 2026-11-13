package com.evcockpit.data.remote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import com.evcockpit.domain.usecase.EpdkProvenancePolicy
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import com.evcockpit.domain.model.ChargingStation
import com.evcockpit.domain.model.ChargingSocket
import com.evcockpit.domain.model.GeoCoordinate

/** Reject schema drift and corrupted/partial snapshots instead of showing false completeness. */
object EpdkSnapshotParser {
    const val MAX_GZIP_BYTES = 10_000_000
    private const val MAX_JSON_BYTES = 24_000_000
    private const val MIN_STATIONS = 5_000
    data class Parsed(
        val verifiedAtUtc: String,
        val stations: List<ChargingStation>,
        val sourceUrl: String,
        val allRowCount: Int,
        val publicRowCount: Int,
        val geocodedRowCount: Int
    )

    fun readBounded(stream: InputStream, maxBytes: Int): ByteArray {
        val result=ByteArrayOutputStream()
        val buffer=ByteArray(8192)
        while(true) {
            val n=stream.read(buffer)
            if(n<0)break
            if(result.size()+n>maxBytes) error("EPDK response exceeds the permitted size")
            result.write(buffer,0,n)
        }
        return result.toByteArray()
    }

    fun parseGzip(bytes: ByteArray): Parsed {
        require(bytes.size in 1..MAX_GZIP_BYTES) { "Invalid EPDK gzip size" }
        val json=GZIPInputStream(ByteArrayInputStream(bytes)).use { readBounded(it,MAX_JSON_BYTES) }
        return parseJson(String(json,Charsets.UTF_8))
    }

    fun parseJson(json: String): Parsed {
        val root=JSONObject(json)
        require(root.optInt("schema_version") == 1) { "Incompatible EPDK version" }
        require(!root.optBoolean("prices_included",true) &&
            !root.optBoolean("live_status_included",true)) { "Source contains unexpected fields" }
        val rawPreflight=root.getJSONArray("stations")
        require(rawPreflight.length() in MIN_STATIONS..100_000) {
            "EPDK station list is empty or incomplete"
        }
        val source=root.getString("source_url")
        require(source == EpdkProvenancePolicy.OFFICIAL_SOURCE) {
            "Unverified EPDK snapshot origin"
        }
        val verified=root.getString("verified_at_utc")
        val verifiedTime=Instant.parse(verified)
        val now=Instant.now()
        require(!verifiedTime.isAfter(now.plusSeconds(300))) { "EPDK export date is in the future" }
        // Full national publisher export; not a partial search/filter or a fabricated count.
        if(root.has("country")) require(root.optString("country")=="TR")
        if(root.has("complete")) require(root.optBoolean("complete"))
        val allCount=root.getInt("all_rows_count")
        val publicCount=root.getInt("public_rows_count")
        val geocodedCount=root.getInt("geocoded_public_rows_count")
        require(allCount in MIN_STATIONS..100_000 &&
            publicCount in MIN_STATIONS..allCount &&
            geocodedCount in MIN_STATIONS..publicCount) {
            "EPDK export count hierarchy is invalid"
        }
        val raw=root.getJSONArray("stations")
        require(raw.length() == geocodedCount) {
            "EPDK publisher count differs from payload"
        }
        val items=ArrayList<ChargingStation>(raw.length())
        val ids=HashSet<String>()
        for(i in 0 until raw.length()) {
            val record=raw.optJSONObject(i)
                ?: throw IllegalArgumentException("EPDK station is not an object")
            require(record.optBoolean("public",false) &&
                record.optString("service_kind")=="HALKA_ACIK" &&
                record.optString("source")=="EPDK") {
                "EPDK snapshot contains unverified or private station"
            }
            val id=record.optString("official_station_id").trim()
            require(id.isNotBlank() && ids.add(id)) {
                "EPDK station ID is missing or duplicated"
            }
            val lat=record.optDouble("lat",Double.NaN)
            val lon=record.optDouble("lon",Double.NaN)
            val coords=GeoCoordinate(lat,lon)
            require(coords.valid() && lat in 35.0..43.0 && lon in 25.0..46.0) {
                "EPDK public station coordinate is invalid"
            }
            val socketsJson=record.optJSONArray("sockets")
            require(socketsJson!=null && socketsJson.length()<=24) {
                "EPDK socket collection malformed or truncated"
            }
            require(record.optInt("socket_count",-1)==socketsJson.length()) {
                "EPDK socket count differs from actual sockets"
            }
            val sockets=buildList {
                if(socketsJson!=null)for(j in 0 until minOf(socketsJson.length(),24)) {
                    val socket=socketsJson.optJSONObject(j) ?: continue
                    add(ChargingSocket(
                        socket.optString("socket_id").take(48),
                        socket.optString("type").take(60),
                        socket.optString("kind").take(50),
                        socket.optString("power_raw").take(24)))
                }
            }
            items.add(ChargingStation(id,record.optString("name","Charging station").take(150),
                record.optString("operator").take(160),record.optString("address").take(260),
                coords,sockets,verified))
        }
        require(items.size == geocodedCount) {
            "EPDK normalized station list is incomplete"
        }
        return Parsed(verifiedTime.toString(),items,source,
            allCount, publicCount, geocodedCount)
    }
}
