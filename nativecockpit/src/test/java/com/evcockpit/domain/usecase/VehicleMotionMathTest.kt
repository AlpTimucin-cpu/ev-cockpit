package com.evcockpit.domain.usecase

import org.junit.Assert.*
import org.junit.Test
import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.GpsFix

class VehicleMotionMathTest {
    private val ni = GeoCoordinate(37.970,34.680)
    @Test fun cardinalBearings() {
        assertEquals(0.0, VehicleMotionMath.bearing(ni,GeoCoordinate(38.0,34.68))!!,0.1)
        assertEquals(90.0,VehicleMotionMath.bearing(ni,GeoCoordinate(37.970,34.71))!!,0.1)
    }
    @Test fun shortestTurnAcrossNorth() {
        assertEquals(0.0,VehicleMotionMath.lerpBearing(350.0,10.0,.5f)!!,0.01)
        assertEquals(0.0,VehicleMotionMath.lerpBearing(10.0,350.0,.5f)!!,0.01)
    }
    @Test fun datelineInterpolation() {
        val m=VehicleMotionMath.lerpPosition(GeoCoordinate(0.0,179.8),
            GeoCoordinate(0.0,-179.8),.5f)
        assertTrue(kotlin.math.abs(kotlin.math.abs(m.longitude)-180.0)<.001)
    }
    @Test fun rejectsOldFixAndImpossibleJump() {
        val a=GpsFix(ni,6f,100_000L)
        assertTrue(VehicleMotionMath.canAnimate(a,GpsFix(GeoCoordinate(37.9701,34.6801),5f,101_000L)))
        assertFalse(VehicleMotionMath.canAnimate(a,GpsFix(GeoCoordinate(37.9702,34.6802),5f,109_000L)))
        assertFalse(VehicleMotionMath.canAnimate(a,GpsFix(GeoCoordinate(38.020,34.680),5f,101_000L)))
        assertFalse(VehicleMotionMath.canAnimate(a,GpsFix(GeoCoordinate(37.9701,34.6801),5f,99_000L)))
    }
    @Test fun usesRealRouteHeadingOnlyWhenNearby() {
        val road=listOf(ni,GeoCoordinate(37.980,34.680))
        assertEquals(0.0,VehicleMotionMath.routeBearing(road,
            GeoCoordinate(37.975,34.680))!!,1.0)
        assertNull(VehicleMotionMath.routeBearing(road,GeoCoordinate(37.975,34.685)))
        assertNull(VehicleMotionMath.routeBearing(emptyList(),ni))
    }
    @Test fun unknownDirectionIsNull() {
        val stationary=GpsFix(ni,5f,100_000L)
        assertNull(VehicleMotionMath.observedHeading(stationary,null,emptyList()))
        assertEquals(93.0,VehicleMotionMath.observedHeading(
            stationary.copy(bearingDegrees=93f),null,emptyList())!!,.0001)
    }
}
