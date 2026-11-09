"""Regression checks for the screenshot-reported route configuration blocker.

The user's route must never depend on battery/BMS consent. Public demo
routing is opt-in, Debug-only, and must not trigger automated charging detours.
Tests complement native JVM/Android lint and do NOT prove live OSRM uptime.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "nativecockpit/src/main/java/com/evcockpit"


def source(path):
    return (JAVA / path).read_text(encoding="utf-8")


class RouteOsrmDebugOptInTest(unittest.TestCase):
    def test_bms_does_not_block_real_road_route(self):
        repo = source("data/repository/OsrmRouteRepository.kt")
        self.assertIn("OsrmEndpointSelector.choose(", repo)
        self.assertNotIn("CoreIssue.CONFIG_UNCONFIRMED.messageTr", repo)
        self.assertNotIn("vehicle.readyForEstimate()", repo)
        self.assertNotIn("VerifiedBatterySocPolicy", repo)
        self.assertIn("Rota servisi seçilmedi", repo)

    def test_demo_is_debug_only_and_explicit_opt_in(self):
        selection = source("domain/model/OsrmEndpointSelection.kt")
        config = source("domain/model/AppConfiguration.kt")
        storage = source("data/settings/AppConfigRepository.kt")
        repo = source("data/repository/OsrmRouteRepository.kt")
        settings = source("presentation/settings/SettingsScreen.kt")
        self.assertIn("debugDemoRoutingEnabled: Boolean = false", config)
        self.assertIn("debugBuild && endpoints.debugDemoRoutingEnabled", selection)
        self.assertIn("p[Keys.DEBUG_ROUTING_DEMO] == true", storage)
        self.assertIn("setDebugDemoRoutingEnabled", storage)
        self.assertIn("appConfig.configuration.first().endpoints, BuildConfig.DEBUG", repo)
        self.assertIn("if (BuildConfig.DEBUG)", settings)
        self.assertIn("viewModel::setDebugDemoRoutingEnabled", settings)
        self.assertIn("router.project-osrm.org", selection)

    def test_demo_cannot_flood_station_inspection(self):
        detours = source("data/repository/OsrmRoadDetourRepository.kt")
        repo = source("data/repository/OsrmRouteRepository.kt")
        self.assertIn("endpoint == null || endpoint.demo", detours)
        self.assertIn("if (endpoint.demo) 1 else 2", repo)
        self.assertIn("!demoGate.accept(System.currentTimeMillis())", repo)
        self.assertIn("User-Agent", repo)
        self.assertIn("source.request(6_000_001L)", repo)

    def test_route_failure_links_to_correct_settings_screen(self):
        ui = source("presentation/route/RoutePlannerDialog.kt")
        cockpit = source("presentation/cockpit/CockpitScreen.kt")
        activity = source("MainActivity.kt")
        self.assertIn("onOpenRoutingSettings: () -> Unit", ui)
        self.assertIn("Rota hizmeti ayarlarını aç", ui)
        self.assertIn("onOpenRoutingSettings = {", cockpit)
        self.assertIn("onSettings = onOpenSoundSettings", cockpit)
        self.assertIn("SettingsScreen(", activity)
        self.assertIn("onOpenSoundSettings = { audioSettingsOpen = true }", activity)

    def test_auto_and_zip_safety_remain_separate(self):
        selector = source("domain/model/OsrmEndpointSelection.kt")
        endpoint = source("domain/model/AppConfiguration.kt")
        self.assertIn('osrmBaseUrl: String? = null', endpoint)
        self.assertIn('osrm = true', selector)
        self.assertNotIn("22028C", selector)
        self.assertNotIn("passengerFlag", selector)


if __name__ == "__main__":
    unittest.main()
