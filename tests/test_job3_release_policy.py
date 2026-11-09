"""Fail-closed source-only policy tests; device and signed Release remain unverified."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


class Job3ReleasePolicyTests(unittest.TestCase):
    def test_release_compiler_security_and_variant_identity(self):
        gradle = (ROOT / "nativecockpit/build.gradle.kts").read_text()
        self.assertIn('versionName = "1.0.8"', gradle)
        self.assertIn('applicationIdSuffix = ".debug"', gradle)
        self.assertIn('versionNameSuffix = "-DEBUG"', gradle)
        self.assertNotIn('versionNameSuffix = "-TEST"', gradle)
        self.assertRegex(gradle, r'(?s)getByName\("release"\)\s*\{.*?isDebuggable\s*=\s*false')
        self.assertIn('ndk { abiFilters += "arm64-v8a" }', gradle)
        self.assertIn("isMinifyEnabled = true", gradle)
        self.assertIn("isShrinkResources = true", gradle)
        self.assertIn('"proguard-rules.pro"', gradle)

    def test_manifest_and_network_security_defaults(self):
        manifest = ET.parse(ROOT / "nativecockpit/src/main/AndroidManifest.xml").getroot()
        app = manifest.find("application")
        self.assertIsNotNone(app)
        self.assertEqual("false", app.attrib[ANDROID_NS + "allowBackup"])
        self.assertEqual("false", app.attrib[ANDROID_NS + "usesCleartextTraffic"])
        self.assertEqual("@xml/network_security_config",
                         app.attrib[ANDROID_NS + "networkSecurityConfig"])
        network = ET.parse(ROOT / "nativecockpit/src/main/res/xml/network_security_config.xml").getroot()
        self.assertEqual("network-security-config", network.tag)
        base = network.find("base-config")
        self.assertEqual("false", base.attrib.get("cleartextTrafficPermitted"))
        anchors = base.find("trust-anchors")
        self.assertEqual(["system"], [n.attrib.get("src") for n in anchors.findall("certificates")])
        self.assertEqual([], network.findall("domain-config"))
        self.assertEqual([], network.findall("debug-overrides"))

    def test_old_production_signing_workflow_stays_held(self):
        legacy = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8-sig")
        self.assertIn("if: false", legacy)
        smoke = (ROOT / ".github/workflows/verify-native-release-smoke.yml").read_text()
        self.assertIn("contents: read", smoke)
        self.assertIn("no-distribution", smoke.lower().replace(" ", "-"))
        self.assertIn("EYA_RELEASE_STORE_FILE", smoke)
        self.assertIn("-validity 1", smoke)
        self.assertNotIn("gh release create", smoke)
        self.assertNotIn("gh release upload", smoke)

    def test_foreground_weather_only_and_no_unapproved_obd_commands(self):
        screen = (ROOT / "nativecockpit/src/main/java/com/evcockpit/car/EyaCarNavigationScreen.kt").read_text()
        policy = (ROOT / "nativecockpit/src/main/java/com/evcockpit/car/CarWeatherPresentationPolicy.kt").read_text()
        self.assertIn("maybeRefreshWeather(r, f, now)", screen)
        self.assertIn("hasVerifiedGpsRouteProgress", policy)
        self.assertIn("30L * 60_000L", policy)
        self.assertIn("markAttempt", screen)
        self.assertNotIn("PeriodicWorkRequest", screen)
        self.assertNotIn("WorkManager", screen)


if __name__ == "__main__":
    unittest.main()
