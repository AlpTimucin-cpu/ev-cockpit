package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * TV-only screen, deliberately isolated from EV/navigation initialization.
 * Imports the user's OWN M3U through SAF and shares only the channel the user
 * selects. MediaEngine remains an independent installed app. No pirated IPTV,
 * Netflix/Prime or film/series catalogue is embedded.
 */
class MediaEngineTvActivity:Activity() {
    private val packages=listOf(
        "me.app.fermataX.auto.mola32.debug",
        "me.app.fermataX.auto.debug",
        "me.app.fermataX.auto")
    private val prefs by lazy { getSharedPreferences("ev_mediaengine_tv",MODE_PRIVATE) }
    private val worker=Executors.newSingleThreadExecutor()
    @Volatile private var loadToken=0
    private var channels:List<MediaEngineTvPlaylist.Channel> = emptyList()
    private lateinit var state:TextView
    private lateinit var search:EditText
    private lateinit var results:LinearLayout
    private val bg=0xff081923.toInt()
    private val light=0xffa9c6d5.toInt()
    private val accent=0xff6ee1ca.toInt()

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=bg
        window.navigationBarColor=bg
        val sc=ScrollView(this).apply{setBackgroundColor(bg)}
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(d(16),d(28),d(16),d(30))
        }
        sc.addView(root)
        setContentView(sc)
        root.addView(label("‹ Geri",15,true).apply{setOnClickListener{finish()}})
        root.addView(label("MEDIAENGINE TV",24,true))
        root.addView(label(
            "Yalnızca canlı TV • Film ve dizi katalogları eklenmedi",13,false))
        root.addView(label(
            "MediaEngine kuruluysa onu açabilirsin. Kendi yasal HTTPS M3U listenin " +
            "kanallarını da burada seçip sistem oynatıcısında veya MediaEngine'da açabilirsin.",
            12,false))
        root.addView(button("▶ Kurulu MediaEngine TV uygulamasını aç") {openMediaEngine()})
        root.addView(button("＋ TV kanalları (M3U) dosyası seç") {chooseM3u()})
        root.addView(button("↻ Son seçtiğim M3U listesini aç") {
            val value=prefs.getString("source_uri",null)
            if(value.isNullOrBlank()){
                show("Liste seçilmedi","Önce telefonundan kendi M3U dosyanı seç.")
            }else load(Uri.parse(value))
        })
        val sec=label("KANAL ARAMA",16,true)
        sec.setPadding(d(8),d(16),d(8),d(8))
        root.addView(sec)
        search=EditText(this).apply{
            hint="Kanal veya grup adı"
            setTextColor(Color.WHITE)
            setHintTextColor(light)
            setSingleLine(true)
            textSize=14f
            setPadding(d(10),d(10),d(10),d(10))
        }
        root.addView(search)
        root.addView(button("Kanallarda ara") {render()})
        state=label("MediaEngine: "+(installedPackage()?.let{"kurulu"}?:"kurulu değil")+
            " • TV listesi henüz açılmadı.",12,false)
        root.addView(state)
        results=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        root.addView(results)
        root.addView(label(
            "Güvenlik: HTTP ve hatalı yayın adresleri atlanır. " +
            "Liste sağlayıcısının yayın hakları ve cihazdaki hesap bilgileri " +
            "kullanıcı sorumluluğundadır. İzleme yalnızca araç park halindeyken.",
            12,false))
    }

    private fun installedPackage():String?=
        packages.firstOrNull {
            try {packageManager.getLaunchIntentForPackage(it)!=null}
            catch(_:Exception){false}
        }

    private fun openMediaEngine() {
        val pkg=installedPackage()
        if(pkg==null){
            show("MediaEngine kurulu değil",
                "Telefonunda MediaEngine 3.2 / FermataX bulunamadı. " +
                "İstersen kendi M3U dosyanı içe aktarıp Android'in desteklediği " +
                "yayınları uygulamada deneyebilirsin. APK otomatik yüklenmez.")
            return
        }
        try{
            startActivity(packageManager.getLaunchIntentForPackage(pkg)!!)
        }catch(_:Exception){show("MediaEngine başlatılamadı","Android MediaEngine uygulamasını açamadı.")}
    }

    private fun chooseM3u(){
        try{
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
                addCategory(Intent.CATEGORY_OPENABLE)
                type="*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            },PICK_M3U)
        }catch(_:Exception){show("Dosya açılamadı","Android dosya seçicisi kullanılamıyor.")}
    }

    @Deprecated("Framework result callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=PICK_M3U||resultCode!=RESULT_OK)return
        val uri=data?.data?:return
        try{
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString("source_uri",uri.toString()).apply()
        }catch(_:SecurityException){
            // Still read immediately with the temporary grant. Do not store
            // a URI we cannot access again after a process restart.
            prefs.edit().remove("source_uri").apply()
        }
        load(uri)
    }

    private fun load(uri:Uri){
        val token=++loadToken
        state.text="M3U okunuyor • Android arka plan işlemi…"
        results.removeAllViews()
        worker.execute{
            val parsed=try{
                val bytes=contentResolver.openInputStream(uri)?.use{input->
                    val out=ByteArrayOutputStream()
                    val buffer=ByteArray(8192)
                    while(true){
                        val count=input.read(buffer)
                        if(count<0)break
                        require(out.size()+count<=4_000_000){"Dosya 4 MB sınırını aşıyor"}
                        out.write(buffer,0,count)
                    }
                    out.toByteArray()
                }?:error("İçerik okunamadı")
                MediaEngineTvPlaylist.parse(String(bytes,Charsets.UTF_8))
            }catch(_:Exception){null}
            runOnUiThread{
                if(isFinishing||isDestroyed||token!=loadToken)return@runOnUiThread
                if(parsed==null){
                    state.text="M3U dosyası okunamadı. 4 MB altındaki metin M3U dosyanı seç."
                    return@runOnUiThread
                }
                channels=parsed.channels
                state.text=channels.size.toString()+" HTTPS TV kanalı yüklendi • "+
                    parsed.unsafeUrlsSkipped+" güvenli olmayan bağlantı atlandı."
                render()
            }
        }
    }

    private fun render(){
        if(!::results.isInitialized)return
        results.removeAllViews()
        val matches=MediaEngineTvPlaylist.filter(channels,search.text.toString(),60)
        if(matches.isEmpty()) {
            results.addView(label("Eşleşen kanal yok.",13,false))
            return
        }
        matches.forEach { channel ->
            results.addView(button("📺 "+channel.title.take(90)+
                "\n"+channel.group.take(55)) {
                pickPlayer(channel)
            })
        }
        if(channels.size>matches.size) {
            results.addView(label("İlk "+matches.size+
                " kanal gösteriliyor. Başka kanal bulmak için aramayı daralt.",12,false))
        }
    }

    private fun pickPlayer(channel:MediaEngineTvPlaylist.Channel) {
        // Never place the tokenized private stream URL in any error dialog/log.
        AlertDialog.Builder(this)
            .setTitle(channel.title.take(100))
            .setMessage("Kendi HTTPS TV kanalını aç. Yayın adresi abonelik bilgileri " +
                "içerebilir. Bağlantıyı yalnızca güvendiğin oynatıcıyla paylaş.")
            .setNeutralButton("Telefonda oynat"){_,_->
                startActivity(Intent(this,MediaEngineTvPlayerActivity::class.java).apply{
                    // Pass only the in-memory channel selection through the
                    // activity, never store credentials in logs/preferences.
                    putExtra(MediaEngineTvPlayerActivity.EXTRA_URL,channel.url)
                    putExtra(MediaEngineTvPlayerActivity.EXTRA_NAME,channel.title)
                })
            }
            .setNegativeButton("Vazgeç",null)
            .setPositiveButton("MediaEngine'da aç"){_,_->shareWithMediaEngine(channel)}
            .show()
    }

    private fun shareWithMediaEngine(channel:MediaEngineTvPlaylist.Channel) {
        val pkg=installedPackage()
        if(pkg==null){
            show("MediaEngine kurulu değil",
                "Harici MediaEngine oynatıcısı kurulu değil. 'Telefonda oynat' ile " +
                "Android sistem oynatıcısını deneyebilirsin.")
            return
        }
        val intent=Intent(Intent.ACTION_VIEW).apply{
            setPackage(pkg)
            setDataAndType(Uri.parse(channel.url),"video/*")
        }
        try{
            startActivity(intent)
        }catch(_:ActivityNotFoundException){
            // Not all MediaEngine builds declare ACTION_VIEW video/*.
            // Still permit normal app launch, without exposing the secret URL
            // in the OS-wide browser or clipboard.
            show("MediaEngine bağlantıyı desteklemiyor",
                "Bu MediaEngine sürümü doğrudan kanal aktarımını desteklemiyor olabilir. " +
                "MediaEngine uygulamasını ayrı açıp listeyi orada kullanabilirsin.")
        }catch(_:SecurityException){
            show("Aktarım engellendi","Android, MediaEngine'ya yayın göndermeye izin vermedi.")
        }
    }

    private fun show(title:String,details:String){
        AlertDialog.Builder(this).setTitle(title).setMessage(details)
            .setPositiveButton("Tamam",null).show()
    }
    private fun label(textValue:String,size:Int,strong:Boolean):TextView=
        TextView(this).apply{
            text=textValue;textSize=size.toFloat()
            setTextColor(if(strong)accent else light)
            if(strong)setTypeface(typeface,Typeface.BOLD)
            setPadding(d(6),d(9),d(6),d(9))
        }
    private fun button(value:String,action:()->Unit):Button=Button(this).apply{
        text=value;textSize=13f;isAllCaps=false
        setTextColor(Color.WHITE)
        background=GradientDrawable().apply {
            setColor(0xff205367.toInt());cornerRadius=d(12).toFloat()
        }
        val lp=LinearLayout.LayoutParams(-1,ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin=d(8)
        layoutParams=lp
        minimumHeight=d(51)
        setOnClickListener{action()}
    }
    private fun d(x:Int)=(x*resources.displayMetrics.density).toInt()
    override fun onDestroy(){
        loadToken++
        worker.shutdownNow()
        super.onDestroy()
    }
    companion object {private const val PICK_M3U=8822}
}
