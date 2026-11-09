package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.InputType
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tesla-inspired OFFLINE energy and journey dashboard for electric vehicle.
 * Inputs are entered manually. No steering, charging, HVAC, door, OBD,
 * remote control, GPS tracking or background collection is performed.
 */
class EvGarageActivity : Activity() {
    private val bg = 0xff09151f.toInt()
    private val surface = 0xff172b38.toInt()
    private val accent = 0xff75dfc6.toInt()
    private val neutral = 0xffb2c9d5.toInt()
    private val rows = LinkedHashMap<String, EditText>()
    private lateinit var summary: LinearLayout
    private lateinit var recent: LinearLayout
    private lateinit var gauge: BatteryBar
    private val tr = Locale("tr", "TR")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=bg
        window.navigationBarColor=bg
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.setOnApplyWindowInsetsListener { view,insets ->
            if(android.os.Build.VERSION.SDK_INT >= 30) {
                val bar=insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0,bar.top,0,bar.bottom)
            }
            insets
        }
        setContentView(root)
        val header=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(13),dp(11),dp(13),dp(10))
        }
        header.addView(button("‹"){ finish() },LinearLayout.LayoutParams(dp(49),dp(48)))
        header.addView(text("EV Cockpit ENERJİ MERKEZİ",21,true),LinearLayout.LayoutParams(0,-2,1f))
        root.addView(header)
        val scroll=ScrollView(this).apply { isFillViewport=true }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(3),dp(16),dp(25))
        }
        scroll.addView(content)

        content.addView(text("Tesla'dan esinlenen EV kokpiti",14,false,neutral))
        content.addView(text("2024 EV Cockpit Pure • 125 kW / 170 PS",15,true))
        content.addView(text("Verileri sen giriyorsun. Bu ekran araçtan canlı veri çekmez veya araca komut göndermez.",12,false,neutral))
        gauge=BatteryBar()
        content.addView(gauge,LinearLayout.LayoutParams(-1,dp(38)).apply{
            topMargin=dp(13);bottomMargin=dp(14)
        })

        val profile=EvGarageStore.profile(this)
        content.addView(section("BATARYA VE SÜRÜŞ"))
        content.addView(field("Batarya kullanılabilir kapasite • kWh", "capacity",profile.usableKwh))
        content.addView(field("Mevcut şarj • %", "soc",profile.socPercent))
        content.addView(field("Hedef şarj • %", "target",profile.targetPercent))
        content.addView(field("Güvenli varış rezervi • %", "reserve",profile.reservePercent))
        content.addView(field("Gerçek tüketim • kWh / 100 km", "cons",profile.consumptionKwhPer100Km))
        content.addView(field("Planlanan yol • km", "distance",profile.distanceKm))
        content.addView(section("ŞARJ ÜCRETİ VE SÜRESİ"))
        content.addView(field("Ortalama bataryaya aktarılan güç • kW", "power",profile.averageChargingKw))
        content.addView(field("Şarj verimi • %", "eff",profile.gridEfficiency*100.0))
        content.addView(field("Tarife • TL / kWh", "price",profile.priceTlPerKwh))
        content.addView(text("Tarife örnek değerdir; gerçek istasyon fiyatını gir. Özellikle DC'de güç şarj boyunca değişir.",12,false,neutral))

        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        row.addView(button("Hesapla"){ calculate(true) },LinearLayout.LayoutParams(0,dp(54),1f))
        row.addView(button("Profili kaydet"){ saveProfile() },LinearLayout.LayoutParams(0,dp(54),1f)
            .apply { leftMargin=dp(7) })
        content.addView(row)

        summary=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        content.addView(summary)
        content.addView(section("ŞARJ HARCAMA DEFTERİ"))
        val sessionRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        sessionRow.addView(button("＋ Gerçek şarj kaydet"){ addSession() },
            LinearLayout.LayoutParams(0,dp(54),1f))
        sessionRow.addView(button("Paylaş"){ shareReport() },LinearLayout.LayoutParams(0,dp(54),.56f)
            .apply{leftMargin=dp(7)})
        content.addView(sessionRow)
        recent=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        content.addView(recent)
        content.addView(button("Şarj kayıtlarını temizle"){ confirmClear() })
        content.addView(section("BATARYA / OBD ÖLÇÜM DEFTERİ"))
        content.addView(text("Car Scanner ile gördüğün SoC, kilometre ve teknik notları kaydet. " +
            "Tek bir ölçümden kesin SoH sonucu çıkarılmaz.",12,false,neutral))
        content.addView(button("＋ Manuel batarya kaydı"){ addBatteryNote() })
        content.addView(button("📥 Car Scanner CSV dosyası içe aktar") {
            try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type="*/*"
                }, PICK_CARSCANNER_CSV)
            } catch (_: Exception) {
                alert("Dosya seçici açılamadı", "Android dosya seçimini başlatamadı.")
            }
        })
        content.addView(button("Batarya geçmişini göster"){ showBatteryNotes() })
        content.addView(section("PARK & SEYAHAT"))
        content.addView(button("🗺 Türkiye Şarj Haritasını Aç") {
            startActivity(Intent(this, TurkeyNavigationActivity::class.java))
        })
        content.addView(button("Harici haritada şarj istasyonu ara"){openStations()})
        content.addView(text("Park notlarını ve şarj kayıtlarını yalnızca telefonda tutar. " +
            "Tesla Sentry, uzaktan klima, kapı kontrolü ve otomatik batarya ön koşullandırması " +
            "burada varmış gibi gösterilmez.",12,false,neutral))
        calculate(false)
        showSessions()
    }

    private fun calculate(notify: Boolean) {
        val p=readProfile() ?: run {
            if(notify)alert("Girişleri kontrol et",
                "SoC 0-100, verim 60-100, kapasite 10-200 kWh ve diğer alanlar geçerli aralıkta olmalı.")
            return
        }
        val c=EvPlanner.charge(p)
        val t=EvPlanner.trip(p)
        gauge.soc=p.socPercent.toFloat()
        gauge.target=p.targetPercent.toFloat()
        gauge.invalidate()
        summary.removeAllViews()
        summary.addView(section("TAHMİNİ SONUÇLAR"))
        summary.addView(info("Bataryaya eklenecek enerji",num(c.batteryKwh)+" kWh"))
        summary.addView(info("Şebekeden çekilecek enerji",num(c.gridKwh)+" kWh"))
        summary.addView(info("Tahmini şarj maliyeti",num(c.costTl)+" TL"))
        summary.addView(info("İdeal süre (girilen ortalama güçle)",num(c.idealHoursAtChosenAverageKw*60)+" dk"))
        summary.addView(info("Mevcut SoC ile teorik menzil",num(t.currentRangeKm)+" km"))
        summary.addView(info("Rezerve kadar kullanılabilir menzil",num(t.usableRangeUntilReserveKm)+" km"))
        summary.addView(info("Şarjsız varış SoC tahmini",num(t.arrivalPercentWithoutCharge)+" %"))
        summary.addView(info("Varış rezervi için ek enerji",
            if(t.canArriveWithReserve)"Gerekmiyor" else num(t.batteryKwhRequiredToArriveWithReserve)+" kWh"))
        summary.addView(text("Hesap doğrusal enerji modelidir. Trafik, sıcaklık, eğim, hız, " +
            "rüzgâr, batarya sıcaklığı, şarj eğrisi ve klima gerçek sonucu değiştirebilir. " +
            "Şarj süresi garanti veya navigasyon hesabı değildir.",12,false,neutral))
        if(notify)toast("Tahminler güncellendi.")
    }

    private fun readProfile(): EvPlanner.Profile? {
        fun n(key:String): Double? =
            rows[key]?.text.toString().trim().replace(',','.').toDoubleOrNull()
        return try {
            val p=EvPlanner.Profile(
                usableKwh=n("capacity") ?: return null,
                socPercent=n("soc") ?: return null,
                targetPercent=n("target") ?: return null,
                reservePercent=n("reserve") ?: return null,
                consumptionKwhPer100Km=n("cons") ?: return null,
                distanceKm=n("distance") ?: return null,
                averageChargingKw=n("power") ?: return null,
                gridEfficiency=(n("eff") ?: return null)/100.0,
                priceTlPerKwh=n("price") ?: return null)
            if(EvPlanner.valid(p))p else null
        } catch (_:Exception){null}
    }

    private fun saveProfile() {
        val p=readProfile()
        if(p==null || !EvGarageStore.saveProfile(this,p)) {
            alert("Profil kaydedilmedi","Sayısal değerleri kontrol et.")
        } else toast("EV Cockpit enerji profili bu telefona kaydedildi.")
    }

    private fun addSession() {
        val form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(8))}
        val place=input("Şarj yeri / istasyon adı")
        val kwh=input("Faturalanan enerji (kWh)",numeric=true)
        val cost=input("Gerçek ödeme (TL)",numeric=true)
        listOf(place,kwh,cost).forEach{form.addView(it)}
        AlertDialog.Builder(this).setTitle("Gerçek şarj seansı kaydet")
            .setMessage("Tahmini değil, istasyon fişindeki enerji ve tutarı gir.")
            .setView(form).setNegativeButton("Vazgeç",null)
            .setPositiveButton("Kaydet"){_,_->
                val energy=kwh.value()
                val tl=cost.value()
                if(energy==null || tl==null ||
                    !EvGarageStore.addSession(this,ChargeSession(System.currentTimeMillis(),
                        place.text.toString().trim().take(80),energy,tl))) {
                    alert("Kayıt başarısız","Enerji 0-1000 kWh, ödeme 0-1.000.000 TL olmalı.")
                } else { showSessions();toast("Şarj seansı kaydedildi.") }
            }.show()
    }

    private fun addBatteryNote() {
        val form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(8))}
        val soc=input("BMS / gösterge SoC %",numeric=true).apply{
            setText(rows["soc"]?.text.toString())
        }
        val odo=input("Kilometre sayacı • km",numeric=true)
        val note=input("Car Scanner gözlemi / batarya sıcaklığı / şarj gücü")
        listOf(soc,odo,note).forEach{form.addView(it)}
        AlertDialog.Builder(this).setTitle("Batarya ölçüm notu")
            .setView(form).setNegativeButton("Vazgeç",null)
            .setPositiveButton("Kaydet"){_,_->
                val x=soc.value();val km=odo.value()
                if(x==null||km==null || !EvGarageStore.addNote(this,
                    EvGarageStore.BatteryNote(System.currentTimeMillis(),x,km,note.text.toString()))) {
                    alert("Kayıt başarısız","Geçerli SoC ve kilometre gir.")
                } else toast("Batarya gözlemi kaydedildi.")
            }.show()
    }

    @Deprecated("Android framework SAF callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=PICK_CARSCANNER_CSV || resultCode!=RESULT_OK)return
        val uri=data?.data?:return
        Thread {
            val reading=try {
                val bytes=contentResolver.openInputStream(uri)?.use { stream ->
                    val out=java.io.ByteArrayOutputStream()
                    val buffer=ByteArray(8192)
                    while(true){
                        val count=stream.read(buffer)
                        if(count<0)break
                        require(out.size()+count<=3_500_000) { "File too large" }
                        out.write(buffer,0,count)
                    }
                    out.toString(Charsets.UTF_8.name())
                } ?: error("Missing CSV")
                CarScannerCsv.parse(bytes)
            } catch (_:Exception) { null }
            runOnUiThread {
                if(isFinishing || isDestroyed)return@runOnUiThread
                if(reading==null){
                    alert("CSV okunamadı", "Dosya 3,5 MB altında ve Car Scanner CSV biçiminde olmalı.")
                    return@runOnUiThread
                }
                val builder=AlertDialog.Builder(this)
                    .setTitle("Car Scanner CSV • Önizleme")
                    .setMessage(if(reading.hasMeasurements())
                        reading.detail()+"\n\nHam konum, VIN ve diğer veri alanları kaydedilmeyecek. " +
                        "Bu bir tanılama/SoH sertifikası değildir."
                        else "Tanınan batarya ölçümü bulunamadı.\n\n"+
                            "İncelenen satır: "+reading.inspectedRows+
                            ". CSV sütun isimleri başka olabilir.")
                    .setNegativeButton("Kapat",null)
                if(reading.socPercent != null && reading.odometerKm != null){
                    builder.setPositiveButton("SoC ve km kaydını sakla") { _, _ ->
                        val note=listOfNotNull(
                            reading.batteryVoltageV?.let{"Voltaj $it V"},
                            reading.batteryTemperatureC?.let{"Sıcaklık $it C"},
                            reading.dcPowerKw?.let{"Güç $it kW"}
                        ).joinToString(" • ")
                        if(EvGarageStore.addNote(this,
                            EvGarageStore.BatteryNote(System.currentTimeMillis(),
                                reading.socPercent,reading.odometerKm,
                                "Car Scanner CSV • "+note))) toast("CSV özeti kaydedildi.")
                        else alert("Kaydedilemedi","Ölçümler geçersiz.")
                    }
                }
                builder.show()
            }
        }.start()
    }

    private fun showBatteryNotes() {
        val notes=EvGarageStore.notes(this)
        val body=if(notes.isEmpty())"Henüz ölçüm yok." else notes.take(30).joinToString("\n\n") {
            date(it.timeMs)+" • "+num(it.odometerKm)+" km • SoC %"+num(it.socPercent)+"\n"+it.note
        }
        alert("Yerel batarya kayıtları • "+notes.size,body+
            "\n\nBu liste SoH testi veya servis teşhisi değildir.")
    }

    private fun showSessions(){
        if(!::recent.isInitialized)return
        recent.removeAllViews()
        val sessions=EvGarageStore.sessions(this)
        val sums=EvPlanner.chargingSessionsTotal(sessions)
        recent.addView(info("Toplam "+sessions.size+" şarj",
            num(sums.first)+" kWh  •  "+num(sums.second)+" TL"))
        sessions.take(12).forEach{s->
            recent.addView(text(date(s.createdAtUtcMs)+" • "+
                s.description.take(35)+"  /  "+num(s.gridKwh)+" kWh  /  "+num(s.costTl)+" TL",
                13,false,neutral))
        }
        if(sessions.size>12)recent.addView(text("Önceki seanslar: "+(sessions.size-12),12,false,neutral))
    }

    private fun confirmClear(){
        AlertDialog.Builder(this).setTitle("Şarj geçmişini sil")
            .setMessage("Kaydedilmiş tüm şarj maliyeti kayıtları kalıcı olarak silinecek.")
            .setNegativeButton("Vazgeç",null)
            .setPositiveButton("Sil"){_,_->EvGarageStore.clearSessions(this);showSessions()}
            .show()
    }

    private fun shareReport(){
        val sessions=EvGarageStore.sessions(this)
        val total=EvPlanner.chargingSessionsTotal(sessions)
        val msg="EV Cockpit • Şarj özeti\n" +
            "Seans: "+sessions.size+"\nToplam enerji: "+num(total.first)+
            " kWh\nÖdeme: "+num(total.second)+" TL\n" +
            "Veriler telefona manuel girildi; araçtan canlı alınmadı."
        val intent=Intent(Intent.ACTION_SEND).apply{
            type="text/plain";putExtra(Intent.EXTRA_TEXT,msg)
        }
        try{startActivity(Intent.createChooser(intent,"EV raporunu paylaş"))}
        catch (_:Exception){alert("Paylaşılamadı","Telefonun paylaşım penceresi açılamadı.")}
    }

    private fun openStations(){
        try{startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("geo:0,0?q="+Uri.encode("elektrikli araç şarj istasyonu"))))}
        catch (_:Exception){alert("Harita bulunamadı","Yüklü harita uygulaması bulunamadı.")}
    }

    private fun field(label:String,key:String,value:Double):LinearLayout =
        LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(0,dp(5),0,dp(5))
            addView(text(label,12,false,neutral))
            val entry=input(label,numeric=true)
            entry.setText(if(value%1==0.0)value.toInt().toString() else value.toString())
            rows[key]=entry
            addView(entry,LinearLayout.LayoutParams(-1,dp(49)))
        }

    private fun input(hint:String,numeric:Boolean=false):EditText=EditText(this).apply {
        this.hint=hint
        textSize=15f
        setTextColor(Color.WHITE)
        setHintTextColor(neutral)
        setSingleLine(true)
        if(numeric) inputType=InputType.TYPE_CLASS_NUMBER or
            InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        background=shape(surface)
        setPadding(dp(13),dp(9),dp(12),dp(9))
    }

    private fun EditText.value():Double?=text.toString().trim().replace(',','.').toDoubleOrNull()
    private fun num(x:Double):String=String.format(tr,"%.1f",x)
    private fun date(x:Long):String=SimpleDateFormat("dd.MM.yyyy HH:mm",tr).format(Date(x))
    companion object { private const val PICK_CARSCANNER_CSV=9502 }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun shape(color:Int)=GradientDrawable().apply{
        setColor(color);cornerRadius=dp(13).toFloat()
    }
    private fun text(msg:String,size:Int,strong:Boolean,color:Int=Color.WHITE):TextView=
        TextView(this).apply{
            text=msg;textSize=size.toFloat();setTextColor(color)
            if(strong)setTypeface(typeface,android.graphics.Typeface.BOLD)
            setPadding(dp(8),dp(9),dp(8),dp(9))
        }
    private fun section(msg:String):TextView=text(msg,17,true,accent).apply{
        setPadding(dp(7),dp(18),dp(7),dp(8))
    }
    private fun info(label:String,value:String):View=
        LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            background=shape(surface)
            val lp=LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(6)}
            layoutParams=lp
            setPadding(dp(9),dp(5),dp(9),dp(5))
            addView(text(label,13,false,neutral),LinearLayout.LayoutParams(0,-2,1f))
            addView(text(value,14,true,Color.WHITE))
        }
    private fun button(label:String,fn:()->Unit):Button=Button(this).apply{
        text=label;textSize=12.5f;isAllCaps=false
        setTextColor(Color.WHITE);background=shape(0xff205267.toInt())
        setOnClickListener{fn()}
    }
    private fun toast(msg:String)=android.widget.Toast.makeText(this,msg,android.widget.Toast.LENGTH_LONG).show()
    private fun alert(title:String,msg:String){
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton("Tamam",null).show()
    }

    /** Indicative visual only: values come from editable manual inputs. */
    private inner class BatteryBar:View(this@EvGarageActivity){
        var soc=60f
        var target=80f
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas:Canvas){
            super.onDraw(canvas)
            val left=dp(5).toFloat()
            val right=width.toFloat()-dp(5)
            val top=dp(6).toFloat()
            val bottom=height.toFloat()-dp(6)
            paint.color=0xff253747.toInt()
            canvas.drawRoundRect(left,top,right,bottom,dp(8).toFloat(),dp(8).toFloat(),paint)
            paint.color=accent
            canvas.drawRoundRect(left,top,left+(right-left)*(soc.coerceIn(0f,100f)/100f),bottom,
                dp(8).toFloat(),dp(8).toFloat(),paint)
            paint.color=Color.WHITE
            paint.strokeWidth=dp(3).toFloat()
            val position=left+(right-left)*(target.coerceIn(0f,100f)/100f)
            canvas.drawLine(position,top,position,bottom,paint)
        }
    }
}
