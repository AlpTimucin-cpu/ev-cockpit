package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Collect only the Java/Kotlin stack trace of our own fatal errors, on-device.
 * Never uploads diagnostics, URLs, accounts, playlists or private data.
 */
object StartupCrashNotes {
    private const val PREF="evcockpit_startup_crash_notes"
    private const val STACK="stack"
    private const val WHEN="when"
    private val urls=Regex("""(?:https?://|rtsp://|rtmp://)\S+""",RegexOption.IGNORE_CASE)
    private val credentials=Regex("""(?i)(password|token|apikey|username|authorization)[=:]\s*\S+""")
    fun save(context:Context,error:Throwable) = saveDiagnostic(context,
        "Beklenmeyen hata (exception)\n"+error.stackTraceToString())

    // Invoked only from a background thread during a possible UI stall.
    fun saveDiagnostic(context:Context,raw:String) {
        val cleaned=raw.replace(urls,"[private source]")
            .replace(credentials,"[private credential]")
            .take(3400)
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit()
            .putString(STACK,cleaned)
            .putLong(WHEN,System.currentTimeMillis())
            .commit()
    }
    fun latest(context:Context):String?=
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(STACK,null)
    fun clear(context:Context){
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().clear().apply()
    }
}

class SafeStartApplication:Application() {
    private val active = AtomicBoolean(false)
    private val lastResponse = AtomicLong(SystemClock.uptimeMillis())
    private val main = Handler(Looper.getMainLooper())
    private val mainThread = Looper.getMainLooper().thread
    private val capturedThisSession = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { StartupCrashNotes.save(this,error) } catch (_:Throwable) {}
            if(previous!=null) previous.uncaughtException(thread,error)
            else kotlin.system.exitProcess(1)
        }
        // ANR does not throw an exception. A low-frequency watchdog records
        // the *main-thread stack* if the foreground UI stops handling messages.
        registerActivityLifecycleCallbacks(object:ActivityLifecycleCallbacks {
            override fun onActivityCreated(a:Activity,b:Bundle?){}
            override fun onActivityStarted(a:Activity){}
            override fun onActivityResumed(a:Activity){
                active.set(true)
                lastResponse.set(SystemClock.uptimeMillis())
            }
            override fun onActivityPaused(a:Activity){active.set(false)}
            override fun onActivityStopped(a:Activity){}
            override fun onActivitySaveInstanceState(a:Activity,out:Bundle){}
            override fun onActivityDestroyed(a:Activity){}
        })
        Thread({
            while(true) {
                try{
                    Thread.sleep(2500)
                    if(!active.get())continue
                    val before=SystemClock.uptimeMillis()
                    main.post{lastResponse.set(SystemClock.uptimeMillis())}
                    // After a verified ~9 second foreground UI stall,
                    // persist a local redacted trace for the next safe launch.
                    if(before-lastResponse.get()>9000 &&
                        capturedThisSession.compareAndSet(false,true) && active.get()){
                        val frames=mainThread.stackTrace.take(36)
                            .joinToString("\n"){it.toString()}
                        StartupCrashNotes.saveDiagnostic(this,
                            "Olası ANR: ana ekran 9 saniyeden uzun yanıt vermedi\n"+
                            "Ana iş parçacığı:\n"+frames)
                    }
                }catch(_:InterruptedException){return@Thread}
                catch(_:Exception){}
            }
        },"EVCOCKPIT-ANR-watchdog").apply{isDaemon=true;start()}
    }
}

/**
 * If opening the full media cockpit crashes, the next launch shows the local
 * stack trace instead of an endless crash loop.
 */
class SafeStartActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val saved=StartupCrashNotes.latest(this)
        if(saved.isNullOrBlank()){
            openMain()
            return
        }
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(0xff0c1e2d.toInt())
            setPadding(22,40,22,20)
        }
        setContentView(root)
        fun label(value:String,size:Float):TextView=TextView(this).apply{
            text=value;textSize=size;setTextColor(Color.WHITE)
            setPadding(12,10,12,10)
        }
        fun action(value:String,click:()->Unit):Button=Button(this).apply{
            text=value;isAllCaps=false;setOnClickListener{click()}
        }
        root.addView(label("EV Cockpit • Güvenli Başlatma",20f))
        root.addView(label(
            "Bir önceki çalıştırmada uygulama dondu veya kapandı. Tanılama yalnızca " +
                "telefonuna kaydedildi; hiçbir sunucuya gönderilmedi.",14f))
        root.addView(action("Ana uygulamayı tekrar dene"){
            StartupCrashNotes.clear(this);openMain()
        })
        root.addView(action("EV Cockpit Enerji Merkezini aç"){
            startActivity(Intent(this,EvGarageActivity::class.java))
        })
        root.addView(action("Türkiye şarj haritasını aç"){
            startActivity(Intent(this,TurkeyNavigationActivity::class.java))
        })
        root.addView(action("Hata raporunu kopyala"){
            val clipboard=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("EV Cockpit crash",saved))
            AlertDialog.Builder(this).setTitle("Rapor kopyalandı")
                .setMessage("Hata raporunu geliştiriciyle paylaşabilirsin. " +
                    "Göndermeden önce kişisel bilgilerini kontrol et.")
                .setPositiveButton("Tamam",null).show()
        })
        root.addView(action("Önceki raporu sil"){StartupCrashNotes.clear(this);openMain()})
        root.addView(label("Hatanın teknik özeti:\n"+
            saved.lineSequence().take(6).joinToString("\n").take(630),11f))
    }

    private fun openMain() {
        startActivity(Intent(this,MainActivity::class.java))
        finish()
    }
}
