#!/usr/bin/env python3
"""Read-only APK inventory for EV Cockpit.

No APK is launched, installed or extracted. The optional Android SDK aapt
tool reads package/permission metadata only. Never prints stored URLs or keys.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import zipfile
from collections import Counter
from pathlib import Path

MAX_APK = 350 * 1024 * 1024
MAX_ENTRIES = 30000
MAX_UNCOMPRESSED = 2 * 1024 * 1024 * 1024


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def android_sdk_metadata(path: Path) -> dict:
    aapt = shutil.which("aapt")
    if not aapt:
        return {"sdk_metadata": "aapt not installed; metadata unavailable"}
    try:
        result = subprocess.run([aapt, "dump", "badging", str(path)],
                                capture_output=True, text=True, timeout=15)
        if result.returncode != 0:
            return {"sdk_metadata": "aapt could not read metadata"}
        info = {}
        match = re.search(
            r"^package: name='([^']+)' versionCode='([^']*)' versionName='([^']*)'",
            result.stdout, re.M)
        if match:
            info.update(package=match.group(1), version_code=match.group(2),
                        version_name=match.group(3))
        info["permissions"] = sorted(set(re.findall(
            r"^uses-permission(?:-sdk-[0-9]+)?: name='([^']+)'",
            result.stdout, re.M)))
        for key, pattern in {
            "min_sdk": r"^sdkVersion:'([^']+)'",
            "target_sdk": r"^targetSdkVersion:'([^']+)'",
        }.items():
            matched = re.search(pattern, result.stdout, re.M)
            if matched:
                info[key] = matched.group(1)
        return info
    except (OSError, subprocess.TimeoutExpired):
        return {"sdk_metadata": "aapt invocation unavailable"}


def inspect(path: Path) -> dict:
    if not path.is_file() or path.suffix.lower() != ".apk":
        raise ValueError("Existing .apk file required")
    size = path.stat().st_size
    if size > MAX_APK:
        raise ValueError("APK exceeds 350 MB audit limit")
    result = {"file": path.name, "bytes": size, "sha256": sha256(path)}
    with zipfile.ZipFile(path, "r") as archive:
        entries = archive.infolist()
        if len(entries) > MAX_ENTRIES:
            raise ValueError("APK contains too many ZIP entries")
        unpacked = sum(item.file_size for item in entries)
        if unpacked > MAX_UNCOMPRESSED:
            raise ValueError("APK has excessive uncompressed size")
        if any(item.file_size > 400 * max(item.compress_size, 1024 * 1024)
               for item in entries):
            raise ValueError("Suspicious compression ratio")
        files = [item.filename for item in entries if not item.is_dir()]
        counts = Counter()
        for name in files:
            low = name.lower()
            if re.fullmatch(r"classes\d*\.dex", name):
                counts["dex"] += 1
            elif low.startswith("lib/") and low.endswith(".so"):
                counts["native_libs"] += 1
            elif low.startswith("res/"):
                counts["resources"] += 1
            elif low.startswith("assets/"):
                counts["assets"] += 1
        result["has_manifest"] = "AndroidManifest.xml" in files
        result["has_resource_table"] = "resources.arsc" in files
        result["zip_entries"] = len(entries)
        result["uncompressed_bytes"] = unpacked
        result["file_counts"] = dict(counts)
        result["native_abis"] = sorted({
            parts[1] for name in files
            if (parts := name.split("/"))[0] == "lib" and len(parts) > 2
        })
        joined = " ".join(files).lower()
        result["dependency_filename_hints"] = sorted(
            hint for hint, marker in {
                "FFmpeg": "ffmpeg", "VLC": "libvlc",
                "ExoPlayer": "exoplayer", "Flutter": "libflutter.so",
                "React Native": "libreactnative",
            }.items() if marker in joined
        )
    result.update(android_sdk_metadata(path))
    return result


def main() -> int:
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("apks", nargs="+", type=Path)
    cli.add_argument("--out", type=Path, default=Path("apk_inventory_report.json"))
    args = cli.parse_args()
    results = []
    for path in args.apks:
        try:
            results.append(inspect(path))
            print("Scanned:", path.name, file=sys.stderr)
        except (ValueError, OSError, zipfile.BadZipFile) as exc:
            results.append({"file": path.name, "error": str(exc)})
            print("Skipped:", path.name, file=sys.stderr)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps({
        "format": "evcockpit-apk-inventory-v1",
        "apk_executed": False, "items": results
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(args.out)
    return 0 if all("error" not in x for x in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
