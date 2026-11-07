package com.evcockpit.car

import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.model.RouteSnapshot
import com.evcockpit.domain.model.validRouteGeometry
import java.util.Locale

/** Short Turkish Android Auto labels; never invent current speed, GPS or distance. */
data class CarRouteSummary(
    val routeAvailable: Boolean,
    val title: String,
    val detail: String,
    val locationStatus: String
) {
    companion object {
        fun from(route: RouteSnapshot?, gps: GpsFix?, nowMillis: Long): CarRouteSummary {
            val location = if (gps?.usable(nowMillis) == true && gps.accuracyMeters <= 35f)
                "GPS güncel" else "Hassas GPS yok · telefondan izin verin"
            if (route == null || !validRouteGeometry(route.routePoints) ||
                route.routeId.isNullOrBlank()
            ) return CarRouteSummary(
                false, "Planlı rota yok", "Telefondan başlangıç ve varış seçin", location
            )
            val destination = route.destination?.trim()?.takeIf { it.isNotBlank() }
                ?.take(55) ?: "Hedef"
            val total = route.totalKm?.takeIf { it.isFinite() && it > 0.0 }
                ?.let { String.format(Locale.forLanguageTag("tr-TR"),
                    "Toplam %.1f km · OSRM tahmini", it) }
                ?: "Rota var · OSRM uzunluğu bilinmiyor"
            return CarRouteSummary(true, destination, total, location)
        }
    }
}
