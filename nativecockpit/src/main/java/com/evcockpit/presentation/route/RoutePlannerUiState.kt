package com.evcockpit.presentation.route

import com.evcockpit.domain.model.*

data class RoutePlannerUiState(
    val originText: String = "",
    val destinationText: String = "",
    val originSelected: RoutePlace? = null,
    val destinationSelected: RoutePlace? = null,
    val originFromGps: Boolean = false,
    val gpsOrigin: GeoCoordinate? = null,
    val originMatches: List<RoutePlace> = emptyList(),
    val destinationMatches: List<RoutePlace> = emptyList(),
    val options: List<RouteOption> = emptyList(),
    val selectedIndex: Int? = null,
    val searchingOrigin: Boolean = false,
    val searchingDestination: Boolean = false,
    val locatingGps: Boolean = false,
    val planning: Boolean = false,
    val error: String? = null
) {
    val startLocation: GeoCoordinate?
        get() = (if (originFromGps) gpsOrigin else originSelected?.coordinate)?.takeIf { it.valid() }
    val destinationLocation: GeoCoordinate?
        get() = destinationSelected?.coordinate?.takeIf { it.valid() }
    val canCalculate: Boolean
        get() = !planning && !locatingGps && startLocation != null && destinationLocation != null
}
