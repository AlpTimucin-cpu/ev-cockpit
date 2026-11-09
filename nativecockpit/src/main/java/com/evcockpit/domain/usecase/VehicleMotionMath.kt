package com.evcockpit.domain.usecase

import kotlin.math.*
import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.GpsFix

/** Pure geometry. Values reflect two observed GPS fixes, never extrapolated motion. */
object VehicleMotionMath {
    private const val EARTH_METERS = 6_371_000.0

    fun distanceMeters(a: GeoCoordinate, b: GeoCoordinate): Double {
        if (!a.valid() || !b.valid()) return Double.NaN
        val dLat = Math.toRadians(b.latitude-a.latitude)
        val dLon = Math.toRadians(b.longitude-a.longitude)
        val h = sin(dLat/2).pow(2.0) + cos(Math.toRadians(a.latitude)) *
            cos(Math.toRadians(b.latitude)) * sin(dLon/2).pow(2.0)
        return 2 * EARTH_METERS * asin(sqrt(h.coerceIn(0.0,1.0)))
    }

    /** Geodetic initial heading, 0° north, 90° east. */
    fun bearing(a: GeoCoordinate, b: GeoCoordinate): Double? {
        if (!a.valid() || !b.valid() || distanceMeters(a,b)<.3) return null
        val lat1=Math.toRadians(a.latitude)
        val lat2=Math.toRadians(b.latitude)
        val dLon=Math.toRadians(b.longitude-a.longitude)
        val east=sin(dLon)*cos(lat2)
        val north=cos(lat1)*sin(lat2)-sin(lat1)*cos(lat2)*cos(dLon)
        return ((Math.toDegrees(atan2(east,north))%360)+360)%360
    }

    fun lerpPosition(a: GeoCoordinate, b: GeoCoordinate, t: Float): GeoCoordinate {
        val fraction=t.coerceIn(0f,1f).toDouble()
        // Dateline-safe longitude interpolation.
        val delta=((b.longitude-a.longitude+540)%360)-180
        val lon=a.longitude+fraction*delta
        return GeoCoordinate(a.latitude+(b.latitude-a.latitude)*fraction,
            ((lon+540)%360)-180)
    }

    fun lerpBearing(a: Double?, b: Double?, fraction: Float): Double? {
        if (a==null) return b
        if (b==null) return a
        val shortest=((b-a+540)%360)-180
        return ((a+shortest*fraction.coerceIn(0f,1f)+360)%360)
    }

    /** Disallow smoothing across stale fixes, implausible jumps or GPS drift. */
    fun canAnimate(previous: GpsFix, current: GpsFix): Boolean {
        if (!previous.usable(current.recordedAtMillis) ||
            !current.coordinate.valid() || current.accuracyMeters>40f ||
            previous.accuracyMeters>40f) return false
        val gap=current.recordedAtMillis-previous.recordedAtMillis
        if (gap !in 250L..6_000L) return false
        val meters=distanceMeters(previous.coordinate,current.coordinate)
        if (!meters.isFinite() || meters<=.4 || meters>180.0) return false
        // Maximum credible GPS delta: 60 m/s (~216 km/h), not an ETA.
        return meters*1000/gap <=60.0
    }

    /** Only derive route heading if a genuine GPS fix is within 40 m of its geometry. */
    fun routeBearing(
        route: List<GeoCoordinate>, pos: GeoCoordinate,
        maxLateralMeters: Double = 40.0
    ): Double? {
        if (!pos.valid() || route.size !in 2..20_000) return null
        val centerLat=Math.toRadians(pos.latitude)
        val metersLon=111_320.0*cos(centerLat)
        val metersLat=111_195.0
        var closest=Double.POSITIVE_INFINITY
        var best:Double?=null
        for (i in 0 until route.lastIndex) {
            val a=route[i]; val b=route[i+1]
            if (!a.valid() || !b.valid()) continue
            val ax=(a.longitude-pos.longitude)*metersLon
            val ay=(a.latitude-pos.latitude)*metersLat
            val dx=(b.longitude-a.longitude)*metersLon
            val dy=(b.latitude-a.latitude)*metersLat
            val squared=dx*dx+dy*dy
            if (squared<1) continue
            val t=(-(ax*dx+ay*dy)/squared).coerceIn(0.0,1.0)
            val distance=hypot(ax+t*dx,ay+t*dy)
            if (distance<closest){closest=distance;best=bearing(a,b)}
        }
        return if(closest<=maxLateralMeters) best else null
    }

    /** Preference: actual road geometry; otherwise measured GPS bearing,
     * or a direction inferred from TWO observed fixes. Never dummy north. */
    fun observedHeading(fix: GpsFix, previous: GpsFix?, route: List<GeoCoordinate>): Double? {
        routeBearing(route,fix.coordinate)?.let { return it }
        fix.bearingDegrees?.takeIf { it.isFinite() && it>=0f && it<360f }
            ?.let { return it.toDouble() }
        if(previous!=null && canAnimate(previous,fix) &&
            distanceMeters(previous.coordinate,fix.coordinate)>5.0) {
            return bearing(previous.coordinate,fix.coordinate)
        }
        return null
    }
}
