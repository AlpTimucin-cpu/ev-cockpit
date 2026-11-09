package com.evcockpit.data.repository

import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.evcockpit.domain.model.VehicleTelemetry
import com.evcockpit.domain.repository.VehicleRepository

// Production-safe default: no connection means all measurements are unknown.
class DisconnectedVehicleRepository @Inject constructor() : VehicleRepository {
    private val _telemetry = MutableStateFlow(VehicleTelemetry())
    override val telemetry: StateFlow<VehicleTelemetry> = _telemetry.asStateFlow()
}
