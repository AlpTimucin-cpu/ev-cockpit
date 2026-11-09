@echo off
setlocal
cd /d "%~dp0.."
where adb >nul 2>nul
if errorlevel 1 (
  echo ADB bulunamadi. Android SDK platform-tools PATH icin ayarla.
  exit /b 2
)
echo Telefonu USB uzerinden bagla ve hata ayiklama iznini kabul et.
adb devices
set "OUT=EVCOCKPIT_tani_%RANDOM%.txt"
(
  echo === EV Cockpit diagnosis (user initiated) ===
  echo === App ===
  adb shell dumpsys package com.evcockpit.mediahub ^| findstr /I "versionName versionCode"
  echo === Android Auto ===
  adb shell dumpsys package com.google.android.projection.gearhead ^| findstr /I "versionName versionCode"
  echo === Media sessions ===
  adb shell dumpsys media_session
) > "%OUT%"
echo Dosya: %OUT%
echo NOT: Medya oturumlari cihaz ve diger uygulama adlarini gosterebilir. Paylasmadan incele.
endlocal
