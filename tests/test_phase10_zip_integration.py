"""Phase 10: verified archive provenance and explicit user-controlled ZIP integration.

CI does not embed or execute the ZIP. Source and Kotlin/JUnit tests complement
each other; phone, Android Auto and VW hardware QA remain separate.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CODE = ROOT / "nativecockpit/src/main/java/com/evcockpit"


def code(path):
    return (CODE / path).read_text(encoding="utf-8")


class Phase10ZipIntegrationTest(unittest.TestCase):
    def test_candidate_specs_have_provenance_without_auto_verification(self):
        reference = code("domain/model/ZipId4PureReference.kt")
        settings = code("presentation/settings/SettingsScreen.kt")
        config = code("domain/model/AppConfiguration.kt")
        store = code("data/settings/AppConfigRepository.kt")
        self.assertIn(
            "9f66d0b1302789b7377ae82b94545daad198a02003c69625f7cf5ab4e413e62a",
            reference
        )
        for literal in ("52.0", "145.0", "11.0", "354", "363", "125", "170"):
            self.assertIn(literal, reference)
        self.assertIn("fun stagedDraft(current: VehicleProfileConfig)", reference)
        self.assertIn("userConfirmed = false", reference)
        self.assertIn("ZipId4PureReference.stagedDraft(configuration.vehicle)", settings)
        self.assertIn("zipDraftStaged = true", settings)
        self.assertIn("Kaydedilmedi", settings.replace("kaydedilmedi", "Kaydedilmedi"))
        self.assertIn("viewModel.saveVehicleConfig(", settings)
        self.assertIn("userConfirmed: Boolean = false", config)
        self.assertIn("p[Keys.CONFIRMED] = profile.userConfirmed", store)
        self.assertNotIn("ZipId4PureReference", store)

    def test_reported_consumption_is_optional_editable_scenario_not_soc(self):
        scenario = code("domain/model/UserDrivingScenarios.kt")
        screen = code("presentation/settings/SettingsScreen.kt")
        self.assertIn("10.0, 11.0", scenario)
        self.assertIn("17.0, 19.0", scenario)
        self.assertIn("UserDrivingScenarios.summer", screen)
        self.assertIn("UserDrivingScenarios.winter", screen)
        self.assertNotIn("socPercent", scenario)

    def test_zip_audio_shortcuts_are_explicit_official_apps_only(self):
        media = code("presentation/media/MediaHomeScreen.kt")
        launcher = code("presentation/media/MediaLauncher.kt")
        manifest = (ROOT / "nativecockpit/src/main/AndroidManifest.xml").read_text(
            encoding="utf-8"
        )
        self.assertIn('SPOTIFY("Spotify", "com.spotify.music")', launcher)
        self.assertIn(
            'YOUTUBE_MUSIC("YouTube Music", "com.google.android.apps.youtube.music")',
            launcher
        )
        self.assertIn("MediaLauncher.launchOfficialMusicApp(context, app)", media)
        self.assertIn("PhoneVideoSafetyPolicy.allow(", media)
        self.assertIn("allowVideo = false", media)
        for package in ("com.spotify.music", "com.google.android.apps.youtube.music"):
            self.assertIn(f'android:name="{package}"', manifest)
        self.assertNotIn("passengerMode", media)
        self.assertNotIn("android.intent.action.VIEW", launcher)
        car = code("car/EyaCarAppService.kt")
        self.assertNotIn("MediaWebViewScreen", car)
        self.assertNotIn("launchOfficialMusicApp", car)

    def test_old_zip_source_and_unverified_bms_were_not_ported(self):
        java_root = ROOT / "nativecockpit/src/main/java"
        self.assertFalse((java_root / "tr/yolarkadasi").exists())
        obd = code("data/repository/Elm327VehicleRepository.kt")
        self.assertNotIn("Obd.value(b, 1, 1, 2.5)", obd)
        self.assertNotIn("socEvidence", obd)
        self.assertIn('protocol.readRawOnce("010D")', obd)


if __name__ == "__main__":
    unittest.main()
