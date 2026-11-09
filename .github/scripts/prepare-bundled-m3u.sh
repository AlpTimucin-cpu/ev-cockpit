#!/usr/bin/env bash
set -euo pipefail
# Download the user-provided, publicly shared Drive playlist at BUILD TIME.
# Its signed third-party media addresses are never committed to GitHub source.
# The result is intentionally packaged in the APK only after verifying the
# exact original file hash and all 7,232 EXTINF records.
python3 - <<'PY'
from pathlib import Path
from urllib.request import Request, urlopen
import hashlib, subprocess, sys

FILE_ID = "1sRgSkbfTSDfPZHluLnanSynWeQA0ru7k"
EXPECTED_SHA256 = "b337c57ae71a3be32d64b52048c32d2d2d66c3316dad64630ae6b9983bc2f011"
PATH = Path("app/src/main/assets/filmler_full.m3u")
PATH.parent.mkdir(parents=True, exist_ok=True)

def valid(data):
    return (
        3_000_000 <= len(data) <= 8_000_000
        and data[:12].lstrip(b"\xef\xbb\xbf").startswith(b"#EXTM3U")
        and data.count(b"#EXTINF:") == 7232
        and hashlib.sha256(data).hexdigest() == EXPECTED_SHA256
    )

urls = [
    "https://drive.google.com/uc?export=download&id=" + FILE_ID,
    "https://drive.usercontent.google.com/download?id=" + FILE_ID + "&export=download&confirm=t",
]
data = None
for url in urls:
    try:
        req = Request(url, headers={"User-Agent": "Mozilla/5.0 (compatible; EVCOCKPITBuild/1.0)"})
        with urlopen(req, timeout=40) as response:
            candidate = response.read(8_000_001)
        if valid(candidate):
            data = candidate
            break
    except Exception as exc:
        print("Direct Drive fetch unavailable:", type(exc).__name__, file=sys.stderr)

if data is None:
    # Public Google Drive file links may require a cookie-based confirmation.
    # Use gdown solely as a robust file download transport.
    subprocess.check_call([sys.executable, "-m", "pip", "install", "--quiet", "gdown==5.2.0"])
    import gdown
    temporary = str(PATH) + ".tmp"
    result = gdown.download(id=FILE_ID, output=temporary, quiet=True, fuzzy=False)
    if result:
        candidate = Path(temporary).read_bytes()
        if valid(candidate):
            data = candidate
    Path(temporary).unlink(missing_ok=True)

if data is None:
    raise SystemExit("Complete original M3U could not be fetched or checksum did not match. APK build aborted.")

PATH.write_bytes(data)
print("Bundled full owner-provided M3U; entries=7232; bytes=",len(data))
# Never echo signed third-party media URLs in CI logs.
PY
