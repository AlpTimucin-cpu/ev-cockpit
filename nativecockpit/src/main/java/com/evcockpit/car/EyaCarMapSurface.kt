package com.evcockpit.car

import android.app.Presentation
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Surface
import android.widget.FrameLayout
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import com.evcockpit.domain.model.ChargingStation
import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.model.RouteSnapshot
import com.evcockpit.domain.model.validRouteGeometry

/**
 * Renders MapLibre to the host Surface, never a browser/phone-screen mirror.
 * No MapView is created if Android Auto has not supplied a valid Surface.
 */
internal class EyaCarMapSurface(private val context: CarContext) : SurfaceCallback {
    private var activeSurface: Surface? = null
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var pendingRoute: RouteSnapshot? = null
    private var pendingGps: GpsFix? = null
    private var pendingChargingStations: List<ChargingStation> = emptyList()
    private var appliedChargingSignature: String? = null
    private var appliedRoute: String? = null
    private var appliedGpsAt: Long? = null
    private var centeredOnGps = false
    private var viewport: CarMapViewport? = null
    private var visibleArea: CarMapArea? = null
    private var stableArea: CarMapArea? = null
    private var lastPadding: CarMapInsets? = null
    // Guards delayed MapLibre callbacks after Android Auto replaces a Surface.
    private var surfaceGeneration = 0L

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val hostSurface = surfaceContainer.surface ?: return
        val width = surfaceContainer.width
        val height = surfaceContainer.height
        val dpi = surfaceContainer.dpi
        if (!hostSurface.isValid || width <= 0 || height <= 0 || dpi <= 0) {
            // Every incoming Surface instance must eventually be released.
            if (hostSurface !== activeSurface) hostSurface.release()
            Log.w("EyaCarMap", "Invalid host surface: size or DPI unavailable")
            return
        }
        val updated = CarMapViewport(width, height, dpi, visibleArea, stableArea)
        if (activeSurface === hostSurface && display != null) {
            if (viewport == updated) return
            viewport = updated
            lastPadding = null
            val generation = surfaceGeneration
            try {
                display?.resize(width, height, dpi)
                updateLayerSizes()
                fitCamera()
                mapView?.post {
                    if (generation == surfaceGeneration) fitCamera()
                }
            } catch (error: RuntimeException) {
                Log.w("EyaCarMap", "Surface resize failed", error)
                release()
            }
            return
        }
        release()
        activeSurface = hostSurface
        viewport = updated
        val generation = ++surfaceGeneration
        try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val virtual = dm.createVirtualDisplay(
                "EV Cockpit – Navigation",
                width, height, dpi,
                hostSurface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            ) ?: run {
                Log.e("EyaCarMap", "Android Auto VirtualDisplay unavailable")
                release()
                return
            }
            display = virtual
            val popup = Presentation(context, virtual.display)
            presentation = popup
            MapLibre.getInstance(context.applicationContext)
            val view = MapView(popup.context)
            mapView = view
            view.onCreate(null)
            val frame = FrameLayout(popup.context)
            frame.addView(view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            popup.setContentView(frame)
            popup.show()
            view.onStart()
            view.onResume()
            view.addOnLayoutChangeListener { _, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom ->
                if (generation == surfaceGeneration &&
                    (right - left != oldRight - oldLeft ||
                        bottom - top != oldBottom - oldTop)
                ) fitCamera()
            }
            view.getMapAsync { ready ->
                if (generation != surfaceGeneration || mapView !== view) return@getMapAsync
                map = ready
                ready.uiSettings.isAttributionEnabled = true
                ready.uiSettings.isLogoEnabled = true
                // Overview only; never label this fixed center as measured GPS.
                ready.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(39.0, 35.0)).zoom(5.2).build()
                ready.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/dark")) { loaded ->
                    if (generation != surfaceGeneration || mapView !== view) return@setStyle
                    style = loaded
                    appliedRoute = null
                    appliedGpsAt = null
                    appliedChargingSignature = null
                    lastPadding = null
                    render()
                    fitCamera()
                }
            }
        } catch (error: RuntimeException) {
            Log.w("EyaCarMap", "Car surface failed", error)
            release()
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea.takeUnless { it.isEmpty }?.let {
            CarMapArea(it.left, it.top, it.right, it.bottom)
        }
        updateSafeViewport()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        this.stableArea = stableArea.takeUnless { it.isEmpty }?.let {
            CarMapArea(it.left, it.top, it.right, it.bottom)
        }
        updateSafeViewport()
    }

    private fun updateSafeViewport() {
        val before = viewport ?: return
        val after = before.copy(visible = visibleArea, stable = stableArea)
        viewport = after
        if (before.cameraInsets() != after.cameraInsets()) fitCamera()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        // A stale callback for a replaced surface cannot destroy the new one.
        if (surfaceContainer.surface === activeSurface) release()
    }

    fun update(route: RouteSnapshot?, fix: GpsFix?) {
        pendingRoute = route
        pendingGps = fix?.takeIf { it.usable(System.currentTimeMillis()) }
        render()
    }

    /** Draw only EPDK-proven DC/CCS coordinates; never an invented POI pin. */
    fun updateChargingStations(stations: List<ChargingStation>) {
        pendingChargingStations = stations.filter { it.hasDcCcs && it.coordinate.valid() }
            .take(12)
        renderCharging()
    }

    private fun renderCharging() {
        val ready = style ?: return
        val signature = pendingChargingStations.joinToString("|") { station ->
            station.id + ":" + station.coordinate.latitude + ":" + station.coordinate.longitude
        }
        if (appliedChargingSignature == signature) return
        val features = pendingChargingStations.map { station ->
            Feature.fromGeometry(Point.fromLngLat(
                station.coordinate.longitude, station.coordinate.latitude
            ))
        }.toTypedArray()
        val collection = FeatureCollection.fromFeatures(features)
        val src = ready.getSourceAs<GeoJsonSource>("eya-car-chargers")
        if (src == null) {
            ready.addSource(GeoJsonSource("eya-car-chargers", collection))
            ready.addLayer(CircleLayer("eya-car-charger-pins", "eya-car-chargers")
                .withProperties(
                    circleColor(android.graphics.Color.rgb(255, 193, 7)),
                    circleRadius(viewport?.stylePixels(6f) ?: 6f),
                    circleStrokeWidth(viewport?.stylePixels(2f) ?: 2f),
                    circleStrokeColor(android.graphics.Color.WHITE)
                ))
        } else src.setGeoJson(collection)
        appliedChargingSignature = signature
    }

    private fun render() {
        val ready = style ?: return
        renderCharging()
        val route = pendingRoute?.routePoints?.takeIf(::validRouteGeometry)
        val signature = pendingRoute?.routeId ?: route?.hashCode()?.toString() ?: "empty"
        if (appliedRoute != signature) {
            val collection = if (route == null)
                FeatureCollection.fromFeatures(emptyArray<Feature>())
            else FeatureCollection.fromFeature(Feature.fromGeometry(
                LineString.fromLngLats(route.map { Point.fromLngLat(it.longitude,it.latitude) })))
            val src = ready.getSourceAs<GeoJsonSource>("eya-car-route")
            if (src == null) {
                ready.addSource(GeoJsonSource("eya-car-route", collection))
                ready.addLayer(LineLayer("eya-car-route-line","eya-car-route")
                    .withProperties(lineColor(android.graphics.Color.rgb(0,176,255)),lineWidth(viewport?.stylePixels(5f) ?: 5f)))
            } else src.setGeoJson(collection)
            if (route != null) fitCamera()
            appliedRoute = signature
        }
        val fix = pendingGps?.takeIf { it.usable(System.currentTimeMillis()) }
        val stamp = fix?.recordedAtMillis
        if (appliedGpsAt != stamp) {
            val collection = if (fix == null)
                FeatureCollection.fromFeatures(emptyArray<Feature>())
            else FeatureCollection.fromFeature(Feature.fromGeometry(
                Point.fromLngLat(fix.coordinate.longitude,fix.coordinate.latitude)))
            val src = ready.getSourceAs<GeoJsonSource>("eya-car-gps")
            if (src == null) {
                ready.addSource(GeoJsonSource("eya-car-gps",collection))
                ready.addLayer(CircleLayer("eya-car-marker","eya-car-gps")
                    .withProperties(circleColor(android.graphics.Color.rgb(0,230,118)),
                        circleRadius(viewport?.stylePixels(9f) ?: 9f),
                        circleStrokeWidth(viewport?.stylePixels(3f) ?: 3f),
                        circleStrokeColor(android.graphics.Color.WHITE)))
            } else src.setGeoJson(collection)
            if (fix != null && !centeredOnGps && route == null) fitCamera()
            appliedGpsAt = stamp
        }
    }


    /** Resize SVG-like line/marker paint using host DPI, not the phone density. */
    private fun updateLayerSizes() {
        val ready = style ?: return
        val v = viewport ?: return
        ready.getLayerAs<LineLayer>("eya-car-route-line")
            ?.setProperties(lineWidth(v.stylePixels(5f)))
        ready.getLayerAs<CircleLayer>("eya-car-marker")?.setProperties(
            circleRadius(v.stylePixels(9f)),
            circleStrokeWidth(v.stylePixels(3f))
        )
        ready.getLayerAs<CircleLayer>("eya-car-charger-pins")?.setProperties(
            circleRadius(v.stylePixels(6f)),
            circleStrokeWidth(v.stylePixels(2f))
        )
    }

    /** Reframe only valid route geometry or fresh measured GPS in the host-safe area. */
    private fun fitCamera() {
        val ready = map ?: return
        val view = mapView ?: return
        if (view.width <= 0 || view.height <= 0) return
        val padding = viewport?.cameraInsets() ?: return
        val points = pendingRoute?.routePoints?.takeIf(::validRouteGeometry)
        try {
            if (points != null && points.size >= 2) {
                val bounds = LatLngBounds.Builder()
                points.forEach { bounds.include(LatLng(it.latitude, it.longitude)) }
                // Per-edge bounds padding replaces the old fixed 65-pixel value.
                ready.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0))
                ready.moveCamera(CameraUpdateFactory.newLatLngBounds(
                    bounds.build(), padding.left, padding.top, padding.right, padding.bottom
                ))
                centeredOnGps = false
            } else {
                val fix = pendingGps?.takeIf { it.usable(System.currentTimeMillis()) } ?: return
                ready.moveCamera(CameraUpdateFactory.paddingTo(
                    padding.left.toDouble(), padding.top.toDouble(),
                    padding.right.toDouble(), padding.bottom.toDouble()
                ))
                ready.moveCamera(CameraUpdateFactory.newLatLngZoom(
                    LatLng(fix.coordinate.latitude, fix.coordinate.longitude), 13.5
                ))
                centeredOnGps = true
            }
            lastPadding = padding
        } catch (error: RuntimeException) {
            lastPadding = null
            Log.w("EyaCarMap", "Safe viewport fit failed; retry on next update", error)
        }
    }

    fun release() {
        surfaceGeneration++ // Invalidate asynchronous map/style callbacks first.
        style = null
        map = null
        appliedGpsAt = null
        appliedRoute = null
        appliedChargingSignature = null
        pendingChargingStations = emptyList()
        centeredOnGps = false
        lastPadding = null
        viewport = null
        mapView?.let { view ->
            try { view.onPause();view.onStop();view.onDestroy() }
            catch (e: RuntimeException) { Log.w("EyaCarMap", "MapView cleanup", e) }
        }
        mapView = null
        try { presentation?.dismiss() } catch (_: Exception) { }
        presentation = null
        try { display?.release() } catch (_: Exception) { }
        display = null
        // SurfaceCallback requires each received Surface instance be released.
        val previous = activeSurface
        activeSurface = null
        previous?.release()
    }
}
