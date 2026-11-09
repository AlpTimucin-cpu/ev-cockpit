package com.evcockpit.domain.model

/** A turn instruction and coordinate MUST originate in a real routing engine.
 * Original OSRM fields allow the car host to receive a semantically correct icon.
 */
data class NavigationManeuver(
    val id: String,
    val instructionTr: String,
    val location: GeoCoordinate,
    val osrmType: String? = null,
    val osrmModifier: String? = null
)
