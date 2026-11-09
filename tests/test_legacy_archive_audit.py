"""No extraction, no secrets in output, and integrity checks for archived sources."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile, ZipInfo

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "audit_legacy_archives.py"
SPEC = importlib.util.spec_from_file_location("archive_audit", SCRIPT)
assert SPEC and SPEC.loader
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)


class ArchiveAuditTest(unittest.TestCase):
    def make_zip(self, directory: Path, contents: dict[str, bytes]) -> Path:
        out = directory / "archive.zip"
        with ZipFile(out, "w") as z:
            for name, data in contents.items():
                z.writestr(name, data)
        return out

    def test_good_archive_has_sha256_and_does_not_modify_content(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            f = self.make_zip(root, {"README.md": b"Historic source only"})
            before = f.read_bytes()
            result = audit.audit_one(f)
            self.assertEqual("PASS", result["status"])
            self.assertEqual(1, result["file_entries"])
            self.assertEqual(64, len(result["sha256"]))
            self.assertEqual(before, f.read_bytes())

    def test_rejects_zip_slip_and_absolute_paths(self):
        for malicious in ("../escape.txt", "/absolute.txt", "C:/Windows/file.txt",
                          "parent\\child.txt"):
            with self.subTest(malicious=malicious), tempfile.TemporaryDirectory() as d:
                f = self.make_zip(Path(d), {malicious: b"bad"})
                result = audit.audit_one(f)
                self.assertEqual("BLOCK", result["status"])
                self.assertIn({"code": "unsafe-entry-path"}, result["findings"])

    def test_blocks_private_key_files_and_secret_values_without_printing_them(self):
        secret = b"-----BEGIN PRIVATE KEY-----\nINTERNAL_SECRET_MUST_NOT_PRINT\n"
        with tempfile.TemporaryDirectory() as d:
            f = self.make_zip(Path(d), {
                "app/src/private.jks": b"binary-data",
                "README.md": secret,
            })
            result = audit.audit_one(f)
            self.assertEqual("BLOCK", result["status"])
            codes = {item["code"] for item in result["findings"]}
            self.assertIn("credential-filename", codes)
            self.assertIn("private-key-header", codes)
            self.assertNotIn("INTERNAL_SECRET_MUST_NOT_PRINT", str(result))
            self.assertNotIn("private.jks", str(result))

    def test_disallows_symlinks_and_duplicates(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "archive.zip"
            symlink = ZipInfo("assets/linked")
            symlink.create_system = 3
            symlink.external_attr = 0o120777 << 16
            with ZipFile(f, "w") as archive:
                archive.writestr("assets/a.txt", b"one")
                archive.writestr("ASSETS/A.TXT", b"two")
                archive.writestr(symlink, b"target")
            result = audit.audit_one(f)
            self.assertEqual("BLOCK", result["status"])
            codes = {item["code"] for item in result["findings"]}
            self.assertIn("duplicate-entry-name", codes)
            self.assertIn("symlink-member", codes)

    def test_missing_archive_blocks_and_does_not_fabricate_sha256(self):
        with tempfile.TemporaryDirectory() as d:
            result = audit.audit_one(Path(d) / "not-here.zip")
            self.assertEqual("BLOCK", result["status"])
            self.assertIsNone(result["sha256"])

    def test_report_never_calls_old_source_a_current_release(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            self.make_zip(root, {"README.md": b"sample"}).rename(root / "old.zip")
            report = audit.run(root, names=("old.zip", "missing.zip"))
            self.assertFalse(report["can_publish_source_archive"])
            self.assertEqual(2, len(report["archives"]))
            self.assertIn("NOT signed APK", report["purpose"])

    def test_crc_corruption_fails_archive_audit(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            f = self.make_zip(root, {"README.md": b"abc-def-ghi"})
            data = f.read_bytes()
            offset = data.find(b"abc-def-ghi")
            self.assertNotEqual(-1, offset)
            f.write_bytes(data[:offset] + b"X" + data[offset+1:])
            result = audit.audit_one(f)
            self.assertEqual("BLOCK", result["status"])
            self.assertIn("unreadable-zip", {x["code"] for x in result["findings"]})


if __name__ == "__main__":
    unittest.main()
