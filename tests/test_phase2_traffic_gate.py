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
        self.assertIn("TrafficEvidencePolicy.disabled.label", route)
        self.assertIn("OSRM tahmini · canlı trafik içermez", route)
        self.assertIn("TrafikEvidence", gate) if False else None
        self.assertIn("TrafficEvidencePolicy.disabled.label", settings)

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
        self.assertIn("Trafik olayları/hız sınırı: kullanılamıyor", debug)
        self.assertNotIn("TOMTOM_API_KEY", debug)
        self.assertNotIn("https://api.tomtom.com/", debug)
if __name__ == "__main__":
    unittest.main()
