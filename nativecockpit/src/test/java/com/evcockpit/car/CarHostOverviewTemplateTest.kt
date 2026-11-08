package com.evcockpit.car

import androidx.car.app.Screen
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real Car App Library 1.7.0 model and TestCarContext/ScreenController harness.
 * Synthetic UI labels are not simulated GPS/SoC/OBD measurements.
 * No real ID.4/AA display is attached by these JVM tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarHostOverviewTemplateTest {
    private val route = CarRouteSummary(
        routeAvailable = true,
        title = "Niğde → Antalya",
        detail = "OSRM 480 km · model",
        locationStatus = "GPS güncel"
    )
    private val noRoute = CarRouteSummary(
        routeAvailable = false,
        title = "Planlı rota yok",
        detail = "Telefondan başlangıç ve varış seçin",
        locationStatus = "Hassas GPS yok"
    )

    /** An actual Screen created with AndroidX TestCarContext. */
    private class OverviewScreen(
        context: TestCarContext,
        var api: Int,
        var rowLimit: Int,
        var summary: CarRouteSummary,
        var active: Boolean = false,
        var canBegin: Boolean = false,
        var hasRoute: Boolean = false
    ) : Screen(context) {
        override fun onGetTemplate(): Template = CarHostOverviewTemplate.render(
            apiLevel = api,
            hostPaneLimit = rowLimit,
            summary = summary,
            detail = "Manevra bilgisi GPS doğrulanınca gösterilir",
            active = active,
            canBegin = canBegin,
            hasRealRoute = hasRoute,
            connectionIssue = null,
            onBegin = {},
            onEnd = {},
            onWeatherAndCharging = {}
        )
    }

    private fun screen(
        api: Int,
        rowLimit: Int,
        summary: CarRouteSummary = route,
        active: Boolean = false,
        canBegin: Boolean = false,
        hasRoute: Boolean = true
    ): Pair<OverviewScreen, ScreenController> {
        val context = TestCarContext.createCarContext(
            ApplicationProvider.getApplicationContext()
        )
        context.lifecycleOwner.registry.currentState = Lifecycle.State.STARTED
        val screen = OverviewScreen(
            context, api, rowLimit, summary, active, canBegin, hasRoute
        )
        val controller = ScreenController(screen)
        controller.moveToState(Lifecycle.State.STARTED)
        return screen to controller
    }

    @Test fun apiSixReturnsLegacyPaneWithOnlyPermittedRows() {
        val (screen, controller) = screen(api = 6, rowLimit = 2)
        val template = screen.onGetTemplate()
        assertTrue(template is PaneTemplate)
        val pane = (template as PaneTemplate).pane
        assertEquals(2, pane.rows.size)
        assertEquals("Rota", pane.rows[0].title.toString())
        assertEquals("Yönlendirme", pane.rows[1].title.toString())
        assertEquals(1, pane.actions.size) // weather/charging only
        assertTrue(pane.actions[0].title.toString().contains("Hava"))
        assertNotNull(controller.screen)
    }

    @Test fun apiSevenUsesMapWithContentAndMaxThreeInformationRows() {
        val (screen, _) = screen(api = 7, rowLimit = 100, active = true)
        val template = screen.onGetTemplate()
        assertTrue(template is MapWithContentTemplate)
        val pane = ((template as MapWithContentTemplate).contentTemplate as PaneTemplate).pane
        assertEquals(3, pane.rows.size)
        assertEquals(2, pane.actions.size)
        assertEquals("Navigasyonu bitir", pane.actions[0].title.toString())
        assertEquals("Hava / Şarj", pane.actions[1].title.toString())
    }

    @Test fun noRealRouteAndNoVerifiedGpsCannotEnableStartActions() {
        val (screen, _) = screen(
            api = 1, rowLimit = 2, summary = noRoute, canBegin = false,
            hasRoute = false
        )
        val template = screen.onGetTemplate() as PaneTemplate
        assertTrue(template.pane.actions.isEmpty())
        assertEquals("Planlı rota yok", template.pane.rows[0].texts[0].toString())
        assertTrue(template.pane.rows.size <= 2)
    }

    @Test fun userStartActionExistsOnlyWhenScreenAuthorizesIt() {
        val (screen, _) = screen(
            api = 7, rowLimit = 3, canBegin = true, hasRoute = true
        )
        val template = screen.onGetTemplate() as MapWithContentTemplate
        val pane = (template.contentTemplate as PaneTemplate).pane
        assertEquals("Navigasyonu başlat", pane.actions[0].title.toString())
        assertEquals(2, pane.actions.size)
    }

    @Test fun hostPaneLimitOneStillPreservesVisibleRouteStatus() {
        val (screen, _) = screen(
            api = 3, rowLimit = 1, summary = noRoute, hasRoute = false
        )
        val pane = (screen.onGetTemplate() as PaneTemplate).pane
        assertEquals(1, pane.rows.size)
        assertEquals("Rota", pane.rows[0].title.toString())
    }

    @Test fun hostCannotGainThirdActionWhenBothNavigationAndMoreAvailable() {
        val (screen, _) = screen(api = 7, rowLimit = 4, active = true, canBegin = true)
        val pane = ((screen.onGetTemplate() as MapWithContentTemplate)
            .contentTemplate as PaneTemplate).pane
        assertEquals(2, pane.actions.size)
        assertTrue(pane.actions.none { it.title.toString() == "Navigasyonu başlat" })
    }
}
