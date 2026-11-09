package com.evcockpit.mediahub

import android.content.Context
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * Optional Open Charge Map enhancement. Needs user's own free OCM API key.
 * Key stays on-device with Android Keystore encryption. No silent API calls.
 * API key is sent only as HTTPS X-API-Key header, never as a URL parameter.
 */
object OpenChargeMapSources {
    private const val PREF = "evcockpit_charge_providers"
    private const val API_KEY = "ocm_key"
    private const val URI = "https://api.openchargemap.io/v3/poi/"
    private var lastRequestTime=0L

    fun hasKey(context:Context):Boolean =
        try{key(context).isNotBlank()}catch(_:Exception){false}

    fun saveKey(context:Context,newKey:String):Boolean {
        val value=newKey.trim()
        if(value.length !in 10..160 || value.any {it.isWhitespace()})return false
        return try{
            val encrypted=PrivateUrlCodec.encode(value)
            context.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit()
                .putString(API_KEY,encrypted).commit()
        }catch(_:Exception){false}
    }

    fun clearKey(context:Context):Boolean =
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .edit().remove(API_KEY).commit()

    private fun key(context:Context):String =
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .getString(API_KEY,"").orEmpty().let { PrivateUrlCodec.decode(it) }

    @Synchronized
    fun nearby(context:Context,lat:Double,lon:Double):List<TurkeyGeoServices.Station> {
        require(lat in 35.0..43.0 && lon in 25.0..46.0)
        val token=key(context)
        require(token.length in 10..160){"An Open Charge Map key is required"}
        val now=System.currentTimeMillis()
        require(now-lastRequestTime>=60_000L){"Wait for provider cooldown"}
        lastRequestTime=now
        val center="%s&longitude=%s".format(Locale.US,lat,lon)
        val url=URI+"?output=json&countrycode=TR&latitude="+center+
            "&distance=30&distanceunit=KM&maxresults=120&compact=false&verbose=false"
        val conn=URL(url).openConnection() as HttpsURLConnection
        conn.instanceFollowRedirects=false
        conn.connectTimeout=12000
        conn.readTimeout=15000
        conn.setRequestProperty("X-API-Key",token)
        conn.setRequestProperty("User-Agent",
            "EV-Cockpit/0.60 (https://github.com/AlpTimucin-cpu/ID4-Media-Hub)")
        conn.setRequestProperty("Accept","application/json")
        try{
            require(conn.responseCode==200){"OpenChargeMap response is not OK"}
            val data=ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buffer=ByteArray(8192)
                while(true){
                    val count=input.read(buffer)
                    if(count<0)break
                    require(data.size()+count<=1_600_000){"OCM response too large"}
                    data.write(buffer,0,count)
                }
            }
            return parse(data.toString(Charsets.UTF_8.name()))
        }finally{conn.disconnect()}
    }

    fun parse(json:String):List<TurkeyGeoServices.Station>{
        val array=JSONArray(json)
        return buildList{
            for(i in 0 until minOf(array.length(),150)){
                val o=array.optJSONObject(i)?:continue
                val address=o.optJSONObject("AddressInfo")?:continue
                val lat=address.optDouble("Latitude",Double.NaN)
                val lon=address.optDouble("Longitude",Double.NaN)
                if(!lat.isFinite() || !lon.isFinite() ||
                    lat !in 35.0..43.0 || lon !in 25.0..46.0)continue
                val links=o.optJSONArray("Connections")
                val ccs=ArrayList<String>()
                val type2=ArrayList<String>()
                var maxKw=0.0
                if(links!=null)for(j in 0 until minOf(links.length(),50)){
                    val link=links.optJSONObject(j)?:continue
                    val type=link.optJSONObject("ConnectionType")?.optString("Title").orEmpty()
                    if(type.contains("CCS",true) || type.contains("Combo",true))ccs.add(type)
                    if(type.contains("Type 2",true))type2.add(type)
                    val power=link.optDouble("PowerKW",0.0)
                    if(power.isFinite() && power in 0.0..800.0)maxKw=maxOf(maxKw,power)
                }
                val id=o.optLong("ID")
                val title=address.optString("Title").take(110).ifBlank{"Şarj istasyonu"}
                val operator=o.optJSONObject("OperatorInfo")?.optString("Title").orEmpty()
                add(TurkeyGeoServices.Station(
                    "ocm:$id",title,lat,lon,operator.take(90),
                    ccs.distinct().joinToString().take(35),
                    type2.distinct().joinToString().take(35),
                    if(maxKw>0)String.format(Locale.US,"%.0f kW",maxKw)else "",
                    source="Open Charge Map"))
            }
        }
    }

    fun merge(osm:List<TurkeyGeoServices.Station>,
              ocm:List<TurkeyGeoServices.Station>):List<TurkeyGeoServices.Station>{
        val all=ArrayList<TurkeyGeoServices.Station>(osm)
        for(item in ocm){
            val duplicate=all.any {
                val near=kotlin.math.abs(it.lat-item.lat)<0.00028 &&
                    kotlin.math.abs(it.lon-item.lon)<0.00038
                near && (it.operator.equals(item.operator,true) ||
                    it.operator.isBlank() || item.operator.isBlank() ||
                    it.title.equals(item.title,true))
            }
            if(!duplicate)all.add(item)
        }
        return all.take(250)
    }
}
