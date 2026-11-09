package com.evcockpit.domain.repository

import kotlinx.coroutines.flow.StateFlow
import com.evcockpit.domain.model.GpsFix

interface LocationRepository {
    val gpsFix: StateFlow<GpsFix?>
    /** Foreground-only GPS after explicit Android permission. */
    fun start()
    fun stop()
    fun acceptFix(fix: GpsFix)
}
