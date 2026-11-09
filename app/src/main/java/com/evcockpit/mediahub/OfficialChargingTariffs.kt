package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.time.Duration
import java.time.Instant
import javax.net.ssl.HttpsURLConnection
import java.util.Locale

/**
 * User-initiated viewing of the project's verified official operator tariff
 * snapshot. This is NOT a socket-specific quoted charging price.
 * Never treats an old record as current simply because it was re-downloaded.
 */
object OfficialChargingTariffs {
    private const val SNAPSHOT =
        "https://raw.githubusercontent.com/AlpTimucin-cpu/ID4-Media-Hub/" +
        "feature/media-engine-migration/data/charging/operator_prices.json"
    private val sourceHosts = mapOf("trugo" to "www.trugo.com.tr",
        "zes" to "zes.net")

    data class VerifiedPrice(val operator: String, val band: String, val price: Double,
        val verifiedUtc: String, val officialUrl: String, val fresh: Boolean) {
        fun label(): String {
            val part = when(band) {
                "DC_MIN" -> "DC alt sınır"
                "DC_MAX" -> "DC üst sınır"
                "DC_1" -> "DC-1"
                "DC_2" -> "DC-2"
                else -> band
            }
            val amount=String.format(Locale("tr","TR"),"%.2f",price)
            return "$part: $amount TL/kWh" + if(fresh) " • doğrulandı" else " • ESKİ VERİ"
        }
    }

    fun parse(raw: String, now: Instant = Instant.now()): List<VerifiedPrice> {
        val root=JSONObject(raw)
        require(root.optInt("schema_version")==1)
        val operators=root.optJSONArray("operators")?:return emptyList()
        return buildList {
            for(i in 0 until minOf(operators.length(),25)){
                val op=operators.optJSONObject(i)?:continue
                val id=op.optString("operator_id")
                val host=sourceHosts[id]?:continue
                val name=op.optString("operator").take(60)
                val prices=op.optJSONArray("tariffs")?:continue
                for(j in 0 until minOf(prices.length(),12)){
                    val tariff=prices.optJSONObject(j)?:continue
                    val band=tariff.optString("connector_band")
                    if(band !in setOf("AC","DC","AC_MIN","AC_MAX","DC_MIN","DC_MAX","DC_1","DC_2"))continue
                    val price=tariff.optDouble("price_try_per_kwh",Double.NaN)
                    if(!price.isFinite() || price !in 1.0..100.0)continue
                    if(tariff.optString("price_scope")!="operator_published_baseline")continue
                    val link=tariff.optString("source_url")
                    val parsed=try { java.net.URI(link) }catch(_:Exception){continue}
                    if(parsed.scheme!="https" || parsed.host!=host)continue
                    val checked=tariff.optString("verified_at_utc")
                    val date=try { Instant.parse(checked) }catch(_:Exception){continue}
                    val age=Duration.between(date,now)
                    val fresh=!age.isNegative && age <= Duration.ofHours(36)
                    add(VerifiedPrice(name,band,price,checked,link,fresh))
                }
            }
        }
    }

    private fun fetch():String {
        val conn=URL(SNAPSHOT).openConnection() as HttpsURLConnection
        conn.instanceFollowRedirects=false
        conn.connectTimeout=9000
        conn.readTimeout=9000
        try {
            require(conn.responseCode==200) { "Snapshot unavailable" }
            val out=ByteArrayOutputStream()
            conn.inputStream.use { stream ->
                val b=ByteArray(4096)
                while(true){
                    val n=stream.read(b)
                    if(n<0)break
                    require(out.size()+n<=250_000) { "Oversized snapshot" }
                    out.write(b,0,n)
                }
            }
            return out.toString(Charsets.UTF_8.name())
        }finally{conn.disconnect()}
    }

    fun show(activity:Activity) {
        val waiting=AlertDialog.Builder(activity)
            .setTitle("Resmî operatör fiyatları")
            .setMessage("Trugo ve ZES'in yayımladığı tarifeler kontrol ediliyor…")
            .setCancelable(false).create()
        waiting.show()
        Thread{
            val records=try{parse(fetch())}catch(_:Exception){null}
            activity.runOnUiThread{
                waiting.dismiss()
                if(activity.isFinishing||activity.isDestroyed)return@runOnUiThread
                val summary=when {
                    records==null -> "Doğrulanmış fiyat verisine erişilemedi. " +
                        "Fiyat göstermek yerine resmî operatör kaynağını açabilirsin."
                    records.isEmpty() -> "Günlük doğrulanmış fiyat henüz yayımlanmadı. " +
                        "Tarife boş bırakıldı."
                    else -> buildString {
                        records.groupBy{it.operator}.forEach{(name,list)->
                            append(name).append("\n")
                            list.forEach{
                                append("• ").append(it.label()).append("\n")
                            }
                            append("Son kaynak doğrulaması: ")
                                .append(list.first().verifiedUtc.take(16)).append(" UTC\n\n")
                        }
                        append("Bu fiyatlar operatörün GENEL duyurusudur; ")
                        append("istasyondaki soket veya üye kampanya fiyatı değildir. ")
                        append("Eski veriyle şarj planlaması yapma.")
                    }
                }
                AlertDialog.Builder(activity).setTitle("Günlük resmî tarife takibi")
                    .setMessage(summary)
                    .setNeutralButton("Trugo resmî tarife"){_,_->
                        officialBrowser(activity,"https://www.trugo.com.tr/price")
                    }
                    .setNegativeButton("Kapat",null)
                    .setPositiveButton("ZES resmî tarife"){_,_->
                        officialBrowser(activity,"https://zes.net/tr/fiyatlandirma")
                    }
                    .show()
            }
        }.start()
    }

    fun officialBrowser(activity:Activity,url:String) {
        try { activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
        catch (_:Exception) { }
    }
}
