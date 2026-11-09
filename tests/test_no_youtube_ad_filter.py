"""Regression tests for source and actual debug-DEX YouTube filter absence."""
from pathlib import Path
import importlib.util
import tempfile
import unittest
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "verify_youtube_filter_absent.py"
SPEC = importlib.util.spec_from_file_location("youtube_filter_guard", SCRIPT)
guard = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(guard)


class YoutubeFilterGuardTests(unittest.TestCase):
    def test_detects_forbidden_source_class(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            path = root / "nativecockpit" / "src" / "main" / "java" / "Unsafe.kt"
            path.parent.mkdir(parents=True)
            path.write_text("class YoutubeAdFilter {}", encoding="utf-8")
            self.assertEqual(1, len(guard.check_sources(root)))

    def test_allows_normal_youtube_navigation_policy(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            path = root / "app" / "src" / "main" / "YoutubePagePolicy.kt"
            path.parent.mkdir(parents=True)
            path.write_text("object YoutubePagePolicy {}", encoding="utf-8")
            self.assertEqual([], guard.check_sources(root))

    def test_detects_embedded_class_in_unminified_apk_dex(self):
        with tempfile.TemporaryDirectory() as d:
            apk = Path(d) / "debug.apk"
            with ZipFile(apk, "w") as z:
                z.writestr("classes.dex", b"dex\x00Lcom/example/YoutubeAdFilter;")
            self.assertTrue(guard.check_apk(apk))

    def test_native_phone_webview_does_not_intercept_youtube_ads_or_inject_skips(self):
        screen = (ROOT / "nativecockpit/src/main/java/com/evcockpit/presentation/media/MediaWebViewScreen.kt")
        text = screen.read_text(encoding="utf-8")
        self.assertNotIn("shouldInterceptRequest(", text)
        self.assertNotIn("YoutubeAdFilter", text)
        self.assertNotIn("ad-showing", text)
        self.assertNotIn(".ytp-ad-skip", text)
        self.assertIn("onReceivedSslError(", text)
        self.assertIn("MediaSitePolicy.allows(", text)
        self.assertIn("CookieManager.getInstance().flush()", text)

    def test_clean_dex_and_missing_apk_outcomes(self):
        with tempfile.TemporaryDirectory() as d:
            apk = Path(d) / "debug.apk"
            with ZipFile(apk, "w") as z:
                z.writestr("classes.dex", b"dex\x00Lcom/example/YoutubePagePolicy;")
            self.assertEqual([], guard.check_apk(apk))
            self.assertTrue(guard.check_apk(Path(d) / "missing.apk"))


if __name__ == "__main__":
    unittest.main()
