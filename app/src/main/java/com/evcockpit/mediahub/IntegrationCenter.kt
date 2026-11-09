package com.evcockpit.mediahub

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Combined information panel. No unapproved projection, unsafe hooks or APK repacking. */
object IntegrationCenter {
    private fun found(context: Context, item: IntegrationSource): List<String> =
        item.packages.mapNotNull { name ->
            CarCompanionApps.installedVersion(context, name)?.let { name + " • " + it }
        }

    private fun status(context: Context, item: IntegrationSource): String {
        val packages = found(context, item)
        if (item.kind == IntegrationKind.NATIVE) return "Media Hub içinde"
        return if (packages.isEmpty()) "Telefonda kurulu görünmüyor" else
            "Telefonda bulundu: " + packages.size + " paket"
    }

    fun report(context: Context): String = buildString {
        appendLine("EV Cockpit MEDIA HUB • ORTAK ENTEGRASYON MERKEZİ")
        appendLine("Android API: " + android.os.Build.VERSION.SDK_INT)
        appendLine()
        IntegrationCatalog.sources.forEach { item ->
            appendLine(item.name + " • " + status(context, item))
            found(context, item).forEach { appendLine("  " + it) }
            appendLine("  " + item.detail)
        }
        appendLine()
        appendLine("AABrowser'da YouTube sayfasının araçta açılması kullanıcı tarafından bildirildi.")
        appendLine("Media Hub/ScreenOnAuto video araç aktarımı henüz doğrulanmadı.")
        appendLine("Hız/vites/park sinyali değiştirme veya sürüşte video kilidi aşma yok.")
    }

    fun show(activity: Activity) {
        fun dp(value: Int): Int =
            (value * activity.resources.displayMetrics.density).toInt()
        fun text(value: String, size: Float, color: Int): TextView =
            TextView(activity).apply {
                this.text = value
                textSize = size
                setTextColor(color)
                setPadding(dp(8),dp(6),dp(8),dp(6))
            }
        fun button(value: String, action: () -> Unit): Button =
            Button(activity).apply {
                this.text = value
                textSize = 12f
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(0xff24516c.toInt())
                    cornerRadius = dp(9).toFloat()
                }
                setOnClickListener { action() }
            }

        val scroll = ScrollView(activity)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12),dp(8),dp(12),dp(12))
            setBackgroundColor(0xff0b1829.toInt())
        }
        scroll.addView(column)
        column.addView(text("ORTAK ENTEGRASYON MERKEZİ",19f,Color.WHITE))
        column.addView(text("IPTV / EPG / radyo ve Web Medya Media Hub içinde. Harici uygulamalar yalnızca yüklüyse çalıştırılır.",12f,0xffc2d2e0.toInt()))
        column.addView(text(IntegrationCatalog.sources.count {
            it.kind == IntegrationKind.NATIVE
        }.toString() + " dahili özellik grubu • " +
            IntegrationCatalog.sources.size + " entegrasyon kaynağı",12f,0xff8de1d3.toInt()))
        IntegrationCatalog.sources.forEach { item ->
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9),dp(6),dp(9),dp(9))
                background = GradientDrawable().apply {
                    setColor(0xff19314a.toInt())
                    cornerRadius = dp(11).toFloat()
                }
            }
            val kind = when (item.kind) {
                IntegrationKind.NATIVE -> "DAHİLİ"
                IntegrationKind.EXTERNAL -> "HARİCİ BAĞLANTI"
                IntegrationKind.DIAGNOSTIC -> "DURUM KONTROLÜ"
                IntegrationKind.REFERENCE -> "TEKNİK REFERANS"
            }
            card.addView(text(item.name + " · " + kind,14f,Color.WHITE))
            card.addView(text(status(activity,item),12f,0xff8de1d3.toInt()))
            card.addView(text(item.detail,12f,0xffc2d2e0.toInt()))
            when (item.id) {
                "web" -> card.addView(button("AABrowser'ı telefonda aç") {
                    launch(activity,item.packages,"AABrowser")
                },LinearLayout.LayoutParams(-1,dp(44)))
                "screenonauto" -> {
                    val row=LinearLayout(activity).apply {
                        orientation=LinearLayout.HORIZONTAL
                    }
                    row.addView(button("▶ Yansıtma isteği") {
                        ScreenOnAutoBridge.send(activity,true)
                    },LinearLayout.LayoutParams(0,dp(44),1f))
                    row.addView(button("■ Durdur") {
                        ScreenOnAutoBridge.send(activity,false)
                    },LinearLayout.LayoutParams(0,dp(44),1f))
                    card.addView(row)
                }
                "fermata" -> card.addView(button("Fermata'yı telefonda aç") {
                    launch(activity,item.packages,"Fermata")
                },LinearLayout.LayoutParams(-1,dp(44)))
                "mediaengine32" -> card.addView(button("MediaEngine 3.2'yi telefonda aç") {
                    MediaEngineBridge.open(activity)
                },LinearLayout.LayoutParams(-1,dp(44)))
                "fermatax" -> card.addView(button("FermataX'i telefonda aç") {
                    launch(activity,item.packages,"FermataX")
                },LinearLayout.LayoutParams(-1,dp(44)))
                "rectv" -> card.addView(button("REC TV'yi telefonda aç") {
                    InstalledMediaApps.openRecTv(activity)
                },LinearLayout.LayoutParams(-1,dp(44)))
                "screen2auto" -> card.addView(button("Screen2Auto'yu telefonda aç") {
                    launch(activity,item.packages,"Screen2Auto")
                },LinearLayout.LayoutParams(-1,dp(44)))
                "mirrormobile" -> card.addView(button("MirrorMobile'ı telefonda aç") {
                    launch(activity,item.packages,"MirrorMobile")
                },LinearLayout.LayoutParams(-1,dp(44)))
                "diplay" -> card.addView(button("DiPlay • Bağlantı / uyumluluk raporu") {
                    AlertDialog.Builder(activity)
                        .setTitle("DiPlay / EV Cockpit ön kontrol")
                        .setMessage(DiPlayCompatibility.report(activity))
                        .setNegativeButton("Kapat", null)
                        .setPositiveButton("Raporu kopyala") { _, _ ->
                            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(
                                "EV Cockpit / DiPlay uyumluluk", DiPlayCompatibility.report(activity)))
                        }.show()
                }, LinearLayout.LayoutParams(-1, dp(44)))
                "androidauto" -> card.addView(button("Android Auto bağlantı raporu") {
                    CarCompatibility.show(activity)
                },LinearLayout.LayoutParams(-1,dp(44)))
                "shizuku" -> card.addView(button("Shizuku API durumunu göster") {
                    CarCompanionApps.show(activity)
                },LinearLayout.LayoutParams(-1,dp(44)))
            }
            column.addView(card,LinearLayout.LayoutParams(-1,-2).apply {
                bottomMargin=dp(8)
            })
        }
        column.addView(text("Hareket halindeyken sürücü ekranı video kısıtlarını kaldıran işlem yapılmaz.",12f,0xffffbe88.toInt()))
        AlertDialog.Builder(activity)
            .setTitle("EV Cockpit / Uygulama Merkezi")
            .setView(scroll)
            .setNegativeButton("Kapat",null)
            .setPositiveButton("Raporu kopyala") { _,_ ->
                val manager = activity.getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager
                manager.setPrimaryClip(ClipData.newPlainText(
                    "EVCOCKPIT entegrasyon raporu",report(activity)
                ))
            }
            .show()
    }

    private fun launch(activity: Activity, packages: List<String>, label: String) {
        val pm=activity.packageManager
        val intent=packages.firstNotNullOfOrNull { name ->
            if (CarCompanionApps.installedVersion(activity,name) != null)
                pm.getLaunchIntentForPackage(name) else null
        }
        if (intent == null) {
            AlertDialog.Builder(activity)
                .setTitle(label + " başlatılamadı")
                .setMessage("Bu telefonda başlatılabilir sürüm bulunamadı. Harici APK otomatik indirilmez veya kurulmaz.")
                .setPositiveButton("Tamam",null).show()
            return
        }
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity,label + " açılamadı",Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(activity,label + " erişimi reddedildi",Toast.LENGTH_SHORT).show()
        } catch (_: IllegalStateException) {
            Toast.makeText(activity,label + " açılamadı",Toast.LENGTH_SHORT).show()
        }
    }
}
