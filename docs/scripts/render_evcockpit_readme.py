#!/usr/bin/env python3
"""Screenshot the REAL navigator.html DOM/CSS. No fake route, GPS or telemetry."""
from pathlib import Path
import shutil
import subprocess
import tempfile

root=Path(__file__).resolve().parents[2]
source=root/"media-engine/overlay/fermata/src/main/assets/evcockpit/navigator.html"
out=root/"docs/screenshots"
out.mkdir(parents=True,exist_ok=True)
chrome=shutil.which("google-chrome") or shutil.which("chromium") or shutil.which("chromium-browser")
if not chrome:raise SystemExit("Chrome/Chromium unavailable")
markup=source.read_text(encoding="utf-8")
cut='<script src="leaflet.js"></script>'
if markup.count(cut)!=1:raise SystemExit("navigator.html structure changed")
# Real HTML and CSS from the app; no network, no invented SoC, route or stations.
markup=markup.split(cut,1)[0]
markup+='\n<style>#map{background:linear-gradient(120deg,#143446,#092838 65%,#071f30)}</style></body></html>'
with tempfile.TemporaryDirectory() as directory:
    html=Path(directory)/"navigator-preview.html"
    html.write_text(markup,encoding="utf-8")
    for name,width,height in [
        ("cockpit-browser-preview.png",1536,691),
        ("cockpit-compact-browser-preview.png",920,480),
        ("route-phone-browser-preview.png",430,860),
    ]:
        target=out/name
        cmd=[chrome,"--headless=new","--no-sandbox","--disable-gpu",
            "--disable-dev-shm-usage","--hide-scrollbars","--no-first-run",
            "--disable-background-networking","--allow-file-access-from-files",
            "--force-device-scale-factor=1",f"--window-size={width},{height}",
            f"--screenshot={target}",html.as_uri()]
        result=subprocess.run(cmd,capture_output=True,text=True,timeout=45)
        if result.returncode!=0 or not target.exists() or target.stat().st_size<4000:
            raise SystemExit(f"Chrome screenshot failed for {name}: {result.stderr[-1000:]}")
        print(name,target.stat().st_size,"bytes")
