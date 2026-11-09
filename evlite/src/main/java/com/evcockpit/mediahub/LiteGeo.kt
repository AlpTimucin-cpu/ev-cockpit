package com.evcockpit.mediahub

import android.net.Uri
import java.util.Locale

/**
 * Mapping requires an installed navigation engine; no embedded MapLibre renderer
 * or UI-blocking map initialization in the lite release.
 */
object TurkeyGeoServices {
    data class Place(val title:String,val lat:Double,val lon:Double) {
        fun coordinates():String = String.format(Locale.US,"%.6f,%.6f",lat,lon)
    }
    data class Station(val osmId:String,val title:String,val lat:Double,val lon:Double,
        val operator:String,val ccs:String,val type2:String,val rawPower:String,
        val source:String="EPDK") {
        fun asPlace()=Place(title,lat,lon)
    }
    data class Bounds(val south:Double,val west:Double,val north:Double,val east:Double) {
        fun isSmallEnough() = south<north && west<east
    }
    fun mapsDirections(lat:Double,lon:Double): Uri {
        val coordinate=String.format(Locale.US,"%.7f,%.7f",lat,lon)
        return Uri.parse("https://www.google.com/maps/dir/?api=1&destination="+coordinate+"&travelmode=driving")
    }
}
