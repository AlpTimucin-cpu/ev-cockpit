package com.evcockpit.car

import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapWithContentTemplate

/**
 * Android Auto host template only. Navigation validity, GPS and OBD evidence are
 * supplied by the owning Car Screen; this renderer cannot fabricate a route.
 *
 * A pane contains at most three informational rows and two host actions.
 * The host's actual content limit wins over physical display dimensions.
 */
internal object CarHostOverviewTemplate {
    fun render(
        apiLevel: Int,
        hostPaneLimit: Int,
        summary: CarRouteSummary,
        detail: String,
        active: Boolean,
        canBegin: Boolean,
        hasRealRoute: Boolean,
        connectionIssue: String?,
        onBegin: () -> Unit,
        onEnd: () -> Unit,
        onWeatherAndCharging: () -> Unit
    ): Template {
        val rows = listOf(
            Row.Builder().setTitle("Rota")
                .addText(summary.title.take(90))
                .addText((connectionIssue ?: summary.detail).take(130))
                .build(),
            Row.Builder().setTitle("Yönlendirme")
                .addText(detail.take(160))
                .build(),
            Row.Builder().setTitle("GPS")
                .addText(summary.locationStatus.take(110))
                .build()
        )
        val pane = Pane.Builder()
        rows.take(hostPaneLimit.coerceIn(1, rows.size)).forEach(pane::addRow)
        if (active) {
            pane.addAction(Action.Builder()
                .setTitle("Navigasyonu bitir")
                .setOnClickListener(onEnd)
                .build())
        } else if (canBegin) {
            pane.addAction(Action.Builder()
                .setTitle("Navigasyonu başlat")
                .setOnClickListener(onBegin)
                .build())
        }
        if (hasRealRoute) {
            pane.addAction(Action.Builder()
                .setTitle("Hava / Şarj")
                .setOnClickListener(onWeatherAndCharging)
                .build())
        }

        val content = PaneTemplate.Builder(pane.build())
        if (apiLevel < 7) {
            @Suppress("DEPRECATION")
            return content.setTitle("EV Cockpit").build()
        }
        return MapWithContentTemplate.Builder()
            .setContentTemplate(content.setHeader(
                Header.Builder().setTitle("EV Cockpit").build()
            ).build())
            .build()
    }
}
