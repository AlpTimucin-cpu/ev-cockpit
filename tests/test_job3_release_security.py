"""Job 3: native release security guardrails; no private signing data or fake measurements."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


class ReleaseSecurityPolicyTest(unittest.TestCase):
    def test_android_manifest_is_private_and_https_only(self):
        manifest = read("nativecockpit/src/main/AndroidManifest.xml")
        self.assertIn('android:allowBackup="false"', manifest)
        self.assertIn('android:usesCleartextTraffic="false"', manifest)
        self.assertIn('android:networkSecurityConfig="@xml/network_security_config"', manifest)
        self.assertIn('android:exported="false"', manifest)
        security = read("nativecockpit/src/main/res/xml/network_security_config.xml")
        self.assertIn('cleartextTrafficPermitted="false"', security)
        self.assertIn('<certificates src="system" />', security)
        self.assertNotIn('src="user"', security)

    def test_release_is_hardened_debug_is_separate(self):
        gradle = read("nativecockpit/build.gradle.kts")
        release = gradle.split('getByName("release")', 1)[1].split('compileOptions', 1)[0]
        debug = gradle.split('getByName("debug")', 1)[1].split('getByName("release")', 1)[0]
        for declaration in (
            'isDebuggable = false',
            'isMinifyEnabled = true',
            'isShrinkResources = true',
            'abiFilters += "arm64-v8a"',
        ):
            self.assertIn(declaration, release)
        self.assertIn('applicationIdSuffix = ".debug"', debug)
        self.assertIn('versionNameSuffix = "-DEBUG"', debug)
        self.assertNotIn('versionNameSuffix = "-TEST"', debug)
        self.assertIn('throw GradleException(', gradle)
        self.assertIn("signedReleaseReady", gradle)

    def test_local_incident_storage_namespace_is_not_api_credential(self):
        source = read("media-engine/overlay/fermata/src/main/assets/evcockpit/evcockpit-live-map.js")
        self.assertIn("incidentStorageNamespace:", source)
        self.assertIn("window.localStorage.getItem(CFG.incidentStorageNamespace)", source)
        self.assertIn("window.localStorage.setItem(CFG.incidentStorageNamespace", source)
        self.assertNotIn("localIncidentKey", source)

    def test_signed_release_workflow_remains_safely_held(self):
        workflow = read(".github/workflows/release.yml")
        self.assertRegex(workflow, r"(?m)^    if: false\s*$")
        self.assertNotIn("allow_temporary_signing: true", workflow)

    def test_weather_policy_is_foreground_and_declares_real_interval(self):
        policy = read("nativecockpit/src/main/java/com/evcockpit/car/CarWeatherPresentationPolicy.kt")
        screen = read("nativecockpit/src/main/java/com/evcockpit/car/EyaCarNavigationScreen.kt")
        self.assertIn("const val AUTOMATIC_REFRESH_MS = 30L * 60_000L", policy)
        self.assertIn("if (!hasVerifiedGpsRouteProgress) return false", policy)
        self.assertIn("weatherRefreshGate.markAttempt(routeId, nowMs)", screen)
        self.assertIn("maybeRefreshWeather(r, f, now)", screen)
        self.assertNotIn("PeriodicWorkRequest", screen)


if __name__ == "__main__":
    unittest.main()
