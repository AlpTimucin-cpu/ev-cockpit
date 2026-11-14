"""Phase 3 EPDK public snapshot provenance and no-live-data source gates."""
from pathlib import Path
from datetime import datetime, timezone
import json
import unittest
ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "nativecockpit/src/main/java/com/evcockpit"
class Phase3EpdkSourceTest(unittest.TestCase):
    def test_branch_snapshot_is_old_and_never_live(self):
        meta = json.loads((ROOT / "data/charging/epdk_public_snapshot_meta.json").read_text())
        self.assertEqual(meta["source_url"], "https://apigateway.epdk.gov.tr/sarjIstasyonlari")
        self.assertFalse(meta["prices_included"])
        self.assertFalse(meta["live_status_included"])
        stamp = datetime.fromisoformat(meta["verified_at_utc"])
        self.assertIsNotNone(stamp.tzinfo)
        self.assertLessEqual(stamp, datetime.now(timezone.utc))
        self.assertGreaterEqual(meta["geocoded_public_rows_count"], 5000)
        self.assertLessEqual(meta["geocoded_public_rows_count"], meta["public_rows_count"])

    def test_phone_car_and_planner_share_age_policy(self):
        def code(path):
            return (NATIVE / path).read_text()
        p = code("domain/usecase/EpdkSourceAgePolicy.kt")
        phone = code("presentation/charging/ChargingViewModel.kt")
        car = code("car/CarChargingListPolicy.kt")
        planner = code("presentation/charging/RouteChargePlanViewModel.kt")
        ui = code("presentation/cockpit/CockpitScreen.kt")
        self.assertIn("CANLI DEĞİL", p)
        self.assertIn("7L * DAY_MS", p)
        self.assertIn("sourceAge = EpdkSourceAgePolicy.assess", phone)
        self.assertIn("sourceAge.labelTr", car)
        self.assertIn("catalogPublicationUtc", planner)
        self.assertIn("ui.sourceAge.labelTr", ui)
        self.assertNotIn("live_status_included\": true", ui)

    def test_network_source_pinned_to_public_snapshot_not_private_api(self):
        source = (NATIVE / "data/remote/EpdkSnapshotParser.kt").read_text()
        service = (NATIVE / "data/remote/EpdkService.kt").read_text()
        self.assertIn("https://apigateway.epdk.gov.tr/sarjIstasyonlari", source)
        self.assertIn('root.optString("source_url")', source)
        self.assertIn("raw.githubusercontent.com", (NATIVE / "di/ChargingModule.kt").read_text())
        self.assertNotIn("api_key", service.lower())
if __name__ == "__main__":
    unittest.main()
