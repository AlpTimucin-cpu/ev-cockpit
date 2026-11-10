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

    def test_compact_map_statuses_have_one_touchable_row_and_large_layout_keeps_stack(self):
        screen = source("presentation/cockpit/CockpitScreen.kt")
        overlays = screen.split(
            "// A single reachable status strip avoids overlays fighting for map space", 1
        )[1]
        self.assertIn("Modifier.align(Alignment.BottomCenter)", overlays)
        self.assertIn("Modifier.weight(1f), compact = true", overlays)
        self.assertIn("Modifier.align(Alignment.BottomStart)", overlays)
        self.assertIn("bottom = 36.dp", overlays)  # MapLibre attribution/logo stays visible
        self.assertIn("verticalArrangement = Arrangement.spacedBy(6.dp)", overlays)
        self.assertIn("modifier = Modifier.widthIn(max = 220.dp)", overlays)
        self.assertIn("modifier.height(if (compact) 44.dp else 36.dp)", overlays)
        self.assertIn("maxLines = 1,", overlays)
        self.assertIn("overflow = TextOverflow.Ellipsis", overlays)

    def test_map_focus_is_reversible_and_preserves_source_guard(self):
        screen = source("presentation/cockpit/CockpitScreen.kt")
        self.assertIn("var mapFocused by rememberSaveable", screen)
        self.assertIn("if (!mapFocused) {", screen)
        self.assertIn("CockpitLayoutPolicy.mapWeight(compactDashboard, mapFocused)", screen)
        self.assertIn("onToggleFocus = { mapFocused = !mapFocused }", screen)
        self.assertIn('if (mapFocused) "Göstergeler" else "Haritayı büyüt"', screen)
        self.assertIn("MapLibreCockpit(", screen)
        self.assertIn("if (obdConnection.phase == ObdPhase.CONNECTED) state else state.copy(", screen)

    def test_navigation_card_actions_are_independent_on_small_landscape(self):
        screen = source("presentation/cockpit/CockpitScreen.kt")
        card = screen.split("private fun NavigationGlassCard(", 1)[1].split(
            "private fun AssistPill(", 1
        )[0]
        self.assertIn("compact = compactDashboard", screen)
        self.assertIn("modifier = modifier,", card)
        self.assertNotIn("modifier.clickable(onClick = onOpen)", card)
        self.assertIn("TextButton(", card)
        self.assertIn("onClick = onToggleFocus", card)
        self.assertIn(".clickable(onClick = onOpen)", card)
        self.assertIn("heightIn(min = 48.dp)", card)
        self.assertIn("maxLines = if (compact) 1 else 2", card)

    def test_recenter_requires_live_fix_and_is_not_continuous_tracking(self):
        screen = source("presentation/cockpit/CockpitScreen.kt")
        map_code = source("presentation/map/MapLibreCockpit.kt")
        self.assertIn("enabled = state.gpsFix?.usable(nowMs) == true", screen)
        self.assertIn('contentDescription = "Konumuma dön"', screen)
        self.assertIn("recenterRequestId = recenterRequestId", screen)
        self.assertIn("recenterRequestId > handledRecenterRequestId", map_code)
        self.assertIn("it.usable(System.currentTimeMillis())", map_code)
        self.assertIn("mutableIntStateOf(recenterRequestId)", map_code)
        self.assertIn("CameraUpdateFactory.newLatLngZoom(", map_code)
        self.assertIn(".padding(bottom = 8.dp, end = 16.dp)", map_code)
        self.assertNotIn("Modifier.align(Alignment.BottomEnd).navigationBarsPadding()", map_code)
        self.assertNotIn('"EPDK · ${stations.size} istasyon', map_code)

    def test_soc_data_origin_is_verification_policy(self):
        nav = source("presentation/navigation/NavigationViewModel.kt")
        self.assertIn("VerifiedBatterySocPolicy.read(telemetry, now)", nav)
        self.assertIn("socPercent = batteryReading?.percent", nav)

if __name__ == "__main__":
    unittest.main()
