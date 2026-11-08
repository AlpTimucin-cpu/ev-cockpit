# EV Cockpit — Phase 12 / Step 7: Android Auto DHU and ID.4 acceptance

**Status: NOT VERIFIED — manual DHU and physical VW ID.4 checks have not been performed.**

This is an executable QA checklist, **not** a report of screenshots actually captured. The
CI Car App Library `app-testing:1.7.0` tests validate Kotlin host templates and
actions, not a real projector, vehicle connection, rendering, touch targets,
audio focus, Android Auto distribution eligibility or Google Play review.

## Prerequisites and evidence rules

- Build only `nativecockpit` (`applicationId com.evcockpit.debug` for Debug).
  Record **the actual Git commit, APK SHA-256, app version, phone OS,
  Android Auto version, DHU version and car host API level**.
- Capture unedited, legible screenshots from **actual DHU output or real
  head unit**. Record time, display pixel size, reported density and whether the
  phone is stationary. Do not generate mock screenshots and label them real.
- A release/pass is prohibited if any critical test is **NOT RUN** or **FAIL**.
  This checklist may be filled on mobile GitHub; never put credentials, detailed
  GPS coordinates, VIN, paired Bluetooth addresses or private destinations in evidence.
- Real GPS/OSRM route data may be used on a stationary test device at a safe
  test site. Never force GPS/OBD data or interact with the device while driving.
  Vehicle owner is responsible for safe parked installation/testing.
- Car App tests do not prove Google Play approval. No driving-time video/WebView,
  unsupported custom car templates or bypass of car host safety restrictions.

## Screen & resolution matrix

Each row needs its *own* actual screenshot with a named `png`/lossless image
and a reviewer check. Suggested artifact names are a template, not existing
files. Use separate images for each condition.

| Host | Pixel size | Scene | Required visible evidence | State |
| --- | --- | --- | --- | --- |
| Google DHU | 800×480 | No route / no GPS | Helpful real-GPS message; no false speed, maneuver, ETA or SoC | NOT RUN |
| Google DHU | 800×480 | Valid route | Legible map, next maneuver, 2 actions maximum; map remains visible | NOT RUN |
| Google DHU | 1280×720 | Valid route | Route polyline, next maneuver, ETA label excluding live traffic | NOT RUN |
| Google DHU | 1280×720 | EPDK DC/CCS | Host list capacity; real station coordinates; missing tariff shown as unknown | NOT RUN |
| Google DHU | 1280×720 | Open-Meteo forecast | Model/forecast, route ETA, “X min ago” if stale, visible back action | NOT RUN |
| Google DHU | 1920×1080 | Valid route | Full display map; no black surface, missing tiles or clipped panel | NOT RUN |
| Google DHU | 1920×1080 | Weather/charger overlay | Weather pins above route, no overlap with car marker/turn card | NOT RUN |
| VW ID.4 Pure | Vehicle's actual host surface | Navigation | Record **measured host pixel size/dpi** instead of assuming 12.9-inch display implies 1920×1080 | NOT RUN |
| VW ID.4 Pure | Vehicle's actual host surface | USB disconnect/reconnect | Navigation only resumes when same route + fresh accurate GPS revalidated | NOT RUN |
| VW ID.4 Pure | Vehicle's actual host surface | Audible guidance | Turkish maneuver TTS heard, ducks audio, weather alert does not cut turns | NOT RUN |

## Car host behavior / driving safety cases

- [ ] **API 1–6**: PaneTemplate, legacy charging list and weather fallback
  meet the host's *actual* `ConstraintManager` row/action limits.
- [ ] **API 7+**: MapWithContentTemplate displays map and pane/list together;
  route remains visible while switching weather/charging.
- [ ] Phone and car show the same selected OSRM route; manually chosen
  charging stops **never disappear** after off-route/refresh.
- [ ] Absent/denied location permission ⇒ no start button, no fake turn
  or road speed, and a usable Turkish permission instruction.
- [ ] GPS accuracy >35 m, stale or mocked position ⇒ guidance stops
  updating; no old maneuver/ETA is presented as live.
- [ ] Confirmed off-route ⇒ no silent route recalculation; user can
  replan on phone and preserve charging stops.
- [ ] Missing or stale EPDK ⇒ source timestamp/stale warning; unknown
  per-station price is never shown as a confirmed price.
- [ ] Missing measured BMS data ⇒ arrival SoC **not shown**;
  verified inputs permit only clearly labeled *MODEL* estimate.
- [ ] Open-Meteo request fails ⇒ old same-route forecast is labeled
  with data age; 45+ minute weather map pins disappear.
- [ ] Speaker volume/voice disabled ⇒ no unsolicited TTS; 200/50 m
  maneuver cues do not overlap with lower priority weather warning.
- [ ] Network offline/online & GPS off/on ⇒ graceful loading,
  recovery, no crash/ANR, no uncontrolled retry flood.
- [ ] Phone Android 14+/15+/16, power optimization, AA host detach ⇒
  location service start is permissioned, notification visible, explicit
  stop works, and absent permission is never bypassed.
- [ ] Verify phone/car hardware compatibility and Android Auto
  availability separately. Debug sideload is **not** production qualification.

## Evidence manifest (to complete manually)

| Evidence item | Value |
| --- | --- |
| Commit SHA / branch | pending |
| APK filename + SHA-256 | pending |
| DHU/Android Auto host versions | pending |
| Phone model, OS / Android Auto version | pending |
| Actual ID.4 host pixel dimensions and density | pending |
| Evidence capture files and reviewer | none (NOT RUN) |
| 800×480 legibility | NOT RUN |
| 1280×720 legibility | NOT RUN |
| 1920×1080 legibility | NOT RUN |
| 12.9-inch ID.4 real screen | NOT RUN |
| USB/GPS/weather/TTS/OBD real hardware testing | NOT RUN |
| Google Play Android Auto eligibility | NOT VERIFIED |

## Gate for future merge

1. `./gradlew :nativecockpit:lintDebug :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug` succeeds.
2. GitHub Actions Gitleaks and APK secret/package checks succeed on the
   **same PR head commit**. Obtain SHA-256 and copy the run URL to evidence.
3. Manually inspect every required DHU screenshot and the real ID.4
   acceptance cases; mark `PASS` only with attributable evidence.
4. Record any failed scene as a follow-up issue. Do not change `main`
   or publish a signed Release without an explicit owner approval.

Android reference:
- https://developer.android.com/training/cars/testing
- https://developer.android.com/training/cars/apps/test
- https://developer.android.com/jetpack/androidx/releases/car-app
