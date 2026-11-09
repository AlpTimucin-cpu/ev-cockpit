package com.evcockpit.mediahub

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Original EV Cockpit-inspired, phone-side dashboard. No UI/source files are copied
 * from FermataX, and the Android Auto host retains its own audio-only UI.
 */
data class CockpitActions(
    val onTv: () -> Unit,
    val onRadio: () -> Unit,
    val onPodcast: () -> Unit,
    val onWeb: () -> Unit,
    val onYoutube: () -> Unit,
    val onDiscover: () -> Unit,
    val onMovies: () -> Unit,
    val onSeries: () -> Unit,
    val onSports: () -> Unit,
    val onNetflix: () -> Unit,
    val onPrimeVideo: () -> Unit,
    val onLibrary: () -> Unit,
    val onGuide: () -> Unit,
    val onCarStatus: () -> Unit,
    val onCompanions: () -> Unit,
    val onPlay: (CockpitEntry) -> Unit,
    val onRadioPauseResume: () -> Unit
)

object CockpitDashboard {
    private const val NAVY = 0xff101b2d.toInt()
    private const val SURFACE = 0xff1b2b40.toInt()
    private const val INK = 0xfff3f7fb.toInt()
    private const val MUTED = 0xffabbccd.toInt()
    private const val ACCENT = 0xff70e1d5.toInt()
    private const val GOLD = 0xffffcf85.toInt()

    private fun dp(activity: Activity, value: Int): Int =
        (activity.resources.displayMetrics.density * value).toInt()

    private fun background(activity: Activity, first: Int, second: Int, radius: Int) =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(first, second)
        ).apply { cornerRadius = dp(activity, radius).toFloat() }

    private fun text(
        activity: Activity, value: String, size: Float,
        color: Int = INK, bold: Boolean = false
    ): TextView = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        includeFontPadding = true
    }

    private fun row(activity: Activity) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun col(activity: Activity) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun actionButton(activity: Activity, title: String, action: () -> Unit): Button =
        Button(activity).apply {
            text = title
            textSize = 13f
            isAllCaps = false
            minHeight = dp(activity, 46)
            setTextColor(0xff0c2330.toInt())
            background = background(activity, 0xff84e6db.toInt(), 0xff47b8ba.toInt(), 12)
            setOnClickListener { action() }
        }

    private fun card(activity: Activity, prominent: Boolean = false) = col(activity).apply {
        setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 16))
        background = if (prominent)
            background(activity, 0xff1c5560.toInt(), 0xff182b47.toInt(), 20)
        else background(activity, SURFACE, NAVY, 18)
    }

    private fun vSpace(activity: Activity, height: Int) =
        View(activity).apply { layoutParams = LinearLayout.LayoutParams(1, dp(activity, height)) }

    private fun sectionTitle(activity: Activity, title: String, detail: String) =
        col(activity).apply {
            addView(text(activity, title, 16f, INK, true))
            addView(text(activity, detail, 12f, MUTED))
        }

    private data class Destination(val label: String, val subtitle: String,
                                   val symbol: String, val action: () -> Unit)

    fun render(
        activity: Activity,
        overview: CockpitOverview,
        epg: EpgScheduleIndex,
        activeRadio: String?,
        isRadioPlaying: Boolean,
        actions: CockpitActions
    ): LinearLayout {
        val widthDp = activity.resources.configuration.screenWidthDp
        val columns = when {
            widthDp >= 720 -> 3
            widthDp >= 360 -> 2
            else -> 1
        }
        val root = col(activity).apply {
            setPadding(0, dp(activity, 9), 0, dp(activity, 18))
            setBackgroundColor(0xff0b1728.toInt())
        }

        val hero = card(activity, true)
        hero.addView(text(activity, "ID⁴   /   YOL ARKADAŞIM", 12f, ACCENT, true))
        hero.addView(vSpace(activity, 8))
        hero.addView(text(activity, "Yolculuğunun\nmedya merkezi.", 26f, INK, true))
        hero.addView(vSpace(activity, 5))
        hero.addView(text(activity,
            "Telefonunda TV ve web medya. EV Cockpit için ayrı Android Auto radyo kitaplığı.",
            12f, 0xffcce6e7.toInt()))
        hero.addView(vSpace(activity, 15))
        val counts = row(activity)
        counts.addView(countBadge(activity, overview.tvCount, "TV kanalı"),
            LinearLayout.LayoutParams(0, -2, 1f))
        counts.addView(countBadge(activity, overview.radioCount, "radyo"),
            LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(counts)
        root.addView(hero)
        root.addView(vSpace(activity, 15))

        // Smart session: actual MediaController state, never a guessed vehicle connection.
        val audio = card(activity)
        val header = row(activity)
        val labels = col(activity).apply {
            addView(text(activity, "SES OTURUMU", 12f, ACCENT, true))
            addView(text(activity,
                if (activeRadio.isNullOrBlank()) "Bir radyo seçerek başla"
                else activeRadio.take(90),
                17f, INK, true))
            addView(text(activity, when {
                activeRadio.isNullOrBlank() -> "Android Auto ses kitaplığına hazır"
                isRadioPlaying -> "● Şu anda oynatılıyor"
                else -> "Ⅱ Duraklatıldı"
            }, 12f, MUTED))
        }
        header.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(actionButton(activity, if (isRadioPlaying) "Ⅱ  Duraklat" else "▶  Oynat") {
            actions.onRadioPauseResume()
        }, LinearLayout.LayoutParams(dp(activity, 115), dp(activity, 48)))
        audio.addView(header)
        root.addView(audio)
        root.addView(vSpace(activity, 19))

        // Three native library destinations: user-owned sources, not REC TV feeds.
        root.addView(sectionTitle(activity, "FİLM • DİZİ • MAÇ",
            "Kendi yayın kaynaklarının MediaEngine tarzı medya kütüphanesi"))
        root.addView(vSpace(activity, 10))
        val libraryRow = row(activity)
        listOf(
            Triple("▣", "FİLMLER", actions.onMovies),
            Triple("▤", "DİZİLER", actions.onSeries),
            Triple("⚽", "MAÇLAR", actions.onSports)
        ).forEachIndexed { index, item ->
            val tile = card(activity)
            tile.gravity=Gravity.CENTER
            tile.isClickable=true
            tile.isFocusable=true
            tile.contentDescription=item.second + " bölümünü aç"
            tile.setOnClickListener { item.third() }
            tile.background=when(index) {
                0 -> background(activity,0xff632d46.toInt(),0xff241b35.toInt(),18)
                1 -> background(activity,0xff4a3a71.toInt(),0xff221b3c.toInt(),18)
                else -> background(activity,0xff1d665d.toInt(),0xff152d35.toInt(),18)
            }
            tile.addView(text(activity,item.first,29f,Color.WHITE,true))
            tile.addView(vSpace(activity,8))
            tile.addView(text(activity,item.second,12f,Color.WHITE,true))
            libraryRow.addView(tile,LinearLayout.LayoutParams(0,dp(activity,120),1f).apply{
                rightMargin=dp(activity,5)
            })
        }
        root.addView(libraryRow)
        root.addView(vSpace(activity,20))

        // MediaEngine-inspired, artwork-first cards on the PHONE dashboard.
        // These cards open installed official phone apps only; no WebView fallback.
        root.addView(sectionTitle(activity, "VİDEO PLATFORMLARI",
            "Büyük görsel kartlarla hızlı erişim"))
        root.addView(vSpace(activity, 10))
        root.addView(StreamingServiceCards.render(
            activity = activity,
            youtube = actions.onYoutube,
            netflix = actions.onNetflix,
            prime = actions.onPrimeVideo
        ))
        root.addView(vSpace(activity, 7))

        root.addView(sectionTitle(activity, "HIZLI ERİŞİM", "İhtiyacın olan her şey burada"))
        root.addView(vSpace(activity, 9))
        val items = listOf(
            Destination("CANLI TV", "Kanallar ve yayınlar", "▣", actions.onTv),
            Destination("RADYO", "Ses ve favoriler", "♫", actions.onRadio),
            Destination("WEB MEDYA", "Sekmeli tarayıcı", "◎", actions.onWeb),
            Destination("FİLM/DİZİ KEŞFET", "TMDB bilgi ve afiş kataloğu", "◉", actions.onDiscover),
            Destination("KÜTÜPHANE", "M3U / XMLTV listeleri", "☷", actions.onLibrary),
            Destination("EV Cockpit DURUM", "Bağlantı raporu", "⌁", actions.onCarStatus),
            Destination("UYGULAMALAR", "FermataX ve diğerleri", "✧", actions.onCompanions)
        )
        items.chunked(columns).forEach { chunk ->
            val quick = row(activity).apply { gravity = Gravity.TOP }
            chunk.forEach { item ->
                val tile = card(activity)
                tile.isClickable = true
                tile.isFocusable = true
                tile.contentDescription = item.label + ". " + item.subtitle
                tile.setOnClickListener { item.action() }
                tile.addView(text(activity, item.symbol, 28f, ACCENT, true))
                tile.addView(vSpace(activity, 8))
                tile.addView(text(activity, item.label, 12f, INK, true))
                tile.addView(text(activity, item.subtitle, 11f, MUTED))
                val lp=LinearLayout.LayoutParams(0, dp(activity, 133), 1f)
                lp.setMargins(dp(activity, 2), 0, dp(activity, 5), 0)
                quick.addView(tile, lp)
            }
            repeat(columns - chunk.size) {
                quick.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            }
            root.addView(quick)
            root.addView(vSpace(activity, 8))
        }

        if (overview.recents.isNotEmpty()) {
            root.addView(vSpace(activity, 12))
            root.addView(sectionTitle(activity, "KALDIĞIN YERDEN", "En son açtığın kanallar ve radyolar"))
            root.addView(vSpace(activity, 10))
            root.addView(mediaRail(activity, overview.recents, epg, actions))
        }

        if (overview.favourites.isNotEmpty()) {
            root.addView(vSpace(activity, 20))
            root.addView(sectionTitle(activity, "FAVORİLERİN", "Hızlı başlatmak için seç"))
            root.addView(vSpace(activity, 10))
            root.addView(mediaRail(activity, overview.favourites, epg, actions))
        }

        val guide = card(activity)
        guide.addView(text(activity, "TV PROGRAM REHBERİ", 13f, ACCENT, true))
        guide.addView(vSpace(activity, 5))
        guide.addView(text(activity, "Şimdi, sonra ve program ilerlemesi", 17f, INK, true))
        guide.addView(vSpace(activity, 8))
        guide.addView(text(activity,
            "XMLTV rehberini yönet, hangi kanalda ne olduğunu incele.", 12f, MUTED))
        guide.addView(vSpace(activity, 10))
        guide.addView(actionButton(activity, "▦  Program rehberini aç", actions.onGuide))
        val guideLp=LinearLayout.LayoutParams(-1, -2)
        guideLp.topMargin=dp(activity, 18)
        root.addView(guide,guideLp)

        if (overview.tvCount == 0) {
            val empty = card(activity)
            empty.addView(text(activity, "İLK KURULUM", 12f, GOLD, true))
            empty.addView(text(activity, "Henüz TV listen yok", 16f, INK, true))
            empty.addView(text(activity,
                "Kitaplığa kendi M3U listen veya uygun bir açık yayın kaynağı ekleyebilirsin.",
                12f, MUTED))
            empty.addView(vSpace(activity, 9))
            empty.addView(actionButton(activity, "＋  Kaynak ekle", actions.onLibrary))
            val lp=LinearLayout.LayoutParams(-1, -2)
            lp.topMargin=dp(activity, 12)
            root.addView(empty, lp)
        }

        root.addView(vSpace(activity, 16))
        root.addView(text(activity,
            "ARAÇ MODU • Android Auto ses/radyo içindir. Video görüntüleme yalnızca " +
                "telefon veya uygun park halindeki ekranlarda kullanılır.",
            11f, MUTED))
        return root
    }

    private fun countBadge(activity: Activity, count: Int, label: String): LinearLayout =
        row(activity).apply {
            setPadding(dp(activity, 11),dp(activity, 10),dp(activity, 8),dp(activity, 10))
            background=background(activity, 0xff1e4453.toInt(),0xff1a394b.toInt(),13)
            addView(text(activity, count.toString(), 23f, INK, true))
            val t=text(activity, "  " + label, 12f, 0xffb8d5dd.toInt())
            addView(t)
        }

    private fun mediaRail(
        activity: Activity, entries: List<CockpitEntry>,
        epg: EpgScheduleIndex, actions: CockpitActions
    ): HorizontalScrollView = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled=false
        val cards=row(activity).apply { gravity=Gravity.TOP }
        entries.forEach { item ->
            val tile=card(activity)
            tile.isClickable=true
            tile.isFocusable=true
            tile.contentDescription="Aç: " + item.title
            tile.setOnClickListener { actions.onPlay(item) }
            val top=row(activity)
            val logo=ImageView(activity).apply {
                setImageResource(
                    if(item.kind=="tv") android.R.drawable.ic_media_play
                    else android.R.drawable.ic_media_ff)
                scaleType=ImageView.ScaleType.FIT_CENTER
            }
            top.addView(logo,LinearLayout.LayoutParams(dp(activity, 40),dp(activity, 40)))
            if(item.logo.isNotBlank()) ChannelLogoLoader.into(item.logo,logo,activity)
            val type=text(activity, if(item.kind=="tv")"CANLI TV" else "RADYO",
                10f,ACCENT,true)
            val tp=LinearLayout.LayoutParams(-2,-2)
            tp.leftMargin=dp(activity,9)
            top.addView(type,tp)
            tile.addView(top)
            tile.addView(vSpace(activity, 8))
            tile.addView(text(activity, item.title.take(70), 14f, INK, true).apply {
                maxLines=2
                ellipsize=android.text.TextUtils.TruncateAt.END
            })
            tile.addView(text(activity, item.subtitle.take(45), 11f, MUTED).apply {
                maxLines=1
                ellipsize=android.text.TextUtils.TruncateAt.END
            })
            // Radio never includes a TV programme status or fake progress.
            if(item.kind=="tv") {
                val programme = epg.current(item.tvgId)
                if(programme != null) {
                    tile.addView(vSpace(activity, 7))
                    tile.addView(text(activity, "ŞİMDİ • " + programme.title.take(42),
                        10f,GOLD).apply {
                        maxLines=1
                        ellipsize=android.text.TextUtils.TruncateAt.END
                    })
                    tile.addView(ProgressBar(activity,null,
                        android.R.attr.progressBarStyleHorizontal).apply {
                        max=100
                        progress=epg.progressPercent(item.tvgId)
                        progressTintList=android.content.res.ColorStateList.valueOf(ACCENT)
                    },LinearLayout.LayoutParams(-1,dp(activity,4)))
                }
            }
            val lp=LinearLayout.LayoutParams(dp(activity, 188), -2)
            lp.rightMargin=dp(activity, 9)
            cards.addView(tile,lp)
        }
        addView(cards,ViewGroup.LayoutParams(-2,-2))
    }
}
