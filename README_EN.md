# EV Cockpit — Android development preview

An independent map-first EV cockpit using Kotlin, Jetpack Compose, MapLibre,
OSRM, real Fused GPS, read-only ELM327 and Android for Cars App Library 1.7.0.
**The application UI and voice instructions are in Turkish; this guide is in
English. The app has NOT passed on-vehicle acceptance testing.**

## What is actually verified (9 November 2026)

- The current native cockpit features live on a **stack of draft pull requests**
  atop `feature/ev-cockpit`. The repository default `main` branch still
  represents an older Media Hub baseline; it is not yet the new native app.
- Phase 12 source and automated Android lint/JVM/Debug build checks have
  passed on the draft branch. This does **not** prove real Android Auto host
  visibility, an actual VW ID.4 12.9-inch screen, TTS/audio focus, Bluetooth
  reliability or USB reconnection.
- The Debug diagnostics branch offers an in-memory, 500-event, privacy-limited
  app-owned log with copy and `.txt` sharing; no system logcat and no precise
  GPS/credential/vehicle identifier collection.
- The [A–H phone/vehicle acceptance protocol](docs/IS2_TELEFON_ID4_KABUL_TESTI_TR.md)
  has **not been completed**. Its cases are awaiting owner evidence.
- The signed production release workflow is **SECURITY-HELD** following an
  exposed signing credential. Old published tags do not establish new native
  production acceptance.

## Data and policy guarantees

Only measured, recent GPS updates and validated OSRM road geometry drive
navigation. Estimated time, remaining range, energy/arrival SoC and weather
are **models/forecasts**, never direct real-time vehicle measurements.
The application refuses to invent unavailable BMS SoC: **MEB DID, CAN address
and scaling for this ID.4 Pure are unverified**.

The ELM327 command boundary permits SAE Mode 01 and explicitly allowed UDS
service `0x22` reads only. No ECU write commands. EPDK charger source age
is displayed; it does not verify price, live availability or road detour.
Open-Meteo route weather is scheduled for *at most once per 30 minutes when
the Android Auto screen is foreground and accurate GPS/route evidence exists*.
Doze and lost connections may delay it; a label alone is not evidence.

Android Auto uses allowed navigation templates and does not expose
video/WebView. Phone video requires an explicit user parked confirmation
and measured stationary OBD speed.

## Builds and variants

- Java 17 / Android SDK 36 / pinned Gradle 8.14.3 / minSdk 28.
- Modules `:nativecockpit`, `:app` and `:evlite`; `media-engine/`
  is a source directory and is not built as a Gradle module.
- Debug: `com.evcockpit.debug`, source version `1.0.8-DEBUG`,
  intentionally independently installed.
- Release source: `com.evcockpit`, version `1.0.8` (no `-TEST` suffix).
  R8, resource shrinking, `arm64-v8a`, HTTPS system trust,
  `debuggable=false`, no cleartext/no backup. A **throwaway CI key** may
  sign an *unpublished Release smoke-test APK* solely to verify the build;
  this does **not** create a trusted or distributable release.

```bash
./gradlew :nativecockpit:lintDebug :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug
python3 scripts/verify_youtube_filter_absent.py --apk nativecockpit/build/outputs/apk/debug/nativecockpit-debug.apk
```

The source/Debug DEX test rejects the legacy YouTube ad-filter class;
a passing native APK scan does not certify a separate old media APK.

## Next checks

- [Phone + real vehicle acceptance checklist](docs/IS2_TELEFON_ID4_KABUL_TESTI_TR.md)
- [Stacked PR integration and risk analysis](docs/INTEGRATION_PLAN_2026-11-09_TR.md)
- [Repository profile + legacy ZIP archive steps](docs/REPO_PROFILE_AND_SOURCE_ARCHIVE_TR.md)
- [CHANGELOG](CHANGELOG.md) · [Turkish documentation](README_TR.md)
- [GPL-3.0](LICENSE) · [NOTICE](NOTICE.md) ·
  [Third-party notices](THIRD_PARTY_NOTICES.md) · [Security](SECURITY_TOMTOM.md)

Independent project; not affiliated with Volkswagen, Google, Android Auto,
charging networks or any media provider.
