package com.evcockpit.domain.model

/** A failed HTTP response must never be passed to the OSRM route JSON parser. */
sealed interface RouteCalculationResult {
    data class Success(val alternatives: Int) : RouteCalculationResult
    data class Failure(val statusCode: Int?, val message: String) : RouteCalculationResult
}
