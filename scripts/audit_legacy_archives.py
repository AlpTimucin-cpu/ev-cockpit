#!/usr/bin/env python3
"""Inspect historic source ZIPs, without extraction or publication.

Produces evidence (SHA-256, entry count, size and redacted finding codes).
Never prints file contents, secret values or their match context. A passing
report does NOT establish correspondence to a current Android Release.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import stat
import sys
from zipfile import BadZipFile, ZipFile

ARCHIVES = (
    "EV_Cockpit_v0.8_Source.zip",
    "EV_Cockpit_Legacy_Source_v0.8.zip",
)
MAX_ARCHIVE_BYTES = 100 * 1024 * 1024
MAX_UNCOMPRESSED_BYTES = 150 * 1024 * 1024
MAX_MEMBER_BYTES = 75 * 1024 * 1024
MAX_ENTRIES = 10000

UNSAFE_BASENAMES = {
    ".env", ".env.local", ".env.production", "local.properties",
    "google-services.json", "secrets.properties", "id_rsa", "id_ed25519",
}
UNSAFE_SUFFIXES = {".p12", ".pfx", ".jks", ".keystore", ".pem", ".key"}
CREDENTIAL_SIGNATURES = (
    ("private-key-header", re.compile(
        rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"
    )),
    ("github-personal-token", re.compile(
        rb"(?:github_pat_[A-Za-z0-9_]{30,}|ghp_[A-Za-z0-9]{30,})"
    )),
    ("google-api-key", re.compile(rb"AIza[A-Za-z0-9_-]{35}")),
    ("openai-style-api-key", re.compile(
        rb"sk-(?:proj-)?[A-Za-z0-9_-]{28,}"
    )),
)

def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()

def safe_path(name: str) -> bool:
    if not name or name.startswith(("/", "\\")) or "\\" in name:
        return False
    if re.match(r"^[A-Za-z]:", name) or any(ord(c) < 32 for c in name):
        return False
    parts = PurePosixPath(name).parts
    return bool(parts) and all(part not in ("..", ".") for part in parts)

def audit_one(path: Path) -> dict:
    result = {
        "filename": path.name,
        "status": "BLOCK",
        "sha256": None,
        "archive_bytes": None,
        "file_entries": 0,
        "uncompressed_bytes": 0,
        "findings": [],
    }
    findings: list[dict[str, str]] = result["findings"]
    if not path.is_file():
        findings.append({"code": "missing-archive"})
        return result
    size = path.stat().st_size
    result["archive_bytes"] = size
    if size > MAX_ARCHIVE_BYTES:
        findings.append({"code": "oversize-archive"})
        return result
    result["sha256"] = digest(path)
    try:
        with ZipFile(path) as archive:
            members = archive.infolist()
            if not members or len(members) > MAX_ENTRIES:
                findings.append({"code": "invalid-entry-count"})
                return result
            names: set[str] = set()
            total = 0
            for member in members:
                # Never use member.filename as a filesystem path.
                name = member.filename
                if not safe_path(name):
                    findings.append({"code": "unsafe-entry-path"})
                    continue
                key = name.casefold()
                if key in names:
                    findings.append({"code": "duplicate-entry-name"})
                    continue
                names.add(key)
                if member.flag_bits & 1:
                    findings.append({"code": "encrypted-member"})
                    continue
                if stat.S_ISLNK(member.external_attr >> 16):
                    findings.append({"code": "symlink-member"})
                    continue
                if member.is_dir():
                    continue
                result["file_entries"] += 1
                total += member.file_size
                if member.file_size > MAX_MEMBER_BYTES or total > MAX_UNCOMPRESSED_BYTES:
                    findings.append({"code": "unsafe-uncompressed-size"})
                    break
                if (member.file_size > 1024 * 1024 and
                    member.file_size / max(member.compress_size, 1) > 500):
                    findings.append({"code": "extreme-compression-ratio"})
                    continue
                basename = PurePosixPath(name).name.lower()
                if basename in UNSAFE_BASENAMES or any(
                    basename.endswith(suffix) for suffix in UNSAFE_SUFFIXES
                ):
                    findings.append({"code": "credential-filename"})
                    continue
                # Stream, not extract. This checks content in the already public ZIP.
                with archive.open(member) as item:
                    carry = b""
                    while True:
                        chunk = item.read(65536)
                        if not chunk:
                            break
                        window = carry + chunk
                        for code, regex in CREDENTIAL_SIGNATURES:
                            if regex.search(window):
                                # Finding code only; do not log matching value.
                                if not any(x["code"] == code for x in findings):
                                    findings.append({"code": code})
                        carry = window[-128:]
            result["uncompressed_bytes"] = total
            # Verify CRC even if the format parser accepted the directory.
            if not findings and archive.testzip() is not None:
                findings.append({"code": "zip-crc-error"})
    except (OSError, ValueError, BadZipFile, RuntimeError, EOFError):
        findings.append({"code": "unreadable-zip"})
    if not findings and result["file_entries"] > 0:
        result["status"] = "PASS"
    else:
        if result["file_entries"] == 0 and not findings:
            findings.append({"code": "no-files"})
    return result

def run(root: Path, names: tuple[str, ...] = ARCHIVES) -> dict:
    if len(set(names)) != len(names):
        raise ValueError("archive names must be unique")
    archives = [audit_one(root / name) for name in names]
    return {
        "schema": "ev-cockpit-legacy-archive-audit-v1",
        "purpose": "Historical source integrity only; NOT signed APK or current source",
        "can_publish_source_archive": all(x["status"] == "PASS" for x in archives),
        "archives": archives,
    }

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--report", type=Path, default=None)
    args = parser.parse_args()
    report = run(args.root)
    text = json.dumps(report, sort_keys=True, indent=2, ensure_ascii=False) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(text, encoding="utf-8")
    print(text)
    if not report["can_publish_source_archive"]:
        print("BLOCK: do not upload legacy source ZIP to GitHub Releases", file=sys.stderr)
        return 1
    print("PASS: archive structure, integrity and selected secret signatures")
    return 0

if __name__ == "__main__":
    sys.exit(main())
