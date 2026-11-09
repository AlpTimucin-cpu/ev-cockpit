"""Offline, deliberately fictitious fixtures for the APK safety gate."""

import importlib.util
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "check_apk_secrets.py"
SPEC = importlib.util.spec_from_file_location("check_apk_secrets", MODULE_PATH)
scanner = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(scanner)


class ApkSecretScannerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.apk = Path(self.tmp.name) / "sample.apk"

    def pack(self, *entries):
        with ZipFile(self.apk, "w") as archive:
            for filename, content in entries:
                archive.writestr(filename, content)
        return scanner.scan_apk(self.apk)

    def test_safe_empty_config(self):
        self.assertEqual([], self.pack(("assets/id4/traffic-config.js", 'window.ID4_TRAFFIC_CONFIG={tomtomKey:""};')))

    def test_safe_no_config(self):
        self.assertEqual([], self.pack(("classes.dex", b"dex\x00neutral")))

    def test_nonempty_config_rejected(self):
        categories = [x[1] for x in self.pack(("assets/id4/traffic-config.js", 'tomtomKey:"FAKE_TEST_ONLY"'))]
        self.assertIn("nonempty-tomtom-config", categories)

    def test_embedded_token_in_url_rejected(self):
        categories = [x[1] for x in self.pack(("assets/site.js", "https://maps.example/?key=FAKE_TEST_STRING_123"))]
        self.assertIn("nonempty-url-api-key", categories)

    def test_keystore_file_rejected(self):
        categories = [x[1] for x in self.pack(("assets/owner.jks", b"test"))]
        self.assertIn("sensitive-filename", categories)

    def test_private_key_marker_rejected(self):
        categories = [x[1] for x in self.pack(("assets/fake.txt", "-----BEGIN PRIVATE KEY-----"))]
        self.assertIn("private-key-marker", categories)

    def test_binary_magic_rejected(self):
        categories = [x[1] for x in self.pack(("res/raw/a", bytes.fromhex("feedfeed00000002") + b"test"))]
        self.assertIn("embedded-keystore-material", categories)

    def test_empty_zip_rejected(self):
        with ZipFile(self.apk, "w"):
            pass
        self.assertIn(("APK", "empty-archive"), scanner.scan_apk(self.apk))

    def test_invalid_archive_rejected(self):
        self.apk.write_text("this is not a zip")
        self.assertIn(("APK", "unreadable-or-invalid-zip"), scanner.scan_apk(self.apk))

    def test_main_requires_argument(self):
        self.assertEqual(2, scanner.main([]))


if __name__ == "__main__":
    unittest.main()
