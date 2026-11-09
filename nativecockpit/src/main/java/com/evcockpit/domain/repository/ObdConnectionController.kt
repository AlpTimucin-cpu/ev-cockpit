package com.evcockpit.domain.repository

import kotlinx.coroutines.flow.StateFlow
import com.evcockpit.domain.model.EngineeringSnapshot
import com.evcockpit.domain.model.EngineeringMetrics
import com.evcockpit.domain.model.ObdConnectionState

interface ObdConnectionController {
    val connection: StateFlow<ObdConnectionState>
    val engineering: StateFlow<EngineeringSnapshot>
    val engineeringMetrics: StateFlow<EngineeringMetrics>
    fun refreshPaired()
    fun connect(address: String)
    fun disconnect()
    fun permissionDenied()
    /** Allowlisted, read-only UDS ReadDataByIdentifier requests. */
    fun requestMebDiagnostic(command: String)
    fun clearMebDiagnostic()
}
