#!/usr/bin/env python3
"""Fail closed if legacy YouTube ad-filter implementation reaches source or Debug APK.

The scanner checks all Android source sets, not just nativecockpit. DEX checks
apply to the exact APK supplied by CI. Debug is unminified, so its class names
remain inspectable. A pass is not a full behavioral audit of a different APK.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import re
import sys
from zipfile import BadZipFile, ZipFile

BLOCKED = re.compile(rb"youtube[_-]?ad[_-]?filter", re.IGNORECASE)
SOURCE_EXTENSIONS = {".kt", ".java", ".js", ".html", ".xml"}

def check_sources(root: Path) -> list[str]:
    failures: list[str] = []
    for module in ("nativecockpit", "app", "evlite", "media-engine"):
        source = root / module / "src"
        if not source.is_dir():
            continue
        for path in source.rglob("*"):
            if not path.is_file() or path.suffix.lower() not in SOURCE_EXTENSIONS:
                continue
            if BLOCKED.search(path.name.encode("utf-8")) or BLOCKED.search(path.read_bytes()):
                failures.append(f"Forbidden YouTube filter source: {path.relative_to(root)}")
    return failures

def check_apk(path: Path) -> list[str]:
    if not path.is_file():
        return [f"APK absent: {path}"]
    try:
        with ZipFile(path) as archive:
            dex_files = [name for name in archive.namelist()
                         if re.fullmatch(r"classes\d*\.dex", name)]
            if not dex_files:
                return ["APK has no classes*.dex to inspect"]
            return [f"Forbidden YouTube filter in APK: {name}"
                    for name in dex_files if BLOCKED.search(archive.read(name))]
    except (OSError, BadZipFile) as exc:
        return [f"Unreadable APK: {type(exc).__name__}"]

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--apk", type=Path, help="Inspect actual build classes*.dex")
    args = parser.parse_args()
    errors = check_sources(args.root)
    if args.apk is not None:
        errors.extend(check_apk(args.apk))
    if errors:
        print("\n".join("FAIL: " + error for error in errors), file=sys.stderr)
        return 1
    print("PASS: no legacy YouTube ad-filter class/source in checked Android modules" +
          (" or supplied Debug APK" if args.apk else ""))
    return 0

if __name__ == "__main__":
    sys.exit(main())
