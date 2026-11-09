"""Phase 7 fail-closed source policy. JVM tests cover runtime state machines.

This is a static audit, NOT physical VW ID.4/Android Auto certification.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "nativecockpit"


def read(path):
    return (ROOT / path).read_text(encoding="utf-8-sig")


class Phase7SafetyPolicyTest(unittest.TestCase):
    def test_debug_can_be_installed_without_overwriting_signed_app(self):
        build = read("nativecockpit/build.gradle.kts")
        workflow = read(".github/workflows/build-nativecockpit.yml")
        self.assertIn('applicationId = "com.evcockpit"', build)
        self.assertRegex(build, r'applicationIdSuffix\s*=\s*"\.debug"')
        self.assertIn('versionNameSuffix = "-DEBUG"', build)
        self.assertNotIn('versionNameSuffix = "-TEST"', build)
        self.assertIn("package: name='com.evcockpit.debug'", workflow)
        self.assertIn("application-label:'EV Cockpit TEST'", workflow)
        for locale in ("values", "values-tr"):
            label = read(f"nativecockpit/src/debug/res/{locale}/strings.xml")
            self.assertIn('name="app_name"', label)
            self.assertIn("EV Cockpit TEST", label)

    def test_release_workflow_is_held_in_this_proposed_branch(self):
        # This source check does NOT establish the same protection on 'main'.
        # Security hotfix PR #32 must be merged there separately.
        workflow = read(".github/workflows/release.yml")
        self.assertRegex(workflow, r'(?m)^  signed-release:\s*\n(?:    #[^\n]*\n)*    if: false\s*$')
        self.assertIn("allow_temporary_signing", workflow)
        self.assertNotIn("assembleRelease", read(".github/workflows/build-nativecockpit.yml"))

    def test_stale_android_auto_screen_cannot_revoke_new_session(self):
        owner = read("nativecockpit/src/main/java/com/evcockpit/car/CarNavigationOwnership.kt")
        screen = read("nativecockpit/src/main/java/com/evcockpit/car/EyaCarNavigationScreen.kt")
        self.assertIn("currentToken === token", owner)
        self.assertIn("currentToken !== token", owner)
        self.assertIn("CarNavigationOwnership.claim(navigationSessionToken)", screen)
        self.assertIn("CarNavigationOwnership.release(navigationSessionToken)", screen)
        self.assertIn("CarNavigationOwnership.owns(navigationSessionToken)", screen)
        self.assertNotIn("setNavigating(false)", screen)

    def test_late_audio_and_gps_events_cannot_clear_new_session(self):
        manager = read("nativecockpit/src/main/java/com/evcockpit/presentation/voice/AudioDuckingManager.kt")
        lease = read("nativecockpit/src/main/java/com/evcockpit/presentation/voice/AudioFocusLease.kt")
        gps = read("nativecockpit/src/main/java/com/evcockpit/car/CarGpsTracker.kt")
        self.assertIn("lease.lossGeneration()", manager)
        self.assertIn("lease.shouldStopAfterLoss(capturedGeneration)", manager)
        self.assertIn("generation == expectedGeneration", lease)
        self.assertIn("val token = gate.open()", gps)
        self.assertIn("gate.isCurrent(token)", gps)
        self.assertIn("gate.invalidate()", gps)

    def test_legacy_77kwh_fake_bms_and_passenger_bypass_not_added(self):
        # The uploaded ZIP is a standalone uncompiled prototype, not the app.
        source_paths = list((NATIVE / "src/main/java").rglob("*.kt"))
        source = "\n".join(p.read_text(encoding="utf-8") for p in source_paths)
        self.assertNotIn("fun passengerMode(", source)
        self.assertNotIn('Text("VW ID.4 Pro • 77 kWh', source)
        self.assertNotIn("Obd.value(b, 1, 1, 2.5)", source)
        self.assertFalse((NATIVE / "src/main/java/tr/yolarkadasi").exists())


if __name__ == "__main__":
    unittest.main()
