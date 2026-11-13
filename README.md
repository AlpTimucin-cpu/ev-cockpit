# EV Cockpit — development preview

Independent EV navigation/telemetry Android project (GPL-3.0). **Not a
verified or approved production Android Auto release.** Maintained source uses
Kotlin, Jetpack Compose, MapLibre, OSRM road routing and the Android for Cars
App Library 1.7.0.

## Source of truth (11 November 2026)

- **Before any integration merge**, `main` is the old Media Hub baseline.
  The proposed integration is a **draft PR**, not yet a published release.
  Existing GitHub Releases `v1.0.0`/`v1.0.2` remain older APKs and must
  not be presented as builds of the new native cockpit.
- The native cockpit is maintained in a series of **draft** PRs, currently
  rooted in `feature/ev-cockpit`, with
  [Phase 12](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/45),
  [Debug diagnostics](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/46),
  [phone/vehicle acceptance protocol](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/47)
  and the Job 3 security/release-verification PR stacked on top.
- The current **unmerged integration chain** is [#51 clean native snapshot](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/51) →
  [#52 archive safety gate](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/52) →
  [#53 adaptive landscape](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/53).
  The Phase 0 permission review is also stacked in a separate draft branch;
  none of these features exists in the current `main` APK.
  See [Phase 0 acceptance and integration plan](docs/PHASE0_FOUNDATION_2026-11-11_TR.md).
- **Physical Volkswagen ID.4 and Android Auto host acceptance remains NOT
  VERIFIED.** The owner is testing without a computer, ADB, logcat or DHU.
  Real phone/vehicle evidence is tracked in
  [the A–H acceptance protocol](docs/IS2_TELEFON_ID4_KABUL_TESTI_TR.md).
- A previously exposed signing credential is still a critical risk. The
  `.github/workflows/release.yml` signing job remains **disabled**. No
  trusted signed Release or Play Store distribution is authorized.

## Phase 2 · TomTom Orbis Traffic (DRAFT, device tests pending)

- The owner approved **TomTom Orbis Traffic v2**, but NOT reuse of the
  previously exposed key. A user-entered **new** key is encrypted with
  AndroidKeyStore AES-256-GCM; outbound route/GPS transfer requires a
  separate explicit phone setting. Removing the key disables traffic.
- Foreground phone or active Android Auto host requests are limited to
  real recent precise GPS and a verified OSRM route. The app fetches
  TomTom incidents (bbox) and a small selection of raster flow PNG tiles,
  checks HTTP response time as an HTTP time (NOT raw sensor sample time),
  keeps a 5-minute display TTL and applies local monthly request caps.
  429/quota/network/route mismatch disables live display, not OSRM routing.
- Phone has incident detail sheet; native MapLibre on phone and Android
  Auto displays provider-provided pixels and nearby incident markers.
  **Traffic-aware ETA and speed limit remain unavailable**, never copied
  from OSRM estimates. Physical phone/car behavior and live API response
  are **not yet verified**; neither can be proved by CI.
- [Phase 2 source, ToS and mobile acceptance](docs/PHASE2_TRAFFIC_PROVIDER_REVIEW_2026-11-13_TR.md).
  PR #56 is DRAFT; no release or integration merge.

## Data and safety invariants

- Real, permissioned Fused GPS and real OSRM route geometry only; times, energy
  consumption and arrival are **models**, not live traffic or measurements.
- Read-only ELM327: SAE Mode 01 / explicitly approved UDS service `0x22`
  only. No automatic manufacturer DID scan or ECU write, and **VW ID.4 Pure
  SoC DID / CAN address / multiplier are not verified**. Missing values display
  *unavailable*, never invented.
- Android Auto uses approved Car App templates, not video/WebView. Phone video
  has strict real-speed, user-confirmed parked-only gating.
- EPDK station catalog does not prove current charger availability or tariff.
  Route weather is a forecast from Open-Meteo, refreshed at **most every 30
  minutes while the foreground Android Auto screen has valid route+GPS**.
  Doze, lost connectivity or host pause can delay the next attempt.
- The Phase 0 phone permission wizard requests **foreground precise** location,
  paired Bluetooth and notifications in user-initiated steps. Missing grants
  remain visible as feature limitations; a successful permission check does
  not certify a GPS fix, an ELM327 connection or an Android Auto session.
- The Debug diagnostic view holds at most 500 redacted **application** events,
  not operating-system logcat; copy/share only after inspecting the report.

## Modules and tested build targets

| Module | Purpose |
| --- | --- |
| `:nativecockpit` | Kotlin/Compose app, Android Auto, GPS, OBD, weather, charging |
| `:app` | Older Media Hub and companion app (separately packaged) |
| `:evlite` | Lightweight EV guide (separately packaged) |
| `media-engine/` | Separate source overlay, **not** a Gradle module |

Source toolchain: Java 17, Android SDK 36, pinned Gradle 8.14.3;
`nativecockpit` minSdk 28. A Debug APK may be built with:

```bash
./gradlew :nativecockpit:lintDebug :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug
python3 scripts/verify_youtube_filter_absent.py --apk nativecockpit/build/outputs/apk/debug/nativecockpit-debug.apk
```

Debug uses application ID `com.evcockpit.debug`, version `1.0.8-DEBUG`;
it is **not** the `com.evcockpit` production identity. Release source version
is `1.0.8` (without `-TEST`), R8 and resource shrinking enabled,
only `arm64-v8a` native libraries, HTTPS-only network security
configuration and no backup/cleartext/debug flag. A separate CI **Release
smoke test** creates a *throwaway signing key* for build verification only.
Even if that CI passes, **do not install or distribute its signed artifact**.

There is no authorized workflow to create a trusted Release from the
previously exposed credentials. **This integration remains unmerged** until
CI, on-device acceptance and owner review succeed. No automatic Release.

## Documentation and licensing

- [Turkish guide](README_TR.md) / [English guide](README_EN.md)
- [Job 2: phone and ID.4 test checklist](docs/IS2_TELEFON_ID4_KABUL_TESTI_TR.md)
- [Stacked PR integration and conflict plan](docs/INTEGRATION_PLAN_2026-11-09_TR.md)
- [Metadata and old ZIP archive plan](docs/REPO_PROFILE_AND_SOURCE_ARCHIVE_TR.md)
- [CHANGELOG](CHANGELOG.md), [LICENSE](LICENSE),
  [NOTICE](NOTICE.md), [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md),
  [Security guidance](SECURITY_TOMTOM.md)

Independent project; no affiliation with Volkswagen, Android Auto, Google,
streaming services or charging-network operators is implied.
