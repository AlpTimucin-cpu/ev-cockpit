"""Phase 2 inactive provider, provenance and safety invariants (no fake live traffic)."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "nativecockpit/src/main/java/com/evcockpit"
def code(path):
    return (BASE / path).read_text(encoding="utf-8")
class Phase2TrafficGateTest(unittest.TestCase):
    def test_unconfigured_unavailable_and_osrm_label_remain_visible(self):
        gate = code("domain/usecase/TrafficEvidencePolicy.kt")
        route = code("presentation/route/RouteJourneySheet.kt")
        settings = code("presentation/settings/SettingsScreen.kt")
        self.assertIn('val label: String = "Trafik: kullanılamıyor"', gate)
        self.assertIn("if (!ownerApproved)", gate)
        self.assertIn("if (!keyAvailable)", gate)
        self.assertIn("trafficState.label(route.routeId, System.currentTimeMillis())", route)
        cockpit = code("presentation/cockpit/CockpitScreen.kt")
        self.assertIn("speedProfile = emptyList()", cockpit)
        self.assertNotIn("speedProfile = route?.profileSpeeds.orEmpty()", cockpit)
        self.assertIn("OSRM tahmini · canlı trafik içermez", route)
        self.assertIn("trafficAccess.statusTr", settings)
        self.assertIn("PasswordVisualTransformation()", settings)
        self.assertIn("viewModel.saveTomTomKey(newTomTomKey)", settings)
        vault = code("data/traffic/TomTomCredentialVault.kt")
        self.assertIn('KeyStore.getInstance("AndroidKeyStore")', vault)
        self.assertIn('Cipher.getInstance("AES/GCM/NoPadding")', vault)
        self.assertIn('GCMParameterSpec(128,', vault)
        self.assertIn('putBoolean("location_consent", false)', vault)
        self.assertNotIn('putString("sealed_key", candidate)', vault)

    def test_stale_quota_route_and_eta_fail_closed(self):
        gate = code("domain/usecase/TrafficEvidencePolicy.kt")
        self.assertIn("httpCode == 429 -> TrafficFailure.QUOTA_EXCEEDED", gate)
        self.assertIn("evidence.routeId != routeId", gate)
        self.assertIn("nowMs - evidence.sourceTimeMs > MAX_AGE_MS", gate)
        self.assertIn("evidence.trafficRoutingVerified && it.isFinite()", gate)
        self.assertNotIn("trafficAwareEtaSeconds = 0.0", gate)
        self.assertIn("segmentsVerified: Int = 0", gate)

    def test_debug_reports_unavailable_not_fabricated_sources(self):
        debug = (ROOT / "nativecockpit/src/debug/java/com/evcockpit"
                 "/diagnostics/DebugDiagnosticsEntry.kt").read_text(encoding="utf-8")
        self.assertIn("Trafikli ETA: kullanılamıyor", debug)
        self.assertIn("Hız sınırı: kullanılamıyor", debug)
        self.assertIn("TomTom HTTP sunucu zamanı: ", debug)
        self.assertIn("state.trafficFlowTilesCount", debug)
        renderer = code("presentation/map/TomTomTrafficMapRenderer.kt")
        self.assertIn("ImageSource(", renderer)
        self.assertIn("RasterLayer(", renderer)
        self.assertIn("snapshot.freshFor(routeId, nowMs)", renderer)
        self.assertNotIn("TomTom-Api-Key", renderer)
        self.assertNotIn("TOMTOM_API_KEY", debug)
        self.assertNotIn("https://api.tomtom.com/", debug)
if __name__ == "__main__":
    unittest.main()
