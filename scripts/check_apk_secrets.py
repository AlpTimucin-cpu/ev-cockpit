#!/usr/bin/env python3
"""Fail-closed, no-secret-output checks of built Android APK ZIP members.

This heuristic supplements (not replaces) repository secret scanning. It scans
DECOMPRESSED entries, including DEX and binary resources. No suspect bytes are
printed to stdout or logs.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from zipfile import BadZipFile, ZipFile

MAX_MEMBER_BYTES = 256 * 1024 * 1024
MAX_TOTAL_BYTES = 1024 * 1024 * 1024
CHUNK_BYTES = 1024 * 1024
OVERLAP_BYTES = 4096

SENSITIVE_NAME = re.compile(
    r"(?:^|/)(?:EYA_GitHub_TEK_SECRET\.txt|[^/]*\.(?:jks|keystore|p12|pfx|pem|key)|"
    r"(?:\.env(?:\.[^/]*)?|google-services\.json))$",
    re.IGNORECASE,
)

# Recognized key-bearing configuration files must never ship with nonempty keys.
CONFIG_FILE = re.compile(r"(?:^|/)(?:traffic-config\.js|.*tomtom.*config.*\.json)$", re.IGNORECASE)
QUOTED_TOMTOM_VALUE = re.compile(
    rb"(?i)\b(?:tomtomKey|tomtomApiKey|ID4_TOMTOM_API_KEY)\b"
    rb"\s*[\"']?\s*[:=]\s*[\"']([^\"'\r\n\x00]+)[\"']"
)
URL_KEY = re.compile(rb"(?i)[?&](?:key|api[_-]?key|tomtomKey)=([a-z0-9_.-]{12,})")
BASE64_KEYSTORE = re.compile(
    rb'(?i)[\"\']?keystore_base64[\"\']?\s*:\s*[\"\'][A-Za-z0-9+/]{128,}={0,2}[\"\']'
)
PEM_KEY = re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----")
AWS_KEY = re.compile(rb"\bAKIA[0-9A-Z]{16}\b")
JKS_HEADERS = (bytes.fromhex("feedfeed00000001"), bytes.fromhex("feedfeed00000002"))


def suspicious_values(data: bytes, config: bool) -> set[str]:
    flags: set[str] = set()
    if PEM_KEY.search(data):
        flags.add("private-key-marker")
    if BASE64_KEYSTORE.search(data) or any(header in data for header in JKS_HEADERS):
        flags.add("embedded-keystore-material")
    if AWS_KEY.search(data):
        flags.add("cloud-key-marker")
    if URL_KEY.search(data):
        flags.add("nonempty-url-api-key")
    if config and any(match.group(1).strip() for match in QUOTED_TOMTOM_VALUE.finditer(data)):
        flags.add("nonempty-tomtom-config")
    return flags


def scan_apk(path: Path) -> list[tuple[str, str]]:
    problems: set[tuple[str, str]] = set()
    total = 0
    try:
        with ZipFile(path) as apk:
            entries = apk.infolist()
            if not entries:
                return [("APK", "empty-archive")]
            for entry in entries:
                if entry.is_dir():
                    continue
                member = entry.filename
                if SENSITIVE_NAME.search(member):
                    problems.add((member, "sensitive-filename"))
                if entry.file_size > MAX_MEMBER_BYTES:
                    problems.add((member, "too-large-to-scan"))
                    continue
                total += entry.file_size
                if total > MAX_TOTAL_BYTES:
                    problems.add(("APK", "scan-size-limit-exceeded"))
                    break
                config = bool(CONFIG_FILE.search(member))
                prior = b""
                with apk.open(entry, "r") as source:
                    while (part := source.read(CHUNK_BYTES)):
                        scan = prior + part
                        for tag in suspicious_values(scan, config):
                            problems.add((member, tag))
                        prior = scan[-OVERLAP_BYTES:]
    except (OSError, BadZipFile, RuntimeError, ValueError):
        # Never echo exception details: they can contain hostile/sensitive names.
        return [("APK", "unreadable-or-invalid-zip")]
    return sorted(problems)


def main(argv: list[str]) -> int:
    if not argv:
        print("Usage: check_apk_secrets.py <APK> [APK...]; no APK supplied", file=sys.stderr)
        return 2
    failures = 0
    for name in argv:
        path = Path(name)
        if not path.is_file() or not path.stat().st_size:
            print("FAIL: requested APK unavailable or empty", file=sys.stderr)
            failures += 1
            continue
        found = scan_apk(path)
        if found:
            failures += 1
            # Do not print filenames or matched content: filenames can include secrets.
            classes = ", ".join(sorted({reason for _, reason in found}))
            print(f"FAIL: APK secret safety check ({len(found)} findings; categories: {classes})", file=sys.stderr)
        else:
            print("PASS: APK scanned, no configured key patterns detected")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
