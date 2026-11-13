package com.evcockpit.data.traffic

import com.evcockpit.domain.model.GeoCoordinate
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal class TomTomHttpException(val status: Int) : IOException("TomTom HTTP status $status")
internal data class TomTomHttpPayload(val bytes: ByteArray, val responseDateMs: Long?)

/**
 * Fixed provider origin in production. MockWebServer origin is allowed only
 * when constructing this internal client in unit tests.
 * Keys are ONLY transmitted as a header; no URL, key logger, or redirect.
 */
internal class TomTomTrafficHttp(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
    private val baseUrl: String = "https://api.tomtom.com"
) {
    private val base = baseUrl.toHttpUrl()

    private fun get(urlPath: String, query: Map<String, String>,
        key: String, mime: String, limitBytes: Int
    ): TomTomHttpPayload {
        require(TomTomKeySyntax.accepted(key))
        val builder = base.newBuilder().addPathSegments(urlPath)
        query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        val request = Request.Builder().url(builder.build())
            .header("TomTom-Api-Key", key)
            .header("TomTom-Api-Version", "2")
            .header("Accept", mime)
            .apply {
                if (mime == "application/json") {
                    header("Attributes",
                        "incidents(type,geometry(type,coordinates)," +
                            "properties(id,iconCategory,magnitudeOfDelay," +
                            "events(description,iconCategory),timeValidity,lastReportTime))")
                    header("Accept-Language", "tr-TR")
                }
            }.get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw TomTomHttpException(response.code)
            val type = response.body?.contentType()?.toString().orEmpty()
            if (!type.startsWith(mime)) throw IOException("TomTom içerik türü geçersiz")
            val body = response.body ?: throw IOException("TomTom boş gövde")
            if (body.contentLength() > limitBytes) throw IOException("TomTom gövde sınırı")
            val result = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    if (result.size() + n > limitBytes) throw IOException("TomTom gövde sınırı")
                    result.write(buffer, 0, n)
                }
            }
            val date = response.header("Date")?.let {
                runCatching {
                    ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli()
                }.getOrNull()
            }
            return TomTomHttpPayload(result.toByteArray(), date)
        }
    }

    fun incidents(key: String, bbox: TomTomBounds): TomTomHttpPayload {
        require(bbox.valid())
        return get("maps/orbis/traffic/incidents/details",
            mapOf("apiVersion" to "2", "bbox" to bbox.query(),
                "timeValidity" to "present"), key, "application/json", 750_000)
    }

    fun flowTile(key: String, tile: TomTomTileId): TomTomHttpPayload =
        get("maps/orbis/traffic/flow/raster/tile/${tile.z}/${tile.x}/${tile.y}",
            mapOf("apiVersion" to "2", "style" to "dark", "tileSize" to "256"),
            key, "image/png", 350_000)
}

/** Strict GeoJSON parsing; unknown/unsupported formats cannot become map incidents. */
internal object TomTomIncidentParser {
    private val allowed = setOf("accident","jam","roadWorks","roadClosed",
        "laneClosed","fog","dangerousConditions","rain","ice","wind",
        "flooding","brokenDownVehicle","unknown")
    fun parse(bytes: ByteArray, nearbyRoute: List<GeoCoordinate>): List<TomTomIncident> {
        val root = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
        val entries = root.get("incidents")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw IOException("TomTom olay listesi bulunamadı")
        if (entries.size() > 500) throw IOException("TomTom olay sayısı sınırı")
        return entries.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val incident = item.asJsonObject
            val properties = incident.get("properties")?.takeIf { it.isJsonObject }
                ?.asJsonObject ?: return@mapNotNull null
            val category = properties.string("iconCategory")
                ?.takeIf { it in allowed } ?: return@mapNotNull null
            if (properties.string("timeValidity") != "present") return@mapNotNull null
            val geometry = incident.get("geometry")?.takeIf { it.isJsonObject }
                ?.asJsonObject ?: return@mapNotNull null
            val points = when (geometry.string("type")) {
                "Point" -> listOfNotNull(coordinate(geometry.getAsJsonArray("coordinates")))
                "LineString" -> geometry.get("coordinates")?.takeIf { it.isJsonArray }
                    ?.asJsonArray?.take(300)?.mapNotNull { p ->
                        coordinate(p.takeIf { it.isJsonArray }?.asJsonArray)
                    }.orEmpty()
                else -> emptyList()
            }
            if (points.isEmpty()) return@mapNotNull null
            val point = points.minByOrNull {
                TomTomCorridorPolicy.kmToRoute(it, nearbyRoute)
            } ?: return@mapNotNull null
            if (TomTomCorridorPolicy.kmToRoute(point, nearbyRoute) > 3.0)
                return@mapNotNull null
            val events = properties.get("events")?.takeIf { it.isJsonArray }?.asJsonArray
            val message = events?.firstOrNull()?.takeIf { it.isJsonObject }
                ?.asJsonObject?.string("description")
                ?.replace(Regex("[\\r\\n\\t]"), " ")?.take(100).orEmpty()
            val reported = properties.string("lastReportTime")?.let {
                runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            }
            TomTomIncident(point, category, message, reported)
        }.take(120)
    }

    private fun JsonObject.string(name: String): String? = runCatching {
        get(name)?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()
    private fun coordinate(c: JsonArray?): GeoCoordinate? = try {
        if (c == null || c.size() != 2) null
        else GeoCoordinate(c[1].asDouble, c[0].asDouble).takeIf { it.valid() }
    } catch (_: RuntimeException) { null }
}
