package com.evcockpit.presentation.cockpit

import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.model.ObdPhase

/** Compact labels are calculated only from verified, timestamped live sources. */
internal object DashboardStatusPolicy {
    fun gpsLabel(fix: GpsFix?, nowMillis: Long): String =
        if (fix?.usable(nowMillis) == true) "GPS güncel" else "GPS bekleniyor"

    fun obdLabel(phase: ObdPhase): String = when (phase) {
        ObdPhase.CONNECTED -> "OBD bağlı"
        ObdPhase.CONNECTING -> "OBD bağlanıyor"
        ObdPhase.ERROR -> "OBD hatası"
        ObdPhase.DISCONNECTED -> "OBD bağlı değil"
    }

    fun weatherLabel(loading: Boolean, hasData: Boolean, ageMinutes: Long?): String = when {
        loading -> "Hava yükleniyor"
        !hasData || ageMinutes == null -> "Hava verisi yok"
        ageMinutes > 30L -> "Hava eski · ${ageMinutes} dk"
        else -> "Hava · ${ageMinutes} dk"
    }
}
