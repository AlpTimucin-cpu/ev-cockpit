@echo off
setlocal
cd /d "%~dp0.."
echo EV Cockpit v0.8 - APK derleme
where gradle >nul 2>nul
if errorlevel 1 (
  echo Gradle PATH uzerinde bulunamadi.
  echo Android Studio ile projeyi acip Build ^> Build APK(s) secin.
  echo Alternatif: .github/workflows/android-debug-apk.yml GitHub Actions.
  exit /b 2
)
call gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
if errorlevel 1 (
  echo Derleme veya JVM birim testi basarisiz oldu. Yukaridaki hata metnini inceleyin.
  exit /b 1
)
if not exist "app\build\outputs\apk\debug\app-debug.apk" (
  echo APK dosyasi uretilmedi.
  exit /b 1
)
echo APK: app\build\outputs\apk\debug\app-debug.apk
certutil -hashfile "app\build\outputs\apk\debug\app-debug.apk" SHA256
endlocal
