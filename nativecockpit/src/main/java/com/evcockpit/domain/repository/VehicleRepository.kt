package com.evcockpit.domain.repository

import kotlinx.coroutines.flow.StateFlow
import com.evcockpit.domain.model.VehicleTelemetry

// Later, a read-only Bluetooth ELM327 adapter will implement this contract.
interface VehicleRepository {
    val telemetry: StateFlow<VehicleTelemetry>
}
