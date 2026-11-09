"""FAZ 8 static regression gates: the uploaded ZIP is a reference, not source.

These checks do not require the uploaded ZIP to be present on GitHub CI.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


def source(path):
    return (ROOT / path).read_text(encoding="utf-8-sig")


class Phase8WeatherProvenanceTest(unittest.TestCase):
    def test_zip_risk_code_never_imported_into_native_android(self):
        java_root = ROOT / "nativecockpit/src/main/java"
        all_native = "\n".join(p.read_text(encoding="utf-8")
                               for p in java_root.rglob("*.kt"))
        for forbidden in (
            "mutableFloatStateOf(80f)",
            "Obd.value(b, 1, 1, 2.5)",
            'putBoolean("passenger", true)',
            'router.project-osrm.org/route/v1/driving',
            'fun query(cmd: String, header: String? = null, filter: String? = null)'
        ):
            self.assertNotIn(forbidden, all_native)
        self.assertFalse((java_root / "tr/yolarkadasi").exists())

    def test_weather_speech_requires_gps_progress_and_unexpired_eta(self):
        gate = source(
            "nativecockpit/src/main/java/com/evcockpit/domain/usecase/"
            "WeatherChangeAlertGate.kt"
        )
        viewmodel = source(
            "nativecockpit/src/main/java/com/evcockpit/presentation/"
            "navigation/NavigationViewModel.kt"
        )
        parser = source(
            "nativecockpit/src/main/java/com/evcockpit/data/weather/"
            "HourlyForecastParser.kt"
        )
        self.assertIn("val fraction = progressFraction?.takeIf", gate)
        self.assertIn("point.distanceFromStartKm + 0.25 >= distanceKm", gate)
        self.assertIn("eta >= nowMs - 60_000L", gate)
        self.assertIn("eta <= nowMs + 3 * 60 * 60_000L", gate)
        self.assertIn("forecast, now, route.validProgress()", viewmodel)
        self.assertIn("bestOffset > 2_700L", parser)
        self.assertNotIn("?: 0", parser)

    def test_no_unverified_bms_can_overwrite_navigation_soc(self):
        obd = source("nativecockpit/src/main/java/com/evcockpit/data/"
                     "repository/Elm327VehicleRepository.kt")
        parser = source("nativecockpit/src/main/java/com/evcockpit/data/obd/MebUdsParser.kt")
        policy = source("nativecockpit/src/main/java/com/evcockpit/domain/"
                        "usecase/VerifiedBatterySocPolicy.kt")
        self.assertIn("if (command !in MebUdsParser.ALLOWED_REQUESTS) return", obd)
        self.assertIn("return result(MebResponseStatus.VALID_RAW, bytes)", parser)
        self.assertIn("EvidenceSource.VERIFIED_OBD", policy)
        self.assertNotIn("socPercent = TimedValue(80.0", obd)


if __name__ == "__main__":
    unittest.main()
