package com.evcockpit.presentation.map

import android.graphics.BitmapFactory
import com.evcockpit.data.traffic.TomTomTrafficState
import com.evcockpit.data.traffic.TomTomFlowTile
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/** MapLibre source geometry for a provider PNG tile; no invented traffic speeds. */
internal object TomTomTileQuad {
    fun from(tile: TomTomFlowTile): LatLngQuad {
        val n = (1 shl tile.zoom).toDouble()
        fun lat(y: Int): Double =
            Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / n))))
        fun lon(x: Int): Double = x / n * 360.0 - 180.0
        return LatLngQuad(
            LatLng(lat(tile.y), lon(tile.x)),
            LatLng(lat(tile.y), lon(tile.x + 1)),
            LatLng(lat(tile.y + 1), lon(tile.x + 1)),
            LatLng(lat(tile.y + 1), lon(tile.x))
        )
    }
}

/**
 * Identical phone and Android Auto map rendering. Only approved, fresh
 * TomTom Orbis PNG tiles are georeferenced through an ImageSource.
 * No URL/key is passed to MapLibre or stored in a map style.
 */
internal object TomTomTrafficMapRenderer {
    private const val FLOW_IMAGE = "evc-tomtom-flow-image-"
    private const val FLOW_LAYER = "evc-tomtom-flow-layer-"
    private const val INCIDENT_SOURCE = "evc-tomtom-incidents-source"
    private const val INCIDENT_LAYER = "evc-tomtom-incidents-layer"
    private const val MAX_TILES = 6

    fun apply(style: Style, snapshot: TomTomTrafficState, routeId: String?, nowMs: Long,
        baseLayer: String? = null
    ) {
        // First remove old app-owned layers, then data sources; stale data must
        // disappear even when disconnected or when the user's consent is revoked.
        style.getLayer(INCIDENT_LAYER)?.let { style.removeLayer(INCIDENT_LAYER) }
        style.getSource(INCIDENT_SOURCE)?.let { style.removeSource(INCIDENT_SOURCE) }
        for (i in 0 until MAX_TILES) {
            style.getLayer(FLOW_LAYER + i)?.let { style.removeLayer(FLOW_LAYER + i) }
            style.getSource(FLOW_IMAGE + i)?.let { style.removeSource(FLOW_IMAGE + i) }
        }
        if (!snapshot.freshFor(routeId, nowMs)) return
        var below = baseLayer?.takeIf { style.getLayer(it) != null }
        snapshot.flowTiles.take(MAX_TILES).forEachIndexed { i, tile ->
            if (tile.zoom !in 0..22 || tile.x < 0 || tile.y < 0 ||
                tile.x >= (1 shl tile.zoom) || tile.y >= (1 shl tile.zoom)) return@forEachIndexed
            val bitmap = BitmapFactory.decodeByteArray(tile.png, 0, tile.png.size)
                ?.takeIf { it.width == 256 && it.height == 256 } ?: return@forEachIndexed
            val sourceId = FLOW_IMAGE + i
            val layerId = FLOW_LAYER + i
            style.addSource(ImageSource(sourceId, TomTomTileQuad.from(tile), bitmap))
            val layer = RasterLayer(layerId, sourceId).withProperties(rasterOpacity(0.82f))
            if (below != null) style.addLayerAbove(layer, below) else style.addLayer(layer)
            below = layerId
        }
        val features = snapshot.incidents.asSequence()
            .filter { it.coordinate.valid() }
            .take(120)
            .map {
                Feature.fromGeometry(Point.fromLngLat(
                    it.coordinate.longitude, it.coordinate.latitude
                ))
            }.toList()
        if (features.isNotEmpty()) {
            style.addSource(GeoJsonSource(
                INCIDENT_SOURCE, FeatureCollection.fromFeatures(features)
            ))
            val layer = CircleLayer(INCIDENT_LAYER, INCIDENT_SOURCE).withProperties(
                circleRadius(6f),
                circleColor(android.graphics.Color.rgb(255, 176, 45)),
                circleStrokeWidth(2f),
                circleStrokeColor(android.graphics.Color.rgb(11, 26, 42))
            )
            if (below != null) style.addLayerAbove(layer, below) else style.addLayer(layer)
        }
    }
}
