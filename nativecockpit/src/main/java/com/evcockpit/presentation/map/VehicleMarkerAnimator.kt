package com.evcockpit.presentation.map

import android.animation.TypeEvaluator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.animation.LinearInterpolator
import com.google.gson.JsonObject
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.usecase.VehicleMotionMath

private const val CAR_SOURCE = "eya-gps-car-source"
private const val CAR_LAYER = "eya-gps-car-direction"
private const val DOT_LAYER = "eya-gps-unknown-heading"
private const val CAR_IMAGE = "eya-gps-car-arrow"

/** Main-thread MapLibre owner. Updates one GeoJsonSource, never a second map.
 * No dead reckoning: the animator stops at the last actually measured position.
 */
internal class VehicleMarkerAnimator {
    private var attachedStyle: Style? = null
    private var previous: GpsFix? = null
    private var drawn: GeoCoordinate? = null
    private var drawnAngle: Double? = null
    private var animation: ValueAnimator? = null

    fun update(style: Style, fix: GpsFix?, route: List<GeoCoordinate>, now: Long) {
        if(attachedStyle !== style){
            animation?.cancel();animation=null
            attachedStyle=style;previous=null;drawn=null;drawnAngle=null
            ensureLayers(style)
        }
        if(fix == null || !fix.usable(now)) {
            clear();return
        }
        val old=previous
        val newAngle=VehicleMotionMath.observedHeading(fix,old,route)
        if(old?.recordedAtMillis==fix.recordedAtMillis) {
            // The route may have changed without a new GPS fix.
            if(animation==null) show(fix.coordinate,newAngle)
            return
        }
        if(old!=null && fix.recordedAtMillis<old.recordedAtMillis) return
        previous=fix
        animation?.cancel();animation=null
        if(old==null || !VehicleMotionMath.canAnimate(old,fix)) {
            // Never sweep the car across an implausible GPS jump.
            show(fix.coordinate,newAngle)
            return
        }
        val from=drawn ?: old.coordinate
        val previousAngle=drawnAngle
        val travel=fix.recordedAtMillis-old.recordedAtMillis
        val evaluator=TypeEvaluator<GeoCoordinate>{fraction,a,b ->
            VehicleMotionMath.lerpPosition(a,b,fraction)
        }
        val smooth=ValueAnimator.ofObject(evaluator,from,fix.coordinate).apply {
            duration=(travel*0.85).toLong().coerceIn(250L,1100L)
            interpolator=LinearInterpolator()
            addUpdateListener { animator ->
                val position=animator.animatedValue as? GeoCoordinate ?: return@addUpdateListener
                val heading=VehicleMotionMath.lerpBearing(previousAngle,newAngle,
                    animator.animatedFraction)
                show(position,heading)
            }
        }
        animation=smooth
        smooth.start()
    }

    /** Lifecycle, rejected fix, or permission revocation: no phantom car pin. */
    fun clear(){
        animation?.cancel();animation=null
        previous=null;drawn=null;drawnAngle=null
        attachedStyle?.getSourceAs<GeoJsonSource>(CAR_SOURCE)
            ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
    }

    fun release(){clear();attachedStyle=null}

    private fun show(point:GeoCoordinate,heading:Double?){
        if(!point.valid())return
        drawn=point;drawnAngle=heading
        val props=JsonObject().apply {
            addProperty("hasHeading",heading!=null)
            addProperty("heading",heading ?: 0.0)
        }
        val feature=Feature.fromGeometry(Point.fromLngLat(point.longitude,point.latitude),props)
        attachedStyle?.getSourceAs<GeoJsonSource>(CAR_SOURCE)
            ?.setGeoJson(FeatureCollection.fromFeature(feature))
    }

    private fun ensureLayers(style:Style){
        if(style.getSource(CAR_SOURCE)!=null)return
        style.addSource(GeoJsonSource(CAR_SOURCE,
            FeatureCollection.fromFeatures(emptyArray<Feature>())))
        style.addImage(CAR_IMAGE,carBitmap())
        // A vehicle-shaped arrow points north for heading=0°, map aligned.
        style.addLayer(SymbolLayer(CAR_LAYER,CAR_SOURCE)
            .withFilter(Expression.eq(Expression.get("hasHeading"),Expression.literal(true)))
            .withProperties(iconImage(CAR_IMAGE),iconSize(.63f),
                iconRotate(Expression.get("heading")),
                iconRotationAlignment("map"),iconAllowOverlap(true),iconIgnorePlacement(true)))
        // When route and GPS cannot determine heading, use a neutral dot.
        style.addLayer(CircleLayer(DOT_LAYER,CAR_SOURCE)
            .withFilter(Expression.eq(Expression.get("hasHeading"),Expression.literal(false)))
            .withProperties(circleRadius(9f),circleColor(Color.rgb(0,230,118)),
                circleStrokeColor(Color.WHITE),circleStrokeWidth(2f)))
    }

    private fun carBitmap():Bitmap{
        val bitmap=Bitmap.createBitmap(96,112,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(0,230,118)}
        val arrow=Path().apply {
            moveTo(48f,5f);lineTo(84f,72f);lineTo(61f,65f)
            lineTo(61f,104f);lineTo(35f,104f);lineTo(35f,65f)
            lineTo(12f,72f);close()
        }
        canvas.drawPath(arrow,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=4f;paint.color=Color.WHITE
        canvas.drawPath(arrow,paint)
        return bitmap
    }
}
