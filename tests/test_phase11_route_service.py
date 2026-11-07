"""Regression checks for the Route Planner OSRM-onboarding screenshot failure.

These are static safety gates complementary to Kotlin/JVM and Android lint.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "nativecockpit/src/main/java/com/evcockpit"


def read(relative):
    return (SRC / relative).read_text(encoding="utf-8")


class Phase11RouteServiceTest(unittest.TestCase):
    def test_route_is_not_gated_by_fake_vehicle_or_unconfirmed_bms(self):
        repo = read("data/repository/OsrmRouteRepository.kt")
        policy = read("data/routing/RoutingEndpointPolicy.kt")
        self.assertNotIn("CoreIssue.CONFIG_UNCONFIRMED", repo)
        self.assertIn("RoutingEndpointPolicy.choose(", repo)
        self.assertIn("Araç bataryası onayı rota çizimi için", policy)
        self.assertNotIn("vehicle.readyForEstimate()", repo)
        self.assertNotIn("socEvidence", repo)
        self.assertNotIn("VehicleProfileConfig", policy)

    def test_demo_requires_opt_in_and_never_works_in_release(self):
        policy = read("data/routing/RoutingEndpointPolicy.kt")
        config = read("domain/model/AppConfiguration.kt")
        store = read("data/settings/AppConfigRepository.kt")
        viewmodel = read("presentation/settings/SettingsViewModel.kt")
        screen = read("presentation/settings/SettingsScreen.kt")
        self.assertIn("isDebugBuild && config.debugDemoRoutingApproved", policy)
        self.assertIn("BuildConfig.DEBUG &&", store)
        self.assertIn("require(BuildConfig.DEBUG)", store)
        self.assertIn("debugDemoRoutingApproved: Boolean = false", config)
        self.assertIn("saveDemoRoutingConsent(", viewmodel)
        self.assertIn("if (BuildConfig.DEBUG)", screen)
        self.assertIn("başlangıç", screen.lower())
        self.assertIn("onCheckedChange = viewModel::setDebugDemoRoutingConsent", screen)
        self.assertNotIn('osrmBaseUrl = "https://router.project-osrm.org/"', config)
        self.assertIn('host == "router.project-osrm.org"', config)

    def test_demo_coordinates_are_rate_limited_identified_and_not_automated(self):
        repo = read("data/repository/OsrmRouteRepository.kt")
        detour = read("data/repository/OsrmRoadDetourRepository.kt")
        limit = read("data/routing/OsrmDemoRateLimiter.kt")
        nav = read("presentation/navigation/NavigationViewModel.kt")
        self.assertIn("demoLimiter.execute(chosen.publicDemo)", repo)
        self.assertIn("demoLimiter.execute(selected.publicDemo)", detour)
        self.assertIn("if (selected.publicDemo)", detour)
        self.assertIn("if (service?.publicDemo == true)", nav)
        self.assertIn("Mutex()", limit)
        self.assertIn("delay(1_200L - elapsed)", limit)
        self.assertIn("EV-Cockpit-Debug/", limit)
        self.assertIn(".followRedirects(false)", limit)
        self.assertIn(".followSslRedirects(false)", limit)

    def test_planner_has_direct_link_to_real_service_settings(self):
        dialog = read("presentation/route/RoutePlannerDialog.kt")
        cockpit = read("presentation/cockpit/CockpitScreen.kt")
        nav = read("presentation/navigation/NavigationViewModel.kt")
        self.assertIn("onOpenRoutingSettings: () -> Unit", dialog)
        self.assertIn("OSRM / rota servisi ayarlarını aç", dialog)
        self.assertIn("onOpenRoutingSettings = {", cockpit)
        self.assertIn("onOpenSoundSettings()", cockpit)
        self.assertIn("result.message.take(350)", nav)
        self.assertNotIn("result.message.take(90)", nav)


if __name__ == "__main__":
    unittest.main()
