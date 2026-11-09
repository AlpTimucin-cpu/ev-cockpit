package com.evcockpit.domain.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import com.evcockpit.domain.model.*

/** Shared singleton backing phone, map, charging corridor and Android Auto. */
interface RouteRepository {
    val currentRoute: StateFlow<RouteSnapshot?>
    val options: StateFlow<List<RouteOption>>
    val selectedIndex: StateFlow<Int?>
    suspend fun search(query: String): List<RoutePlace>
    suspend fun calculate(origin: GeoCoordinate, destination: RoutePlace): RouteCalculationResult
    /** Real OSRM via-points; implementers may reject unsupported routes explicitly. */
    suspend fun calculateVia(
        origin: GeoCoordinate,
        destination: RoutePlace,
        stops: List<GeoCoordinate>
    ): RouteCalculationResult = if (stops.isEmpty()) calculate(origin, destination)
    else RouteCalculationResult.Failure(null, "Bu rota hizmeti ara durakları desteklemiyor")


    // Preserve existing callers while supplying one safe Result boundary to
    // the ViewModel; coroutine cancellation must never turn into UI errors.
    suspend fun searchResult(query: String): Result<List<RoutePlace>> = try {
        Result.success(search(query))
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        Result.failure(error)
    }

    suspend fun calculateResult(
        origin: GeoCoordinate, destination: RoutePlace
    ): Result<RouteCalculationResult> = try {
        Result.success(calculate(origin, destination))
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        Result.failure(error)
    }
    fun choose(index: Int)
    fun clear()
}
