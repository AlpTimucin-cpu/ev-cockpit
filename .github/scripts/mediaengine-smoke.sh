#!/usr/bin/env bash
set -euo pipefail
# Always retain Android errors and ANR diagnostics when a UI assertion fails.
trap 'adb logcat -d -s AndroidRuntime:E ActivityManager:E > android_runtime_errors.log 2>/dev/null || true' EXIT

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -W -n com.evcockpit.mediahub/.MainActivity
sleep 6
adb shell screencap -p /sdcard/evcockpit_home.png
adb pull /sdcard/evcockpit_home.png evcockpit_home.png

# Regression: touch the REAL Film entry through the public home UI; do not try
# launching an AndroidManifest non-exported Activity through shell directly.
for i in 1 2 3 4 5 6; do
  adb shell uiautomator dump /sdcard/evcockpit_home_movie.xml >/dev/null 2>&1 || true
  adb pull /sdcard/evcockpit_home_movie.xml evcockpit_home_movie.xml >/dev/null 2>&1 || true
  if [ -s evcockpit_home_movie.xml ] && grep -q '▣ Filmler' evcockpit_home_movie.xml; then break; fi
  adb shell input swipe 530 1700 530 600 250
  sleep 2
done
test -s evcockpit_home_movie.xml
python3 - <<'PY'
import re, subprocess, xml.etree.ElementTree as ET
root=ET.parse("evcockpit_home_movie.xml").getroot()
nodes=[x for x in root.iter("node") if "▣ Filmler" in x.attrib.get("content-desc","")]
assert nodes,"Movie shortcut missing on the home screen"
box=[int(x) for x in re.findall(r"\d+",nodes[0].attrib["bounds"])]
assert len(box)==4,box
subprocess.run(["adb","shell","input","tap",
    str((box[0]+box[2])//2),str((box[1]+box[3])//2)],check=True)
PY
sleep 4
# The offline titles must render immediately, before the separate
# 3MB/7,232-stream M3U indexing worker finishes.
for i in $(seq 1 12); do
  adb shell uiautomator dump /sdcard/evcockpit_movie.xml >/dev/null 2>&1 || true
  adb pull /sdcard/evcockpit_movie.xml evcockpit_movie.xml >/dev/null 2>&1 || true
  if [ -s evcockpit_movie.xml ] && grep -q 'GÖMÜLÜ FİLM KATALOĞU' evcockpit_movie.xml; then break; fi
  sleep 2
done
test -s evcockpit_movie.xml
grep -q 'GÖMÜLÜ FİLM KATALOĞU' evcockpit_movie.xml
grep -q '7232 BAŞLIK' evcockpit_movie.xml
# Source indexing may take longer on the constrained Android 15 emulator.
for i in $(seq 1 24); do
  adb shell uiautomator dump /sdcard/evcockpit_movie.xml >/dev/null 2>&1 || true
  adb pull /sdcard/evcockpit_movie.xml evcockpit_movie.xml >/dev/null 2>&1 || true
  if [ -s evcockpit_movie.xml ] && grep -q 'TAM M3U PAKETTE' evcockpit_movie.xml; then break; fi
  sleep 2
done
test -s evcockpit_movie.xml
if grep -q "isn't responding" evcockpit_movie.xml; then
  echo "ANR: Movies screen stopped responding"
  exit 1
fi
grep -q 'TAM M3U PAKETTE' evcockpit_movie.xml
grep -q 'GÖMÜLÜ FİLM KATALOĞU' evcockpit_movie.xml
grep -q '7232 BAŞLIK' evcockpit_movie.xml
# Real decoder regression: navigate to the locally bundled MP4, not an
# external CDN that may answer HTTP 403 in Türkiye.
grep -q 'ÇEVRİMDIŞI OYNATICI TESTİ' evcockpit_movie.xml
python3 - <<'PY'
import re, subprocess, xml.etree.ElementTree as ET
root=ET.parse("evcockpit_movie.xml").getroot()
nodes=[x for x in root.iter("node")
       if "ÇEVRİMDIŞI OYNATICI TESTİ" in
       (x.attrib.get("text","") + x.attrib.get("content-desc",""))]
assert nodes,"Offline Media3 test button missing"
box=[int(x) for x in re.findall(r"\d+",nodes[0].attrib["bounds"])]
assert len(box)==4,box
subprocess.run(["adb","shell","input","tap",
    str((box[0]+box[2])//2),str((box[1]+box[3])//2)],check=True)
PY
for i in $(seq 1 12); do
  adb shell uiautomator dump /sdcard/evcockpit_offline_player.xml >/dev/null 2>&1 || true
  adb pull /sdcard/evcockpit_offline_player.xml evcockpit_offline_player.xml >/dev/null 2>&1 || true
  if [ -s evcockpit_offline_player.xml ] &&
      grep -q 'YEREL TEST BAŞARILI' evcockpit_offline_player.xml; then break; fi
  sleep 2
done
test -s evcockpit_offline_player.xml
if ! grep -q 'YEREL TEST BAŞARILI' evcockpit_offline_player.xml; then
  echo 'FAIL: local offline MP4 did not render first frame in Android 15 emulator'
  grep 'text=' evcockpit_offline_player.xml | head -c 2000 || true
  exit 1
fi
adb shell screencap -p /sdcard/evcockpit_offline_player.png
adb pull /sdcard/evcockpit_offline_player.png evcockpit_offline_player.png
echo 'PASS: bundled offline H264 rendered its first Media3 frame'
adb shell input keyevent 4
sleep 4
adb shell uiautomator dump /sdcard/evcockpit_movie.xml >/dev/null 2>&1 || true
adb pull /sdcard/evcockpit_movie.xml evcockpit_movie.xml >/dev/null 2>&1 || true
# The offline control video is displayed BEFORE the archived film cards.
# Android UIAutomator only dumps currently visible rows: scroll until first
# real archive item is in the accessibility tree.
grep -q 'OYNATICI TESTİ' evcockpit_movie.xml
for i in 1 2 3 4 5; do
  if grep -q 'Film ayrıntılarını aç' evcockpit_movie.xml; then break; fi
  adb shell input swipe 530 1690 530 850 350
  sleep 2
  adb shell uiautomator dump /sdcard/evcockpit_movie.xml >/dev/null 2>&1 || true
  adb pull /sdcard/evcockpit_movie.xml evcockpit_movie.xml >/dev/null 2>&1 || true
done
grep -q 'Film ayrıntılarını aç' evcockpit_movie.xml
adb shell screencap -p /sdcard/evcockpit_movie.png
adb pull /sdcard/evcockpit_movie.png evcockpit_movie.png

python3 - <<'PY'
import re, subprocess, xml.etree.ElementTree as ET
root=ET.parse("evcockpit_movie.xml").getroot()
nodes=[x for x in root.iter("node") if "Film ayrıntılarını aç" in x.attrib.get("content-desc","")]
assert nodes,"Embedded film card not found"
box=[int(x) for x in re.findall(r"\d+",nodes[0].attrib["bounds"])]
assert len(box)==4,box
subprocess.run(["adb","shell","input","tap",
    str((box[0]+box[2])//2),str((box[1]+box[3])//2)],check=True)
PY
sleep 3
for i in 1 2 3 4 5; do
  adb shell uiautomator dump /sdcard/evcockpit_movie_detail.xml >/dev/null 2>&1 || true
  adb pull /sdcard/evcockpit_movie_detail.xml evcockpit_movie_detail.xml >/dev/null 2>&1 || true
  if [ -s evcockpit_movie_detail.xml ] && grep -qi 'oynatmayı dene' evcockpit_movie_detail.xml; then break; fi
  sleep 2
done
test -s evcockpit_movie_detail.xml
if grep -q "isn't responding" evcockpit_movie_detail.xml; then
  echo "ANR: Movie detail stopped responding"
  exit 1
fi
grep -qi 'oynatmayı dene' evcockpit_movie_detail.xml
grep -qi 'm3u seç' evcockpit_movie_detail.xml
adb shell screencap -p /sdcard/evcockpit_movie_detail.png
adb pull /sdcard/evcockpit_movie_detail.png evcockpit_movie_detail.png

adb logcat -d -s AndroidRuntime:E > android_runtime_errors.log
if grep -q 'FATAL EXCEPTION' android_runtime_errors.log; then
  cat android_runtime_errors.log
  exit 1
fi
echo 'PASS: full user M3U is packaged, film card opens a live-source decision safely'
