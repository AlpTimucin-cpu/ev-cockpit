package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Offline, manually entered EV energy planner. No vehicle control, telemetry,
 * background services or media initialization.
 */
class EvEnergyActivity:Activity() {
    private val fields=LinkedHashMap<String,EditText>()
    private lateinit var result:TextView
    private val aqua=0xff78e5cf.toInt()
    private val bg=0xff091923.toInt()
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=bg
        window.navigationBarColor=bg
        val scroll=ScrollView(this).apply{setBackgroundColor(bg)}
        val body=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(30),dp(16),dp(36))
        }
        scroll.addView(body)
        setContentView(scroll)
        body.addView(label("‹ Geri",15,true).apply{setOnClickListener{finish()}})
        body.addView(label("EV Cockpit ENERJİ MERKEZİ",22,true))
        body.addView(label("Yol menzili ve şarj gideri • elle girilen veriler",13,false))
        val p=EvGarageStore.profile(this)
        body.addView(field("Kullanılabilir kapasite (kWh)","cap",p.usableKwh))
        body.addView(field("Batarya şarjı (%)","soc",p.socPercent))
        body.addView(field("Hedef şarj (%)","target",p.targetPercent))
        body.addView(field("Varış rezervi (%)","reserve",p.reservePercent))
        body.addView(field("Tüketim (kWh/100 km)","cons",p.consumptionKwhPer100Km))
        body.addView(field("Planlanan yol (km)","km",p.distanceKm))
        body.addView(field("Ortalama şarj gücü (kW)","power",p.averageChargingKw))
        body.addView(field("Şarj verimi (%)","eff",p.gridEfficiency*100))
        body.addView(field("TL/kWh gerçek tarife","price",p.priceTlPerKwh))
        body.addView(button("Hesapla ve profili kaydet"){
            val profile=read()
            if(profile==null){notify("Hatalı sayı","Değerleri kontrol et.");return@button}
            EvGarageStore.saveProfile(this,profile)
            calculate(profile)
        })
        body.addView(button("＋ Gerçek şarj gideri kaydet"){recordSession()})
        result=label("Hesaplama hazır.",14,false)
        body.addView(result)
        body.addView(label("Not: Sonuçlar ideal enerji hesabıdır. " +
            "Trafik, hız, hava, eğim ve DC şarj eğrisi gerçek menzil/süreyi değiştirir. " +
            "Araçla iletişim kurulmaz.",12,false))
        calculate(p)
    }
    private fun calculate(p:EvPlanner.Profile){
        val c=EvPlanner.charge(p)
        val trip=EvPlanner.trip(p)
        fun n(x:Double)=String.format(Locale("tr","TR"),"%.1f",x)
        val sessions=EvGarageStore.sessions(this)
        val totals=EvPlanner.chargingSessionsTotal(sessions)
        result.text="ŞARJ PLANI\n"+
            "Bataryaya: "+n(c.batteryKwh)+" kWh\n"+
            "Şebekeden: "+n(c.gridKwh)+" kWh\n"+
            "Maliyet: "+n(c.costTl)+" TL\n"+
            "İdeal süre: "+n(c.idealHoursAtChosenAverageKw*60)+" dakika\n\n"+
            "YOL PLANI\n"+
            "Teorik mevcut menzil: "+n(trip.currentRangeKm)+" km\n"+
            "Rezerve kadar menzil: "+n(trip.usableRangeUntilReserveKm)+" km\n"+
            "Şarjsız varış SoC: "+n(trip.arrivalPercentWithoutCharge)+" %\n"+
            "Gerekli ilave batarya enerjisi: "+
            n(trip.batteryKwhRequiredToArriveWithReserve)+" kWh\n\n"+
            "KAYITLI ŞARJ GEÇMİŞİ\n"+sessions.size+" işlem · "+
            n(totals.first)+" kWh · "+n(totals.second)+" TL"
    }
    private fun read():EvPlanner.Profile?{
        fun d(key:String)=fields[key]?.text.toString().replace(',','.').toDoubleOrNull()
        val p=EvPlanner.Profile(
            usableKwh=d("cap")?:return null,
            socPercent=d("soc")?:return null,
            targetPercent=d("target")?:return null,
            reservePercent=d("reserve")?:return null,
            consumptionKwhPer100Km=d("cons")?:return null,
            distanceKm=d("km")?:return null,
            averageChargingKw=d("power")?:return null,
            gridEfficiency=(d("eff")?:return null)/100.0,
            priceTlPerKwh=d("price")?:return null)
        return p.takeIf {EvPlanner.valid(it)}
    }
    private fun recordSession(){
        val holder=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(10),dp(18),dp(10))
        }
        fun input(h:String)=EditText(this).apply {hint=h;isSingleLine=true}
        val name=input("Şarj istasyonu")
        val energy=input("Faturalanan kWh").apply {
            inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val paid=input("Ödenen TL").apply{
            inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        listOf(name,energy,paid).forEach { holder.addView(it) }
        AlertDialog.Builder(this).setTitle("Gerçek şarjı kaydet")
            .setView(holder).setNegativeButton("Vazgeç",null)
            .setPositiveButton("Kaydet"){_,_->
                val kwh=energy.text.toString().replace(',','.').toDoubleOrNull()
                val tl=paid.text.toString().replace(',','.').toDoubleOrNull()
                if(kwh==null||tl==null ||
                    !EvGarageStore.addSession(this,ChargeSession(
                        System.currentTimeMillis(),name.text.toString(),kwh,tl))){
                    notify("Kayıt yok","Geçerli kWh ve TL değerleri gir.")
                }else{
                    read()?.let(::calculate)
                }
            }.show()
    }
    private fun field(hint:String,key:String,value:Double):LinearLayout{
        val box=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
        }
        box.addView(label(hint,13,false))
        val input=EditText(this).apply{
            setText(value.toString())
            textSize=16f
            setTextColor(Color.WHITE)
            setSingleLine(true)
            inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            background=GradientDrawable().apply{
                setColor(0xff1a3443.toInt());cornerRadius=dp(11).toFloat()
            }
            setPadding(dp(14),dp(7),dp(14),dp(7))
        }
        fields[key]=input
        box.addView(input)
        return box
    }
    private fun label(v:String,size:Int,strong:Boolean)=TextView(this).apply{
        text=v;textSize=size.toFloat()
        setTextColor(if(strong)aqua else 0xffc2d3dd.toInt())
        if(strong)setTypeface(typeface,Typeface.BOLD)
        setPadding(dp(5),dp(10),dp(5),dp(10))
    }
    private fun button(s:String,click:()->Unit)=Button(this).apply{
        text=s;textSize=14f;isAllCaps=false
        setTextColor(Color.WHITE)
        background=GradientDrawable().apply{
            setColor(0xff25617a.toInt());cornerRadius=dp(12).toFloat()
        }
        setOnClickListener{click()}
        layoutParams=LinearLayout.LayoutParams(-1,dp(55)).apply{
            topMargin=dp(9)
        }
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun notify(title:String,detail:String)=AlertDialog.Builder(this)
        .setTitle(title).setMessage(detail).setPositiveButton("Tamam",null).show()
}
