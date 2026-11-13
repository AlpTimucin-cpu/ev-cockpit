"""Phase 3 EPDK full-catalog correctness and no fabricated tariff/SoC."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/"nativecockpit/src/main/java/com/evcockpit"
def source(rel):
    return (JAVA/rel).read_text(encoding="utf-8")
class Phase3EpdkProvenance(unittest.TestCase):
    def test_official_export_verified_counts_and_freshness(self):
        parser=source("data/remote/EpdkSnapshotParser.kt")
        policy=source("domain/usecase/EpdkProvenancePolicy.kt")
        repo=source("data/repository/EpdkChargingRepository.kt")
        self.assertIn("source == EpdkProvenancePolicy.OFFICIAL_SOURCE",parser)
        self.assertIn("raw.length() == geocodedCount",parser)
        self.assertIn("items.size == geocodedCount",parser)
        self.assertIn("EPDK station ID is missing or duplicated",parser)
        self.assertIn("72L * 60L * 60_000L",policy)
        self.assertIn("EpdkProvenancePolicy.isFresh(result.verifiedAtUtc, receivedMs)",repo)
        self.assertIn("AtomicFile(cache)",repo)
        self.assertIn("diagnostics.chargingAttempt(attemptMs)",repo)
        self.assertIn("downloadSuccessAtMillis = receivedMs",repo)
        self.assertNotIn("Instant.now().toString()",repo)
    def test_stale_cannot_power_route_suggestions(self):
        view=source("presentation/charging/ChargingViewModel.kt")
        planner=source("presentation/charging/RouteChargePlanViewModel.kt")
        car=source("car/CarChargingListPolicy.kt")
        cockpit=source("presentation/cockpit/CockpitScreen.kt")
        self.assertIn("safeStations = catalog.stations.takeIf { sourceFresh }",view)
        self.assertIn("catalog.staleCache",planner)
        self.assertIn("if (catalog.staleCache) return CarChargeList",car)
        self.assertIn("acceptedDcPowerKw(",car)
        self.assertIn("TEORİK",cockpit)
        self.assertIn("enabled = chargingUi.sourceFresh",cockpit)
        self.assertIn("veri setinde bulunmaz",cockpit)
    def test_debug_does_not_confuse_publisher_and_download(self):
        debug=(ROOT/"nativecockpit/src/debug/java/com/evcockpit"
          "/diagnostics/DebugDiagnosticsEntry.kt").read_text(encoding="utf-8")
        self.assertIn("EPDK son indirme denemesi:",debug)
        self.assertIn("EPDK son başarılı snapshot indirilişi:",debug)
        self.assertIn("EPDK doğrulanmış coğrafi kayıt sayısı:",debug)
        self.assertIn("Son EPDK veri zamanı (UTC):",debug)
        self.assertNotIn("EPDK API anahtarı",debug)
if __name__=="__main__":
    unittest.main()
