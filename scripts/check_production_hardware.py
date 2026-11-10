#!/usr/bin/env python3
"""Fail the CI build when native production code reintroduces fake GPS or OBD."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "nativecockpit/src/main/java/com/evcockpit"

def read(relative: str) -> str:
    return (JAVA / relative).read_text(encoding="utf-8")

nav = read("presentation/navigation/NavigationViewModel.kt")
phone = read("MainActivity.kt")
cockpit = read("presentation/cockpit/CockpitScreen.kt")
obd_dialog = read("presentation/obd/ObdSettingsDialog.kt")
car = read("car/EyaCarNavigationScreen.kt")
repo = read("data/repository/Elm327VehicleRepository.kt")
fg = read("ObdForegroundService.kt")
gradle = (ROOT / "nativecockpit/build.gradle.kts").read_text(encoding="utf-8")
proguard = (ROOT / "nativecockpit/proguard-rules.pro").read_text(encoding="utf-8")
battery_policy = read("domain/usecase/VerifiedBatterySocPolicy.kt")
remaining_policy = read("domain/usecase/RemainingRouteEnergyPolicy.kt")
charge_planner = read("presentation/charging/RouteChargePlanViewModel.kt")
vehicle_model = read("domain/model/VehicleTelemetry.kt")

assert not (JAVA / "presentation/navigation/DebugRouteSimulation.kt").exists()
assert not (JAVA / "presentation/navigation/NavigationSourceSelector.kt").exists()
assert not (ROOT / "nativecockpit/src/test/java/com/evcockpit/presentation/navigation/DebugRouteSimulationTest.kt").exists()

for source in (nav, phone, cockpit, obd_dialog):
    assert "DebugRouteSimulation" not in source
    assert "_simulationEnabled" not in source
    assert "simulationEnabled" not in source
    assert "onSimulationToggle" not in source
assert "autoDriveTestRequested" not in car
assert "source.simulated" not in nav
assert 'fresh ?: 80.0' not in nav
assert 'ExplicitAssumption(' not in nav
# FAZ 5: audit the actual provenance policy rather than assuming its
# implementation must live inside NavigationViewModel.
assert "RemainingRouteEnergyPolicy.estimate(" in nav
assert "VerifiedBatterySocPolicy.read(telemetry, now)" in nav
assert "DepartureSoc.FreshObd(soc.percent, soc.measuredAtMs)" in remaining_policy
assert "actualRoute.copy(totalKm = remainingKm)" in remaining_policy
assert "profile.readyForEstimate()" in remaining_policy
assert "EvidenceSource.VERIFIED_OBD" in battery_policy
assert "EvidenceClass.MEASUREMENT" in battery_policy
assert "evidence.recordedAtMillis" in battery_policy
assert "socEvidence: CoreEvidence<Double>? = null" in vehicle_model
assert "configuration.configuration" in charge_planner
assert '"usableBatteryKwh"' not in charge_planner
assert '"referenceWhPerKm"' not in charge_planner
assert "vehicleRepository.telemetry, locationRepository.gpsFix" in nav
assert "locationRepository.start()" in nav
assert "isFromMockProvider" in nav
assert "createRfcommSocketToServiceRecord(spp)" in repo
assert "candidate.connect()" in repo and "connectSpp(device)" in repo
assert "Elm327Session(transport)" in repo
assert "ElmSessionPhase.READY" in repo
assert 'protocol.readRawOnce("010D")' in repo
# Phase 9: the actual one-shot manual request has a second speed gate
# after a fresh 010D poll and before the first AT/CAN diagnostic setting.
assert "runMebReadOnlyQuery(conn, protocol, manual)" in repo
assert "ObdDiagnosticSafetyPolicy.canRequest(" in repo
assert "ObdDiagnosticSafetyPolicy.canExecute(" in repo
assert "ObdWireCommandPolicy.allowed(command)" in repo
assert "ObdProtocolRestorer.restore(" in repo
assert "catch (fatal: ObdProtocolRestoreException)" in repo
assert "_engineering.value.copy(speedKmh = TimedValue())" in repo
assert "lastSpeedKmh = speed" in repo
assert "lastSpeedAtMs=null,lastSpeedKmh=null" in repo
assert "protocol.close()" in repo

assert "withContext(Dispatchers.IO) { runSession(device) }" in repo
assert "_telemetry.value = VehicleTelemetry()" in repo
assert "ObdPhase.CONNECTED" in fg
# Check the live status policy, not the name of a translated display string.
# Connected/disconnected presentation must retain the existing OBD mask.
status_policy = read("presentation/cockpit/DashboardStatusPolicy.kt")
assert "DashboardStatusPolicy.gpsLabel(state.gpsFix, nowMs)" in cockpit
assert "DashboardStatusPolicy.obdLabel(obdConnection.phase)" in cockpit
assert "if (obdConnection.phase == ObdPhase.CONNECTED) state else state.copy(" in cockpit
assert "fix?.usable(nowMillis) == true" in status_policy
assert 'else "GPS bekleniyor"' in status_policy
assert 'ObdPhase.ERROR -> "OBD hatası"' in status_policy
assert 'ObdPhase.CONNECTING -> "OBD bağlanıyor"' in status_policy

assert "isMinifyEnabled = true" in gradle
assert "isShrinkResources = true" in gradle
assert not any(line.strip() == "-dontoptimize" for line in proguard.splitlines())
print("OK: verified-only battery SoC, remaining-route projection, fail-closed ELM327 and R8 guards")
