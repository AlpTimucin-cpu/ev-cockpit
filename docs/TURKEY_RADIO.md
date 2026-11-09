# Türkiye radio and media verification

Native Cockpit 2.0.5 adds **Media → Radio** alongside Video in the existing
ParkedStreamingDialog (the project's media screen). The interface stays English.

RadioBrowserApi uses GET /json/stations/search with countrycode=TR,
hidebroken=true, order=votes, reverse=true, limit=200. RadioRepository filters
invalid and insecure URLs, removes duplicate URLs/IDs, and preserves votes order.
The DTO maps stationuuid, name, url_resolved, favicon, tags and votes. Favicon
metadata is retained; the list renders text without fetching third-party images.
Hilt supplies the Retrofit API and repository to RadioViewModel. The screen uses
Media3 ExoPlayer with HLS support, audio focus, headphone disconnect handling,
loading/error/empty states, retry, pause, play and stop. Switching tabs or closing
the selector releases the player; backgrounding pauses it. This is foreground
phone radio, not a background media service or an Android Auto radio catalogue.
Only HTTPS streams are offered; HTTP-only stations are intentionally excluded.
Radio is audio-only; the existing car-connection restriction remains on video.

Existing integrations are retained:
- MediaLauncher resolves the exact separately built companion package IDs and
  returns Launched, NotInstalled or Blocked; the cockpit renders a dark warning.
- MediaWebViewScreen and FullscreenVideoDialog own custom-view attachment,
  callback, back navigation, lifecycle and disposal.
- MapLibreCockpit reorders existing layers after updates: route, speed profile,
  charging (including DC), weather. Remove by Layer object before addLayerAbove;
  removing by ID then reusing the object caused the earlier startup crash.
- android.enableR8.fullMode=false; Release minify/shrinkResources=false.
  Existing keep rules remain dormant. Add no speculative keep rule for native
  layer ownership errors or without a new, attributable FATAL EXCEPTION trace.

## Apply and test from mobile GitHub

1. Select feature/ev-cockpit in the repository's branch selector.
2. Open Actions → Native Cockpit · Kotlin Compose APK and the latest run.
3. Download EV-Cockpit-Native-Kotlin-Debug-APK from Artifacts; extract and install
   the APK on the test phone. An installed production build with another signing
   key cannot be updated with this debug APK.
4. Open Media → Radio. Verify loading, popular station list, tap-to-play,
   pause/resume, switching stations, network failure, headphones disconnect and
   leaving the screen. Actual broadcaster availability is outside our control.
5. Test Video → YouTube full-screen entry/exit and Media Engine both installed
   and absent. Video remains subject to the existing phone/car eligibility rule.
6. Review Cockpit Visual QA artifacts for startup/radio screenshots and Logcat.
   For a physical release crash, provide FATAL EXCEPTION and its complete Caused
   by chain. Production signing requires the owner's EYA_SIGNING_BUNDLE_BASE64
   Actions secret; emulator-only signing is not a distributable production key.

## Rename the repository on mobile

Open the repository → Settings (use desktop site mode if hidden) → General →
Repository name → ev-cockpit → Rename. Administrator permission is required.
Then update explicit repository URLs in documentation/workflows and local remotes.
GitHub redirects most old repository links, but Pages URLs and hosted-action
references need separate review. This operation does not change the active branch.
