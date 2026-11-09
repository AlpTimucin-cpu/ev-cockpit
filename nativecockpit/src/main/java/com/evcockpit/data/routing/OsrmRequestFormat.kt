package com.evcockpit.data.routing

import java.util.Locale
import com.evcockpit.domain.model.GeoCoordinate

/** OSRM uses longitude,latitude separated by a literal ';'. */
object OsrmRequestFormat {
    private const val ROOT = "route/v1/driving/"
    private const val PARAMETERS = "?alternatives=true&steps=true&geometries=geojson&overview=full&annotations=speed"

    fun coordinates(origin: GeoCoordinate, destination: GeoCoordinate): String {
        require(origin.valid() && destination.valid()) { "Invalid coordinates" }
        require(origin != destination) { "Origin and destination cannot be the same" }
        fun point(value: GeoCoordinate): String = String.format(
            Locale.US, "%.6f,%.6f", value.longitude, value.latitude
        )
        return "${point(origin)};${point(destination)}"
    }

    fun url(origin: GeoCoordinate, destination: GeoCoordinate): String =
        ROOT + coordinates(origin, destination) + PARAMETERS

    /** One to five genuine, user-selected charging stop coordinates.
     * The route visits them in order; no synthetic GPS or straight-line segment.
     */
    /** Minimal OSRM geometry-free check: only two road-leg distances are read. */
    fun urlDetour(
        origin: GeoCoordinate,
        station: GeoCoordinate,
        destination: GeoCoordinate
    ): String = urlVia(origin, listOf(station), destination).substringBefore('?') +
        "?alternatives=false&steps=false&overview=false"

    fun urlVia(
        origin: GeoCoordinate,
        stops: List<GeoCoordinate>,
        destination: GeoCoordinate
    ): String {
        require(stops.size <= 5) { "En fazla beş şarj durağı eklenebilir" }
        require(origin.valid() && destination.valid() && stops.all { it.valid() }) {
            "Geçersiz şarj durağı koordinatı"
        }
        if (stops.isEmpty()) return url(origin, destination)
        val points = listOf(origin) + stops + destination
        require(points.zipWithNext().all { (a, b) -> a != b }) {
            "Aynı koordinata iki kez durak eklenemez"
        }
        val coords = points.joinToString(";") {
            String.format(Locale.US, "%.6f,%.6f", it.longitude, it.latitude)
        }
        return ROOT + coords + PARAMETERS.replace("alternatives=true", "alternatives=false")
    }

}
