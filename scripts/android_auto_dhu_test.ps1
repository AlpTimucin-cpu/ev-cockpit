<#
 EV Cockpit / Step 11: Windows ADB + Android Auto DHU
 This script does NOT fake a DHU run. Requires actual connected phone and
 Google Android Auto DHU installed via Android Studio SDK Tools.
 Instructions: enable Android Auto developer mode, then 'Start head unit server'.
 Usage: .\scripts\android_auto_dhu_test.ps1 -Serial 6c569826 -LaunchDHU
#>
param(
    [string]$Serial = "",
    [switch]$LaunchDHU
)
$ErrorActionPreference = 'Stop'
$adb = Get-Command 'adb.exe' -ErrorAction SilentlyContinue
if (-not $adb) {
    $candidate = Join-Path $env:USERPROFILE 'Desktop\platform-tools\adb.exe'
    if (-not (Test-Path $candidate)) { throw 'ADB yok. Android SDK platform-tools yükleyin.' }
    $adb = @{ Source = $candidate }
}
$adbBin = $adb.Source
$serialArgs = @()
if ($Serial) { $serialArgs = @('-s', $Serial) }
& $adbBin devices -l
if ($LASTEXITCODE -ne 0) { throw 'ADB çalışmadı' }
$installed = (& $adbBin @serialArgs shell pm path com.evcockpit 2>&1)
if ($LASTEXITCODE -ne 0 -or -not ($installed -match 'package:')) {
    throw 'EV Cockpit nativecockpit APK telefonda kurulu değil.'
}
Write-Host 'APK telefonda kurulu.' -ForegroundColor Green
$service = (& $adbBin @serialArgs shell dumpsys package com.evcockpit 2>&1) | Select-String -Pattern 'EyaCarAppService|androidx.car.app.category.NAVIGATION'
if (-not $service) { Write-Warning 'Car App service/kategori doğrulanamadı, AndroidManifest kontrol edin.' }
else { Write-Host 'Car App service kaydı bulundu.' -ForegroundColor Green }
Write-Host 'Android Auto geliştirici menüsünde Start head unit server seçeneğini AÇIN.'
& $adbBin @serialArgs forward tcp:5277 tcp:5277
if ($LASTEXITCODE -ne 0) { throw 'ADB port yönlendirmesi başarısız.' }
Write-Host 'ADB 5277 tüneli hazır.' -ForegroundColor Green
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$dhu = Join-Path $sdk 'extras\google\auto\desktop-head-unit.exe'
if (-not (Test-Path $dhu)) {
    Write-Warning ('DHU kurulu değil: ' + $dhu)
    Write-Host 'Android Studio > SDK Manager > SDK Tools > Android Auto Desktop Head Unit Emulator'
} elseif ($LaunchDHU) {
    Write-Host 'DHU başlatılıyor (gerçek telefon + Android Auto head unit server gereklidir).'
    Start-Process -FilePath $dhu -WorkingDirectory (Split-Path $dhu)
} else {
    Write-Host ('DHU çalıştırmak için: "' + $dhu + '"')
}
Write-Host 'DHU üzerinde doğrulayın: uygulama görünür mü; rota/GPS/manzara; gündüz/gece; ses; navigasyonu başlat/bitir.'
Write-Host 'Android Auto uygulama görünürlüğü cihaz/Google Play dağıtım kurallarına bağlıdır.'
Write-Host 'Hata ayıklama: adb logcat -s AndroidRuntime:E EyaCarMap:W'
