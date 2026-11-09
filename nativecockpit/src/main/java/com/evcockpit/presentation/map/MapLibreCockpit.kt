package com.evcockpit.presentation.map

import androidx.compose.ui.res.stringResource
import com.evcockpit.R

import android.content.ComponentCallbacks2
import android.util.Log
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path
import com.google.gson.JsonObject
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.model.ChargingStation
import com.evcockpit.domain.model.GeoViewport
import com.evcockpit.domain.model.WeatherWaypoint
import com.evcockpit.domain.model.ProfileSpeedSegment
import com.evcockpit.domain.usecase.StationCorridor
import com.evcockpit.domain.model.validRouteGeometry

private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
private const val FALLBACK_STYLE = "https://tiles.openfreemap.org/styles/fiord"
private const val ROUTE_SRC = "eya-route-source"
private const val ROUTE_LAYER = "eya-route-line"
private const val STATION_SRC = "eya-station-source"
private const val STATION_DC_OVERVIEW_SRC = "evc-dc-overview-src"
private const val STATION_DC_OVERVIEW_LAYER = "evc-dc-overview-points"
private const val STATION_CORRIDOR_SRC = "eya-route-dc-corridor-source"
private const val STATION_ALL_LAYER = "eya-station-all"
private const val STATION_RING_LAYER = "eya-station-route-outline"
private const val STATION_PLANNED_LAYER = "eya-station-plan-outline"
private const val STATION_DC_LAYER = "eya-station-dc-icons"
private const val STATION_ICON = "eya-station-bolt"
private const val STATION_CLUSTER_LAYER = "eya-station-cluster-circles"
private const val STATION_CLUSTER_COUNT_LAYER = "eya-station-cluster-count"
private const val SPEED_SRC = "eya-osrm-speed-profile"
private const val SPEED_SLOW = "eya-osrm-speed-slow"
private const val SPEED_NORMAL = "eya-osrm-speed-normal"
private const val WEATHER_SRC = "eya-weather-route"
private const val WEATHER_LAYER = "eya-weather-markers"

/** One MapView for this composition; Compose owns transparent cockpit overlays. */
@Composable
fun MapLibreCockpit(
    gpsFix: GpsFix?,
    routePoints: List<GeoCoordinate>,
    stations: List<ChargingStation>,
    plannedStopIds: Set<String> = emptySet(),
    weatherPoints: List<WeatherWaypoint>,
    speedProfile: List<ProfileSpeedSegment> = emptyList(),
    onViewportChanged: (GeoViewport) -> Unit,
    onStationTapped: (ChargingStation) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    // Map callback must never capture stale Compose lists/lambdas.
    val stationIndex = remember(stations) { stations.associateBy { it.id } }
    val dcStationCount = remember(stations) { stations.count { it.hasDc } }
    val latestStationIndex by rememberUpdatedState(stationIndex)
    val latestViewport by rememberUpdatedState(onViewportChanged)
    val latestTap by rememberUpdatedState(onStationTapped)
    var mapStatus by remember { mutableStateOf("Loading map…") }
    var mapObject by remember { mutableStateOf<MapLibreMap?>(null) }
    var activeStyle by remember { mutableStateOf<Style?>(null) }
    var fallback by remember { mutableStateOf(false) }
    var centeredOnRealGps by remember { mutableStateOf(false) }
    var fittedRouteKey by remember { mutableStateOf<Int?>(null) }
    // This object follows the lifecycle of the ONE MapView.
    val carAnimator = remember(context) { VehicleMarkerAnimator() }

    // MapLibre SDK needs initialization BEFORE MapView is constructed.
    val view = remember(context) {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).also { it.onCreate(null) }
    }

    DisposableEffect(view, owner) {
        var started = false
        var resumed = false
        fun start() { if (!started) { view.onStart(); started = true } }
        fun resume() { start(); if (!resumed) { view.onResume(); resumed = true } }
        fun pause() {
            carAnimator.clear() // no animation while backgrounded
            if (resumed) { view.onPause(); resumed = false }
        }
        fun stop() { pause(); if (started) { view.onStop(); started = false } }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> pause()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        else if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        val callbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(config: Configuration) = Unit
            override fun onLowMemory() { view.onLowMemory() }
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) view.onLowMemory()
            }
        }
        context.registerComponentCallbacks(callbacks)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(callbacks)
            stop()
            carAnimator.release()
            view.onDestroy() // exactly once, on Composition disposal
        }
    }

    DisposableEffect(view) {
        var idleListener: MapLibreMap.OnCameraIdleListener? = null
        var clickListener: MapLibreMap.OnMapClickListener? = null
        var callbackMap: MapLibreMap? = null
        val failed = MapView.OnDidFailLoadingMapListener { message ->
            if (!fallback) {
                fallback = true
                mapStatus = "Retrying the dark map…"
                mapObject?.setStyle(Style.Builder().fromUri(FALLBACK_STYLE)) { style ->
                    activeStyle = style
                    mapStatus = "Map ready · Fiord fallback style"
                }
            } else {
                mapStatus = "Map connection failed · route not drawn"
            }
        }
        view.addOnDidFailLoadingMapListener(failed)
        view.getMapAsync { map ->
            callbackMap = map
            mapObject = map
            map.uiSettings.isAttributionEnabled = true
            map.uiSettings.isLogoEnabled = true
            fun publishViewport() {
                val bounds=map.projection.visibleRegion.latLngBounds
                latestViewport(GeoViewport(bounds.southWest.latitude,bounds.southWest.longitude,
                    bounds.northEast.latitude,bounds.northEast.longitude))
            }
            idleListener = MapLibreMap.OnCameraIdleListener { publishViewport() }
            map.addOnCameraIdleListener(idleListener!!)
            clickListener = MapLibreMap.OnMapClickListener { point ->
                val clusters = map.queryRenderedFeatures(map.projection.toScreenLocation(point),
                    STATION_CLUSTER_LAYER)
                val cluster = clusters.firstOrNull { it.hasProperty("cluster_id") }
                if (cluster != null) {
                    val source = map.style?.getSourceAs<GeoJsonSource>(STATION_SRC)
                    val expansion = try { source?.getClusterExpansionZoom(cluster)?.toDouble() }
                        catch (_: Exception) { null }
                    val center = (cluster.geometry() as? Point)?.let {
                        LatLng(it.latitude(), it.longitude())
                    } ?: point
                    val zoom = (expansion ?: map.cameraPosition.zoom + 2.0)
                        .coerceAtLeast(map.cameraPosition.zoom + 1.0)
                        .coerceAtMost(17.0)
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(center, zoom))
                    return@OnMapClickListener true
                }
                val hits=map.queryRenderedFeatures(map.projection.toScreenLocation(point),
                    STATION_PLANNED_LAYER,STATION_DC_LAYER,STATION_DC_OVERVIEW_LAYER,STATION_RING_LAYER,STATION_ALL_LAYER)
                val id=hits.firstOrNull()?.let { f ->
                    if(f.hasProperty("stationId")) f.getStringProperty("stationId") else null
                }
                val station=latestStationIndex[id]
                if(station!=null) { latestTap(station); true } else false
            }
            map.addOnMapClickListener(clickListener!!)
            // Türkiye panoraması yalnız kamera başlangıcıdır; GPS değildir.
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(39.9334, 32.8597)).zoom(5.2).build()
            map.setStyle(Style.Builder().fromUri(DARK_STYLE)) { style ->
                activeStyle = style
                mapStatus = "Map ready · OpenFreeMap"
                publishViewport()
            }
        }
        onDispose {
            callbackMap?.let { m ->
                idleListener?.let { m.removeOnCameraIdleListener(it) }
                clickListener?.let { m.removeOnMapClickListener(it) }
            }
            view.removeOnDidFailLoadingMapListener(failed)
        }
    }

    // Show only actual OSRM geometry, fit once per selected alternative.
    LaunchedEffect(activeStyle, routePoints) {
        activeStyle?.let {
            updateRoute(it, routePoints)
            ensureRouteOverlayZOrder(it)
        }
        if (activeStyle != null && validRouteGeometry(routePoints)) {
            val signature=routePoints.hashCode()
            if(fittedRouteKey!=signature){
                val b=LatLngBounds.Builder()
                routePoints.forEach { b.include(LatLng(it.latitude,it.longitude)) }
                try { mapObject?.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 76)) }
                catch (_:Exception) { /* keep real geometry if fit is unsupported */ }
                fittedRouteKey=signature
            }
        }
        if(routePoints.isEmpty())fittedRouteKey=null
    }
    LaunchedEffect(activeStyle, stations, routePoints, plannedStopIds) {
        val style = activeStyle ?: return@LaunchedEffect
        // Serialize the 13k+ stations off the UI thread; the source clusters them.
        val geo = withContext(Dispatchers.Default) {
            buildStationGeoJson(stations, routePoints, plannedStopIds)
        }
        updateStations(style, geo)
        ensureRouteOverlayZOrder(style)
        Log.d("EYA-MAP-UAT", "stationSource=" + (style.getSourceAs<GeoJsonSource>(STATION_SRC) != null) +
            " dcRingLayer=" + (style.getLayer(STATION_RING_LAYER) != null))
    }
    LaunchedEffect(activeStyle, speedProfile) {
        activeStyle?.let {
            updateSpeedProfile(it, speedProfile)
            ensureRouteOverlayZOrder(it)
        }
    }
    LaunchedEffect(activeStyle, weatherPoints) {
        activeStyle?.let {
            updateWeather(it, weatherPoints)
            ensureRouteOverlayZOrder(it)
        }
    }
    LaunchedEffect(activeStyle, gpsFix, routePoints) {
        activeStyle?.let { carAnimator.update(it, gpsFix, routePoints, System.currentTimeMillis()) }
        if (!centeredOnRealGps && activeStyle != null &&
            gpsFix?.usable(System.currentTimeMillis()) == true) {
            mapObject?.moveCamera(CameraUpdateFactory.newLatLngZoom(
                LatLng(gpsFix.coordinate.latitude, gpsFix.coordinate.longitude), 13.5))
            centeredOnRealGps = true // later movement animation is Step 5
        }
    }

    Box(modifier.background(Color(0xFF0A0F1A))) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        if (!mapStatus.startsWith("Map ready")) {
            Text(mapStatus, modifier = Modifier.align(Alignment.Center)
                .background(Color(0xDF0B2032), RoundedCornerShape(12.dp))
                .padding(14.dp), color = Color.White, fontSize = 12.sp)
        }
        if (stations.isNotEmpty()) {
            Text(
                "EPDK · ${stations.size} istasyon · $dcStationCount DC",
                color = Color(0xFF39FF88), fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart)
                    .navigationBarsPadding().padding(start = 12.dp, bottom = 76.dp)
                    .background(Color(0xEA081320), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            )
        }
        // Explicit provider attribution additionally to MapLibre's native attribution.
        Text(stringResource(R.string.native_openfreemap_openstreetmap),
            color = Color(0xFFD6E2EB), fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                .padding(bottom = 72.dp, end = 16.dp)
                .background(Color(0xB9091B2A), RoundedCornerShape(6.dp))
                .clickable {
                    val uri = android.net.Uri.parse("https://www.openstreetmap.org/copyright")
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                    try { context.startActivity(intent) } catch (_: Exception) { }
                }.padding(horizontal = 7.dp, vertical = 3.dp))
    }
}

/**
 * MapLibre's last-added layer wins. Weather/EPDK arrive on independent Flows,
 * so addLayerAbove only at creation is insufficient after style/source refresh.
 * Reorder *existing app-owned layers* every time any overlay source updates.
 *
 * Bottom -> top: route, OSRM profile speeds, charging markers, weather icons.
 * No tile/provider layers, MapLibre resources or data sources are replaced.
 */
private fun ensureRouteOverlayZOrder(style: Style) {
    if (style.getLayer(ROUTE_LAYER) == null) return

    var below = ROUTE_LAYER
    val overlayOrder = listOf(
        SPEED_NORMAL, SPEED_SLOW,
        STATION_DC_OVERVIEW_LAYER, STATION_CLUSTER_LAYER, STATION_CLUSTER_COUNT_LAYER,
        STATION_ALL_LAYER, STATION_RING_LAYER, STATION_PLANNED_LAYER,
        STATION_DC_LAYER, WEATHER_LAYER
    )
    // Avoid repeated native remove/re-add cycles when the asynchronous map
    // updates do not actually change the visible layer order.
    val existingOrder = style.layers.map { it.id }
    if (OverlayZOrder.isCorrect(existingOrder, ROUTE_LAYER, overlayOrder)) return
    overlayOrder.forEach { id ->
        val layer = style.getLayer(id) ?: return@forEach
        // The Layer overload transfers native ownership back to this instance.
        // Removing by ID invalidates the instance and cannot be re-added.
        if (!style.removeLayer(layer)) return@forEach
        style.addLayerAbove(layer, below)
        below = id
    }
    Log.d("EYA-MAP-UAT", "overlayZOrder repaired; top=$below aboveRoute=$ROUTE_LAYER")
}

private fun updateRoute(style: Style, points: List<GeoCoordinate>) {
    val valid = validRouteGeometry(points)
    val geo = if (valid) {
        val line = LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })
        FeatureCollection.fromFeature(Feature.fromGeometry(line))
    } else FeatureCollection.fromFeatures(emptyArray<Feature>())
    val source = style.getSourceAs<GeoJsonSource>(ROUTE_SRC)
    if (source != null) source.setGeoJson(geo)
    else {
        style.addSource(GeoJsonSource(ROUTE_SRC, geo))
        style.addLayer(LineLayer(ROUTE_LAYER, ROUTE_SRC).withProperties(
            lineColor(android.graphics.Color.rgb(0, 176, 255)), lineWidth(5f)
        ))
    }
}

/** Feature properties carry only station IDs; details come from verified repository. */
private fun buildStationGeoJson(
    stations: List<ChargingStation>, route: List<GeoCoordinate>,
    plannedStopIds: Set<String>
): FeatureCollection {
    val routeIndex=if(route.size>=2) StationCorridor.index(route) else null
    val features=stations.map { station ->
        val props=JsonObject().apply {
            addProperty("stationId",station.id)
            addProperty("dc",station.hasDc)
            addProperty("corridor",routeIndex?.isDcAlongRoute(station)==true)
            addProperty("proposedStop",station.id in plannedStopIds)
        }
        Feature.fromGeometry(Point.fromLngLat(station.coordinate.longitude,
            station.coordinate.latitude),props)
    }
    val dcAlong = features.count { it.getBooleanProperty("corridor") }
    Log.d("EYA-MAP-UAT", "routeVertices=" + route.size +
        " stations=" + stations.size + " corridorDc5km=" + dcAlong +
        " proposed=" + plannedStopIds.size)
    return FeatureCollection.fromFeatures(features)
}

private fun updateStations(style: Style, geo: FeatureCollection) {
    // Route-DC rings are NOT clustered; they remain visible at overview zoom.
    val corridorFeatures = geo.features().orEmpty().filter { feature ->
        feature.hasProperty("corridor") && feature.getBooleanProperty("corridor")
    }
    val corridorGeo = FeatureCollection.fromFeatures(corridorFeatures)
    style.getSourceAs<GeoJsonSource>(STATION_CORRIDOR_SRC)?.setGeoJson(corridorGeo)
        ?: style.addSource(GeoJsonSource(STATION_CORRIDOR_SRC, corridorGeo))
    Log.d("EYA-MAP-UAT", "unclusteredCorridorDc=" + corridorFeatures.size)
    // Individual DC dots remain visible without a calculated route.
    val dcGeo = FeatureCollection.fromFeatures(geo.features().orEmpty().filter {
        it.hasProperty("dc") && it.getBooleanProperty("dc")
    })
    val overviewSource = style.getSourceAs<GeoJsonSource>(STATION_DC_OVERVIEW_SRC)
    if (overviewSource == null) {
        style.addSource(GeoJsonSource(STATION_DC_OVERVIEW_SRC, dcGeo))
        style.addLayer(CircleLayer(STATION_DC_OVERVIEW_LAYER, STATION_DC_OVERVIEW_SRC)
            .withProperties(
                circleColor(android.graphics.Color.rgb(57, 255, 136)),
                circleRadius(2.9f),
                circleOpacity(0.65f),
                circleStrokeWidth(0f)
            ))
    } else overviewSource.setGeoJson(dcGeo)
    val existing=style.getSourceAs<GeoJsonSource>(STATION_SRC)
    if(existing!=null){existing.setGeoJson(geo);return}
    // Clustering belongs to the data source: thousands of points do not become
    // thousands of on-screen circles at low zoom levels.
    val options = GeoJsonOptions()
        .withCluster(true)
        .withClusterRadius(50)
        .withClusterMaxZoom(14)
    style.addSource(GeoJsonSource(STATION_SRC, geo, options))
    style.addImage(STATION_ICON, lightningBitmap())
    val isCluster = Expression.has("point_count")
    val isSingle = Expression.not(isCluster)
    style.addLayer(CircleLayer(STATION_CLUSTER_LAYER, STATION_SRC)
        .withFilter(isCluster)
        .withProperties(
            circleColor(android.graphics.Color.rgb(18, 125, 87)),
            circleRadius(21f),
            circleStrokeWidth(2f),
            circleStrokeColor(android.graphics.Color.WHITE)
        ))
    style.addLayer(SymbolLayer(STATION_CLUSTER_COUNT_LAYER, STATION_SRC)
        .withFilter(isCluster)
        .withProperties(
            textField(Expression.get("point_count_abbreviated")),
            textColor(android.graphics.Color.WHITE),
            textSize(13f),
            textAllowOverlap(true)
        ))
    style.addLayer(CircleLayer(STATION_ALL_LAYER, STATION_SRC)
        .withFilter(isSingle)
        .withProperties(
            circleColor(android.graphics.Color.rgb(48, 206, 126)),
            circleRadius(5f),
            circleOpacity(.72f),
            circleStrokeWidth(1f),
            circleStrokeColor(android.graphics.Color.WHITE)
        ))
    style.addLayer(CircleLayer(STATION_RING_LAYER, STATION_CORRIDOR_SRC)
        .withFilter(Expression.eq(Expression.get("corridor"), Expression.literal(true)))
        .withProperties(
            circleRadius(15f),
            circleColor(android.graphics.Color.TRANSPARENT),
            circleStrokeWidth(3f),
            circleStrokeColor(android.graphics.Color.rgb(16, 235, 235))
        ))
    // Do not claim these candidates are verified reachable driving detours.
    style.addLayer(CircleLayer(STATION_PLANNED_LAYER, STATION_SRC)
        .withFilter(Expression.all(isSingle,
            Expression.eq(Expression.get("proposedStop"), Expression.literal(true))))
        .withProperties(
            circleRadius(19f),
            circleColor(android.graphics.Color.TRANSPARENT),
            circleStrokeWidth(4f),
            circleStrokeColor(android.graphics.Color.rgb(255, 194, 67))
        ))
    val chargingLayer = SymbolLayer(STATION_DC_LAYER, STATION_SRC)
        .withFilter(Expression.all(isSingle,
            Expression.eq(Expression.get("dc"), Expression.literal(true))))
        .withProperties(
            iconImage(STATION_ICON), iconSize(.50f), iconAllowOverlap(false)
        )
    // The route ID in this project is "eya-route-line", not "route-layer".
    // On first insertion put DC symbols above the route immediately.
    // ensureRouteOverlayZOrder also repositions them after async source updates.
    if (style.getLayer(ROUTE_LAYER) != null) style.addLayerAbove(chargingLayer, ROUTE_LAYER)
    else style.addLayer(chargingLayer)
}

/** Generate a vector-like bolt locally; no external icon/font/license dependency. */
private fun lightningBitmap(): Bitmap {
    val bmp=Bitmap.createBitmap(72,82,Bitmap.Config.ARGB_8888)
    val canvas=AndroidCanvas(bmp)
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style=Paint.Style.FILL;color=android.graphics.Color.rgb(0,230,118)
    }
    val path=Path().apply {
        moveTo(39f,3f);lineTo(14f,43f);lineTo(34f,43f)
        lineTo(25f,79f);lineTo(61f,30f);lineTo(42f,30f);close()
    }
    canvas.drawPath(path,paint)
    return bmp
}

private fun updateWeather(style: Style, samples: List<WeatherWaypoint>) {
    val features = samples.filter { it.coordinate.valid() }.map { item ->
        val props = JsonObject().apply {
            addProperty("weatherIcon", "eya-weather-" + item.iconKey)
            addProperty("tempText", item.temperatureC.toInt().toString() + "°C")
        }
        Feature.fromGeometry(
            Point.fromLngLat(item.coordinate.longitude, item.coordinate.latitude), props
        )
    }
    val data = FeatureCollection.fromFeatures(features)
    val source = style.getSourceAs<GeoJsonSource>(WEATHER_SRC)
    Log.d("EYA-MAP-UAT", "weatherPoints=" + features.size +
        " routeLayer=" + (style.getLayer(ROUTE_LAYER) != null))
    if (source != null) {
        source.setGeoJson(data)
    } else {
        style.addSource(GeoJsonSource(WEATHER_SRC, data))
        listOf("sun", "cloud", "rain").forEach { kind ->
            style.addImage("eya-weather-" + kind, weatherBitmap(kind))
        }
        val weatherLayer = SymbolLayer(WEATHER_LAYER, WEATHER_SRC).withProperties(
            iconImage(Expression.get("weatherIcon")),
            iconSize(0.8f),
            iconAllowOverlap(true),
            textField(Expression.get("tempText")),
            textSize(12f),
            textColor(android.graphics.Color.WHITE),
            textHaloColor(android.graphics.Color.rgb(4, 28, 45)),
            textHaloWidth(2f),
            textAllowOverlap(true)
        )
        if (style.getLayer(ROUTE_LAYER) != null) style.addLayerAbove(weatherLayer, ROUTE_LAYER)
        else style.addLayer(weatherLayer)
        Log.d("EYA-MAP-UAT", "weatherLayer=" + (style.getLayer(WEATHER_LAYER) != null))
    }
}

/** Draw weather pictograms locally; no extra tile, image or font requests. */
private fun weatherBitmap(type: String): Bitmap {
    val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.rgb(4, 28, 45)
    canvas.drawCircle(24f, 24f, 22f, paint)
    paint.color = if (type == "sun") android.graphics.Color.rgb(255, 194, 67)
        else android.graphics.Color.rgb(200, 227, 245)
    if (type == "sun") {
        canvas.drawCircle(24f, 24f, 11f, paint)
    } else {
        canvas.drawOval(12f, 19f, 36f, 32f, paint)
        canvas.drawCircle(20f, 19f, 8f, paint)
        canvas.drawCircle(28f, 19f, 7f, paint)
        if (type == "rain") {
            paint.color = android.graphics.Color.rgb(42, 182, 238)
            paint.strokeWidth = 3f
            canvas.drawLine(17f, 34f, 13f, 41f, paint)
            canvas.drawLine(30f, 34f, 26f, 41f, paint)
        }
    }
    return bitmap
}


/** OSRM PROFILE speeds, never real-time traffic or measured congestion. */
private fun updateSpeedProfile(style: Style, segments: List<ProfileSpeedSegment>) {
    val features = segments.asSequence()
        .filter { it.from.valid() && it.to.valid() &&
            it.speedKmh.isFinite() && it.speedKmh in 0.0..300.0 }
        .take(19_999)
        .map { segment ->
            val props = JsonObject().apply {
                addProperty("slow", segment.speedKmh < 30.0)
            }
            Feature.fromGeometry(
                LineString.fromLngLats(listOf(
                    Point.fromLngLat(segment.from.longitude, segment.from.latitude),
                    Point.fromLngLat(segment.to.longitude, segment.to.latitude)
                )), props
            )
        }.toList()
    val geo = FeatureCollection.fromFeatures(features)
    val src = style.getSourceAs<GeoJsonSource>(SPEED_SRC)
    Log.d("EYA-MAP-UAT", "OSRM profileSpeedSegments=" + features.size + " LIVE_TRAFFIC=false")
    if (src != null) { src.setGeoJson(geo); return }
    style.addSource(GeoJsonSource(SPEED_SRC, geo))
    val normal = LineLayer(SPEED_NORMAL, SPEED_SRC).withFilter(
        Expression.eq(Expression.get("slow"), Expression.literal(false))
    ).withProperties(
        lineColor(android.graphics.Color.rgb(69, 201, 130)),
        lineWidth(3f)
    )
    if (style.getLayer(ROUTE_LAYER) != null) style.addLayerAbove(normal, ROUTE_LAYER)
    else style.addLayer(normal)
    val slow = LineLayer(SPEED_SLOW, SPEED_SRC).withFilter(
        Expression.eq(Expression.get("slow"), Expression.literal(true))
    ).withProperties(
        lineColor(android.graphics.Color.rgb(236, 89, 87)),
        lineWidth(3f)
    )
    style.addLayerAbove(slow, SPEED_NORMAL)
    Log.d("EYA-MAP-UAT", "speedLayers normal=" + (style.getLayer(SPEED_NORMAL) != null) +
        " slow=" + (style.getLayer(SPEED_SLOW) != null))
}
