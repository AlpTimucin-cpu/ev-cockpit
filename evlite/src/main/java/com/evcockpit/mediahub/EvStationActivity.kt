package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/**
 * No embedded GPU map. All official EPDK HTTP, gzip, JSON parsing, searches
 * run off the Android main/UI thread, with at most 40 row views per render.
 */
class EvStationActivity:Activity() {
    private lateinit var progress:TextView
    private lateinit var list:LinearLayout
    private lateinit var query:EditText
    private lateinit var operator:EditText
    private lateinit var socket:Spinner
    @Volatile private var snapshot:EpdkPublicStations.Snapshot?=null
    private val connectors=ChargingStationDiscovery.Connector.entries.toTypedArray()
    private var searchNumber=0
    private val sea=java.util.concurrent.Executors.newSingleThreadExecutor()
    private val dark=0xff081925.toInt()
    private val turquoise=0xff70dfc9.toInt()

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=dark
        window.navigationBarColor=dark
        val scroll=ScrollView(this).apply{setBackgroundColor(dark)}
        val box=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(15),dp(26),dp(15),dp(28))
        }
        scroll.addView(box)
        setContentView(scroll)
        box.addView(label("‹ Geri",16,true).apply{setOnClickListener{finish()}})
        box.addView(label("EPDK ŞARJ NOKTALARI",22,true))
        box.addView(label(
            "Resmî halka açık istasyonlar • CCS, Type 2 ve CHAdeMO soketleri",13,false))
        box.addView(label("Harita ve istasyon rotası EV Cockpit içinde açılır.",12,false))
        query=input("Şehir / ilçe / adres / istasyon adı")
        operator=input("Operatör (isteğe bağlı)")
        box.addView(query)
        box.addView(operator)
        socket=Spinner(this).apply{
            val names= arrayOf("Tüm soketler","CCS (DC)","Type 2 (AC)",
                "CHAdeMO","Diğer soket")
            adapter=ArrayAdapter(this@EvStationActivity,
                android.R.layout.simple_spinner_dropdown_item,names)
        }
        box.addView(socket)
        box.addView(button("🗺 Türkiye Haritasını Aç"){
            startActivity(Intent(this,EmbeddedMapActivity::class.java))
        })
        box.addView(button("⚡ Resmî istasyonları yükle / ara"){
            fetchAndSearch()
        })
        progress=label("Hazır. Aramak için düğmeye dokun.",12,false)
        box.addView(progress)
        list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        box.addView(list)
        box.addView(label("Resmî kayıtlar kesin fiyat veya canlı doluluk " +
            "içermiyor. Operatörden doğrulanmayan bilgiler bilinmiyor olarak gösterilir.",12,false))
    }

    private fun fetchAndSearch(){
        val id=++searchNumber
        val term=query.text.toString().trim().take(100)
        val op=operator.text.toString().trim().take(100)
        val filter=ChargingStationDiscovery.Filter(operator=op,text=term,
            connector=connectors[socket.selectedItemPosition.coerceIn(0,connectors.lastIndex)])
        progress.text="EPDK kaydı okunuyor ve arama hazırlanıyor…"
        list.removeAllViews()
        sea.execute {
            val data=try{
                val snap=snapshot?.takeIf{it.fresh()}?:
                    EpdkPublicStations.load(applicationContext)
                if(!snap.fresh())error("EPDK verisi güncelliğini yitirdi")
                snapshot=snap
                val found=ChargingStationDiscovery.filter(snap.stations,filter)
                Triple(snap,found.size,found.take(40))
            }catch(_:Exception){null}
            runOnUiThread {
                if(isFinishing||isDestroyed||id!=searchNumber)return@runOnUiThread
                if(data==null){
                    progress.text="Resmî EPDK veri dosyasına ulaşılamadı. " +
                        "İnternet bağlantısını kontrol et; eski veriler güncel gibi kullanılmaz."
                    return@runOnUiThread
                }
                val snap=data.first
                progress.text="EPDK • "+snap.publicCount+" halka açık kayıt • "+
                    data.second+" eşleşme. İlk "+data.third.size+
                    " sonuç gösteriliyor.\nVeri tarihi: "+snap.verifiedUtc.take(16)+" UTC"
                if(data.third.isEmpty()) {
                    list.addView(label("Bu filtreyle istasyon bulunamadı.",14,false))
                }
                data.third.forEach { entry ->
                    val button=button(entry.name.take(50)+"  •  "+entry.operator.take(35)+
                        "\n"+entry.address.take(95)) {
                        openStation(entry)
                    }
                    list.addView(button)
                }
            }
        }
    }
    private fun openStation(entry:EpdkPublicStations.Entry){
        val text=ChargingStationDiscovery.socketDetails(entry)
        AlertDialog.Builder(this).setTitle("⚡ "+entry.name)
            .setMessage(text.take(5000))
            .setNegativeButton("Kapat",null)
            .setPositiveButton("🗺 Haritada aç"){_,_->
                EmbeddedMapActivity.open(this,entry.lat,entry.lon,entry.name)
            }.show()
    }
    private fun input(hint:String)=EditText(this).apply{
        this.hint=hint
        setTextColor(Color.WHITE)
        setHintTextColor(0xffa7bfce.toInt())
        textSize=14f
        setSingleLine(true)
        setPadding(dp(12),dp(6),dp(12),dp(6))
    }
    private fun button(text:String,action:()->Unit):Button=Button(this).apply{
        this.text=text;textSize=13f
        isAllCaps=false
        setTextColor(Color.WHITE)
        background=GradientDrawable().apply {
            setColor(0xff23546a.toInt())
            cornerRadius=dp(12).toFloat()
        }
        setOnClickListener{action()}
        val lp=LinearLayout.LayoutParams(-1,-2)
        lp.bottomMargin=dp(8)
        layoutParams=lp
        minimumHeight=dp(54)
    }
    private fun label(v:String,size:Int,bold:Boolean)=TextView(this).apply{
        text=v;textSize=size.toFloat()
        setTextColor(if(bold)turquoise else 0xffbdcfda.toInt())
        if(bold)setTypeface(typeface,Typeface.BOLD)
        setPadding(dp(5),dp(11),dp(5),dp(11))
    }
    private fun dp(x:Int)=(x*resources.displayMetrics.density).toInt()
    private fun message(title:String,detail:String){
        AlertDialog.Builder(this).setTitle(title).setMessage(detail)
            .setPositiveButton("Tamam",null).show()
    }
    override fun onDestroy(){
        searchNumber++
        sea.shutdownNow()
        super.onDestroy()
    }
}
