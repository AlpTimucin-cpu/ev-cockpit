package com.evcockpit.mediahub

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Original EV Cockpit Drive Hub dashboard, inspired by the INFORMATION ARCHITECTURE
 * (not copied assets or source code) of FermataX: resume -> source tiles ->
 * recent -> capabilities. Phone UI; Android Auto gets its audio library.
 */
class DriveDashboard(private val activity: Activity, private val actions: Actions) {
    data class Actions(
        val openTv: () -> Unit,
        val openRadio: () -> Unit,
        val openLibrary: () -> Unit,
        val openWeb: () -> Unit,
        val openYoutube: () -> Unit,
        val openNetflix: () -> Unit,
        val openIntegrations: () -> Unit,
        val openCarReport: () -> Unit,
        val playTv: (VideoChannel) -> Unit,
        val playRadio: (Station) -> Unit,
        val toggleRadio: () -> Unit
    )

    private val bg = 0xff091525.toInt()
    private val panel = 0xff13263d.toInt()
    private val panel2 = 0xff192f48.toInt()
    private val muted = 0xffa9bed0.toInt()
    private val aqua = 0xff7ce2d9.toInt()
    private val amber = 0xffffd297.toInt()
    private val white = Color.WHITE

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun shape(vararg shades: Int, radius: Int = 18): GradientDrawable {
        val drawable = if (shades.size > 1) {
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR, shades
            )
        } else {
            GradientDrawable().apply { setColor(shades.firstOrNull() ?: panel) }
        }
        drawable.cornerRadius = dp(radius).toFloat()
        return drawable
    }

    private fun label(value: String, size: Float, color: Int = white,
                      strong: Boolean = false): TextView =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = true
            if (strong) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun card(padding: Int = 16, vararg colors: Int): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
            background = shape(*(if (colors.isEmpty()) intArrayOf(panel) else colors))
        }

    private fun space(view: LinearLayout, height: Int = 14) {
        view.addView(android.view.View(activity), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun margin(bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }

    private fun button(title: String, accent: Boolean = false,
                       onClick: () -> Unit): TextView =
        label(title, 14f, if (accent) bg else white, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = shape(if (accent) aqua else 0xff31516a.toInt(), radius = 12)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    fun render(
        parent: LinearLayout,
        tv: List<VideoChannel>,
        radio: List<Station>,
        history: List<String>,
        currentRadioId: String?,
        radioPlaying: Boolean
    ) {
        parent.removeAllViews()
        parent.setBackgroundColor(bg)

        val items = DriveHomePlanner.recent(
            history, tv.associate { it.id to it.name },
            radio.associate { it.id to it.name }
        )
        val live = radio.firstOrNull { it.id == currentRadioId }

        val hero = card(19, 0xff174c59.toInt(), 0xff1b3050.toInt()).apply {
            setPadding(dp(18), dp(17), dp(18), dp(19))
        }
        val brandRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val glyph = label("ID⁴", 28f, aqua, true).apply {
            gravity = Gravity.CENTER
            background = shape(0xff0b293c.toInt(), radius = 14)
        }
        brandRow.addView(glyph, LinearLayout.LayoutParams(dp(70), dp(62)))
        val brandText = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        brandText.addView(label("DRIVE HUB", 22f, white, true))
        brandText.addView(label("VOLKSWAGEN  •  MEDIA CONTROL", 10f, aqua, true))
        brandRow.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(brandRow)
        space(hero, 16)
        hero.addView(label("Tek ekran. Tüm medyan.", 21f, white, true))
        space(hero, 6)
        hero.addView(label(
            "TV, radyo, web ve medya kaynaklarını tek noktadan yönet.",
            13f, 0xffd2e5ed.toInt()
        ))
        space(hero, 17)
        val chips = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        chips.addView(chip(tv.size.toString(), "TV KANALI"),
            LinearLayout.LayoutParams(0, dp(65), 1f))
        chips.addView(chip(radio.size.toString(), "RADYO"),
            LinearLayout.LayoutParams(0, dp(65), 1f).apply { leftMargin = dp(8) })
        val favourites = tv.count { it.favorite } + radio.count { it.favorite }
        chips.addView(chip(favourites.toString(), "FAVORİ"),
            LinearLayout.LayoutParams(0, dp(65), 1f).apply { leftMargin = dp(8) })
        hero.addView(chips)
        parent.addView(hero, margin(16))

        val now = card(15, 0xff1b334e.toInt(), 0xff11263e.toInt())
        now.addView(label(
            if (radioPlaying && live != null) "●  ŞİMDİ DİNLENİYOR" else "↺  KALDIĞIN YERDEN",
            11f, if (radioPlaying && live != null) aqua else amber, true
        ))
        space(now, 9)
        when {
            radioPlaying && live != null -> {
                now.addView(label(live.name, 20f, white, true))
                now.addView(label("Radyo • " + live.group, 12f, muted))
                space(now, 12)
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(button("Ⅱ  DURAKLAT", true, actions.toggleRadio),
                    LinearLayout.LayoutParams(0, dp(48), 1f))
                row.addView(button("RADYOYA GİT", false, actions.openRadio),
                    LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
                now.addView(row)
            }
            items.isNotEmpty() -> {
                val item = items.first()
                now.addView(label(item.title, 20f, white, true))
                now.addView(label(
                    if (item.kind == "tv") "Son izlenen TV kanalı" else "Son dinlenen radyo",
                    12f, muted
                ))
                space(now, 12)
                now.addView(button("▶  DEVAM ET", true) { play(item, tv, radio) })
            }
            else -> {
                now.addView(label("Henüz oynatma geçmişi yok", 17f, white, true))
                now.addView(label("Kanal ekle veya radyoyu aç; son yayın burada görünecek.", 12f, muted))
                space(now, 12)
                now.addView(button("＋  KAYNAK EKLE", true, actions.openLibrary))
            }
        }
        parent.addView(now, margin(19))

        section(parent, "MEDYA MERKEZİ", "Kaynağını seç")
        val topRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(sourceTile(
            "▣", "CANLI TV", "IPTV • EPG\nKanalların", aqua, actions.openTv
        ), LinearLayout.LayoutParams(0, dp(136), 1f))
        topRow.addView(sourceTile(
            "♫", "RADYO", "Türkiye radyoları\nFavorilerin", amber, actions.openRadio
        ), LinearLayout.LayoutParams(0, dp(136), 1f).apply { leftMargin = dp(10) })
        parent.addView(topRow, margin(10))
        val bottomRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        bottomRow.addView(sourceTile(
            "◎", "WEB MEDYA", "Sekmeler • Video\nWeb tarayıcı", 0xffc0c7ff.toInt(), actions.openWeb
        ), LinearLayout.LayoutParams(0, dp(136), 1f))
        bottomRow.addView(sourceTile(
            "☷", "KİTAPLIĞIM", "M3U • Rehber\nKaynak yönetimi", 0xffabefbd.toInt(), actions.openLibrary
        ), LinearLayout.LayoutParams(0, dp(136), 1f).apply { leftMargin = dp(10) })
        parent.addView(bottomRow, margin(17))

        section(parent, "HIZLI BAĞLANTILAR", "Telefon uygulamaları")
        val services = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        services.addView(button("▶  YouTube", false, actions.openYoutube),
            LinearLayout.LayoutParams(0, dp(48), 1f))
        services.addView(button("▣  Netflix", false, actions.openNetflix),
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        parent.addView(services, margin(18))

        if (items.size > 1) {
            section(parent, "SON AÇILANLAR", "Kayıtlar yalnızca telefonunda")
            val scroll = HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
            }
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            items.drop(1).take(4).forEach { item ->
                val tile = card(12, panel2)
                tile.addView(label(if (item.kind == "tv") "▣  TV" else "♫  RADYO",
                    11f, aqua, true))
                space(tile, 8)
                tile.addView(label(item.title, 13f, white, true))
                tile.isClickable = true
                tile.isFocusable = true
                tile.setOnClickListener { play(item, tv, radio) }
                row.addView(tile, LinearLayout.LayoutParams(dp(167), dp(88)).apply {
                    rightMargin = dp(9)
                })
            }
            scroll.addView(row)
            parent.addView(scroll, margin(20))
        }

        section(parent, "EV Cockpit BAĞLANTI MERKEZİ", "Cihaz ve oynatıcı tanılama")
        val connection = card(16, 0xff24344a.toInt(), 0xff122638.toInt())
        connection.addView(label("ARAÇ BAĞLANTISI", 12f, aqua, true))
        space(connection, 5)
        connection.addView(label("Android Auto + medya servisleri", 16f, white, true))
        connection.addView(label(
            "Telefon ve araç ayrı doğrulanır. Android Auto'da ses kataloğu; video yalnızca desteklenen park senaryolarında.",
            12f, muted
        ))
        space(connection, 12)
        connection.addView(button("ARAÇ TANILAMA  →", false, actions.openCarReport))
        space(connection, 8)
        connection.addView(button("Tüm uygulamaları ve bağlantıları yönet", false, actions.openIntegrations))
        parent.addView(connection, margin(20))
    }

    private fun chip(value: String, caption: String): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = shape(0x77365869, radius = 10)
            addView(label(value, 20f, white, true).apply { gravity = Gravity.CENTER })
            addView(label(caption, 9f, 0xffc6dedf.toInt(), true).apply { gravity = Gravity.CENTER })
        }

    private fun section(parent: LinearLayout, title: String, subtitle: String) {
        parent.addView(label(title, 16f, white, true))
        parent.addView(label(subtitle, 12f, muted), margin(10))
    }

    private fun sourceTile(icon: String, title: String, subtitle: String, color: Int,
                           onClick: () -> Unit): LinearLayout =
        card(13, panel2, panel).apply {
            addView(label(icon, 28f, color, true))
            space(this, 5)
            addView(label(title, 15f, white, true))
            space(this, 4)
            addView(label(subtitle, 11f, muted))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun play(item: DriveRecentItem, tv: List<VideoChannel>, radio: List<Station>) {
        when (item.kind) {
            "tv" -> tv.firstOrNull { it.id == item.id }?.let(actions.playTv)
            "radio" -> radio.firstOrNull { it.id == item.id }?.let(actions.playRadio)
        }
    }
}
