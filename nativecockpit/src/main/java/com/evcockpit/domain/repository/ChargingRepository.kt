package com.evcockpit.domain.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import com.evcockpit.domain.model.ChargingCatalog

interface ChargingRepository {
    val catalog: StateFlow<ChargingCatalog>
    suspend fun refresh()

    // Keep existing implementations intact while catching unexpected failures
    // at the domain boundary. Cancellation is not a network/UI error.
    suspend fun refreshResult(): Result<Unit> = try {
        refresh()
        Result.success(Unit)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        Result.failure(error)
    }
}
