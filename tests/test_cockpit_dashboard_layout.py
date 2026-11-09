"""Cockpit safety/readability regression checks; no physical-car simulation."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
P = ROOT / "nativecockpit/src/main/java/com/evcockpit"

def source(relative: str) -> str:
    return (P / relative).read_text(encoding="utf-8")

class CockpitDashboardLayoutTest(unittest.TestCase):
    def test_four_readable_drive_values_without_experimental_substitution(self):
        screen = source("presentation/cockpit/CockpitScreen.kt")
        panel = screen.split("private fun TelemetryLeftPanel(", 1)[1].split(
            "private fun NavigationGlassCard(", 1)[0]
        self.assertEqual(panel.count("SummaryMetricRow("), 5)  # 4 uses + declaration
        for label in ("HIZ", "BATARYA DOLULUĞU", "BATARYA GÜCÜ", "TAHMİNİ MENZİL"):
            self.assertIn('title = "' + label + '"', panel)
        self.assertNotIn("candidateSoc", panel)
        self.assertNotIn("engineering.socPercent", panel)
        self.assertNotIn("metrics.estimatedSummerRangeKm", panel)
        self.assertIn(
            "if (connected && data.socPercent != null) data.estimatedRangeKm else null",
            panel,
        )
        self.assertIn("CockpitFormat.percent(if (connected) data.socPercent else null)", panel)
        self.assertIn("CockpitFormat.speed(if (connected) data.speedKmh else null)", panel)

    def test_disconnected_and_stale_data_are_masked(self):
        screen = source("presentation/cockpit/CockpitScreen.kt")
        self.assertIn("if (obdConnection.phase == ObdPhase.CONNECTED) state else state.copy(", screen)
        details = source("presentation/cockpit/TechnicalAnalysisSheet.kt")
        self.assertIn("val visibleSnapshot = if (hasFreshSession) snapshot else EngineeringSnapshot()", details)
        self.assertIn('Metric("SoC · deneysel DID"', details)
        self.assertNotIn('Metric("BMS doluluk"', details)

    def test_map_statuses_are_stacked_not_overlaid_on_navigation(self):
        screen = source("presentation/cockpit/CockpitScreen.kt")
        overlays = screen.split("// Weather and GPS/voice badges share one stacked edge group.", 1)[1]
        self.assertIn("Modifier.align(Alignment.BottomStart)", overlays)
        self.assertIn("verticalArrangement = Arrangement.spacedBy(6.dp)", overlays)
        self.assertIn("modifier = Modifier.widthIn(max = 220.dp)", overlays)
        self.assertIn("maxLines = 1, overflow = TextOverflow.Ellipsis", screen)

    def test_soc_data_origin_is_verification_policy(self):
        nav = source("presentation/navigation/NavigationViewModel.kt")
        self.assertIn("VerifiedBatterySocPolicy.read(telemetry, now)", nav)
        self.assertIn("socPercent = batteryReading?.percent", nav)

if __name__ == "__main__":
    unittest.main()
