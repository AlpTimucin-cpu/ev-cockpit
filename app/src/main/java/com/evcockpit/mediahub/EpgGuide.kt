package com.evcockpit.mediahub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** APTV-inspired XMLTV EPG for user-provided HTTPS sources. No APTV code or services are used. */
data class EpgProgramme(val channelId: String, val title: String, val startMs: Long, val stopMs: Long)

object EpgGuide {
    private const val PREFS = "evcockpit_epg_v1"
    private const val KEY_SOURCE = "source"
    private const val KEY_SHOWS = "shows"
    private const val MAX_COMPRESSED_BYTES = 8_000_000
    private const val MAX_XML_BYTES = 24_000_000
    private const val MAX_PROGRAMMES = 6000
    private const val CACHE_LIMIT = 3500

    fun source(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SOURCE, "").orEmpty()

    private fun limited(input: InputStream, max: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= max) { "Program rehberi çok büyük" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /** Max 3 HTTPS redirects; decompress XMLTV .gz only within a strict expanded size limit. */
    fun downloadHttps(address: String): ByteArray {
        require(M3uParser.isHttpsUrl(address)) { "Geçerli HTTPS EPG adresi gerekli" }
        var location = URL(address)
        repeat(4) {
            val connection = (location.openConnection() as HttpsURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/xml, text/xml, application/gzip, */*")
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val target = connection.getHeaderField("Location") ?: error("Yönlendirme boş")
                    val next = URL(location, target)
                    require(next.protocol.equals("https", ignoreCase = true) && next.userInfo == null) {
                        "Yalnızca güvenli HTTPS yönlendirmesi kabul edilir"
                    }
                    location = next
                } else {
                    require(code == 200) { "EPG sunucu yanıtı: HTTP " + code }
                    val raw = connection.inputStream.use { limited(it, MAX_COMPRESSED_BYTES) }
                    if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                        return GZIPInputStream(ByteArrayInputStream(raw)).use { limited(it, MAX_XML_BYTES) }
                    }
                    return raw
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Çok fazla EPG yönlendirmesi")
    }

    private fun parseXmlTvDate(raw: String): Long? {
        val value = raw.trim()
        if (value.length < 14 || !value.substring(0, 14).all(Char::isDigit)) return null
        val suffix = value.substring(14).trim()
        val timezone = if (suffix.matches(Regex("[+-][0-9]{4}"))) suffix else "+0000"
        return try {
            SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(value.substring(0, 14) + " " + timezone)?.time
        } catch (_: Exception) { null }
    }

    /** Reject DTDs and any external entity resolution; cap parsed records and field lengths. */
    fun parse(bytes: ByteArray, nowMs: Long = System.currentTimeMillis()): List<EpgProgramme> {
        require(bytes.size <= MAX_XML_BYTES) { "EPG belge boyutu aşıldı" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            setExpandEntityReferences(false)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }
        val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
        require(root.tagName == "tv") { "XMLTV kök öğesi bulunamadı" }
        val programmes = root.getElementsByTagName("programme")
        val result = ArrayList<EpgProgramme>(minOf(programmes.length, MAX_PROGRAMMES))
        for (i in 0 until minOf(programmes.length, MAX_PROGRAMMES)) {
            val node = programmes.item(i) as? Element ?: continue
            val id = node.getAttribute("channel").trim().take(160)
            val start = parseXmlTvDate(node.getAttribute("start")) ?: continue
            val stop = parseXmlTvDate(node.getAttribute("stop")) ?: continue
            if (id.isBlank() || stop <= start || stop <= nowMs - 2L * 60 * 60 * 1000 ||
                start >= nowMs + 3L * 24 * 60 * 60 * 1000) continue
            val titleNodes: NodeList = node.getElementsByTagName("title")
            val title = titleNodes.item(0)?.textContent?.replace(Regex("[\u0000-\u001F]"), " ")
                ?.trim()?.take(140).orEmpty()
            if (title.isNotBlank()) result.add(EpgProgramme(id, title, start, stop))
        }
        return result.sortedWith(compareBy(EpgProgramme::startMs, EpgProgramme::channelId))
    }

    fun save(context: Context, address: String, programmes: List<EpgProgramme>): Int {
        require(M3uParser.isHttpsUrl(address)) { "Geçerli HTTPS adresi gerekli" }
        val encoded = JSONArray()
        programmes.take(CACHE_LIMIT).forEach { p ->
            encoded.put(JSONObject().put("c", p.channelId).put("t", p.title)
                .put("s", p.startMs).put("e", p.stopMs))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SOURCE, address).putString(KEY_SHOWS, encoded.toString()).apply()
        return encoded.length()
    }

    fun load(context: Context): List<EpgProgramme> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SHOWS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until minOf(array.length(), CACHE_LIMIT)) {
                    val o = array.optJSONObject(i) ?: continue
                    val id = o.optString("c").take(160)
                    val title = o.optString("t").take(140)
                    val start = o.optLong("s")
                    val end = o.optLong("e")
                    if (id.isNotBlank() && title.isNotBlank() && end > start)
                        add(EpgProgramme(id, title, start, end))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun current(programmes: List<EpgProgramme>, tvgId: String, name: String, nowMs: Long): EpgProgramme? {
        // XMLTV identifiers are defined by the playlist's tvg-id; names are unreliable.
        val identifier = tvgId.trim()
        if (identifier.isBlank()) return null
        return programmes.firstOrNull {
            it.channelId.equals(identifier, ignoreCase = true) && it.startMs <= nowMs && nowMs < it.stopMs
        }
    }

    fun next(programmes: List<EpgProgramme>, tvgId: String, nowMs: Long): EpgProgramme? =
        programmes.filter { it.channelId.equals(tvgId, true) && it.startMs > nowMs }
            .minByOrNull { it.startMs }

    fun clock(ms: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    fun currentLabel(context: Context, tvgId: String): String {
        val shows = load(context)
        val now = System.currentTimeMillis()
        val current = current(shows, tvgId, "", now) ?: return "EPG: Bu kanal için yayın bilgisi yok"
        val following = next(shows, tvgId, now)
        return "Şimdi " + clock(current.startMs) + "–" + clock(current.stopMs) +
            " · " + current.title +
            (if (following != null) "\nSonra " + clock(following.startMs) +
                " · " + following.title else "")
    }
}
