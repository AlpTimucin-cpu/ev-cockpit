# EV Cockpit — Fermata-style car display prototype

## Goal (not another launcher)
Build an actual unified player, catalogue and vehicle-facing video surface that displays permitted streams while the vehicle is **parked**. Keep YouTube / IPTV in one user interface wherever providers allow it. Do **not** present shortcuts to separate apps as in-app integration.

Target:
- electric vehicle Pure (2024), existing vehicle display; no additional Android box.
- POCO F5 Android 15, Android Auto projection.
- Native Media3 for HLS / progressive web media when authorized.
- On-device M3U playlist storage and background import, EPG and catch-up for sources that provide them.
- Real fullscreen, quality selection, favourites, logos, language categories.
- Separate Android Auto audio experience (already in main) from video display experiment.
- Park-only for in-vehicle front display, never bypass driving-video restrictions.

## Reference and licensing
Reference: https://github.com/AndreyPavlenko/Fermata (GPL-3.0).
Fermata is a multi-engine media player and has Android Auto support.
To reuse its source or make a derivative, preserve copyright and GPL-3.0 obligations and make corresponding source available. Do not copy proprietary Android Auto binaries without analyzing their origin/licensing.

## Hard compatibility gate
As of October 2026, official Android Auto parked video is not generally available on this Android 15 target. Google's May 2026 announcement describes video rollout for eligible vehicles and Android 17+ phones later in 2026:
https://android-developers.googleblog.com/2026/05/android-for-cars-unifying-platforms-premium-experiences.html

Current Android Auto parked-app documentation lists games only as a supported parked app category:
https://developer.android.com/training/cars/parked/auto

Fermata's maintainer describes that on Android 14+ simply sideloading a video app generally does not make its video UI appear in the car; root or a special adapter is generally needed to bypass the sideload visibility constraint:
https://github.com/AndreyPavlenko/Fermata/discussions/432

This experiment MUST NOT claim that the car will show video until testing proves it on this exact phone + EV Cockpit Android Auto combination. Mobile screenshot or successful Android APK build is NOT proof of in-vehicle video.

## Architecture decision
1. **Phone player**: Media3 ExoPlayer + WebView for allowed public pages; app's own licensed media catalogue.
2. **Audio-only Android Auto**: existing MediaLibraryService; survives while video is unavailable.
3. **Parked AA video feasibility**: isolate in an optional module / experimental branch. Determine available approved platform APIs and runtime checks; never impersonate a navigation app or circumvent driver-distraction lockouts.
4. **External apps**: Netflix DRM and third-party APK (SportzX) cannot be silently embedded as video sources. Launching a separate app on the phone is NOT equivalent to video on Android Auto.
5. **Diagnostics**: capture Android Auto version, Android phone OS, EV Cockpit app-list visibility, and logs, without leaking personal tokens.

## Acceptance tests
- [ ] The prototype APK installs and launches on POCO F5.
- [ ] 3 authorized HLS streams switch within a single player and show real quality data.
- [ ] Channels are categorized, logos appear, and EPG follows legal source URLs.
- [ ] The Android Auto app is visible on the electric vehicle.
- [ ] While parked, a permitted test video actually appears on the vehicle display.
- [ ] Video cannot appear to the driver while driving.
- [ ] If parked video is unsupported, the UI explains this, with working audio mode and no false claim.

## Next diagnostic dependency
Before choosing the in-vehicle video implementation, establish whether the POCO F5 is rooted and whether the current Android Auto launcher already displays the EV Cockpit as an audio app. Rooting can disrupt banking/security apps and device integrity; do not change root/system settings without explicit informed approval.

Status: engineering plan and feasibility branch only; not a proven vehicle-video APK.

## 2026-10-09: Verified AABrowser evidence from user's actual EV Cockpit

User has ALREADY installed/configured Shizuku. Do **not** repeat the Shizuku / developer options steps. AABrowser opens YouTube in the electric vehicle screen; Netflix's website opens but the video does not play. That is strong evidence that this phone/vehicle already displays a third-party web browser surface, but it does not establish Netflix DRM playback or support for other apps.

Reference (GPL-3.0 source): https://github.com/kododake/AABrowser
- AABrowser's `AndroidManifest.xml` exposes an **Activity** with `android.intent.category.CAR_LAUNCHER`, `androidx.car.app.category.NAVIGATION`, and `android.intent.category.APP_MAPS` intent categories, plus app-specific metadata. This differs from our current AudioLibraryService-only vehicle-facing integration.
- Its `MainActivity.kt` hosts Android WebView and manages native browser tabs/controls.
- Its `BrowserTabFactory.kt` calls `configureWebView` and passes a configurable DRM L3 setting. Having an L3 setting does **not** guarantee that Netflix allows playback in this environment.
- AABrowser v3.0 has minSdk 35/Android 15. This matches the user's POCO F5 Android 15.
- Do not blindly copy navigation intent/category labels to misrepresent a video app as a navigation app or override driver-distraction safeguards. Investigate a compliant parked-only path and actual vehicle behavior first.

**Next proof target**: the phone with Shizuku already configured must launch an original, permitted video in a *genuine* EV Cockpit car-display component while the car is parked. No new installer recommendations until the AA Browser car-screen architecture is proven in our own app. Netflix and SportzX must be treated as independent provider/platform constraints, not fixed by changing Android Auto registration.

## 2026-10-09 official parked category check

Google official: https://developer.android.com/training/cars/parked/auto
- Android Auto runs parked Activities on Android 15+ only for approved categories. In October 2026, the only supported parked category on Android Auto is **games**; the car stops the Activity when driving resumes.
- The CAR_LAUNCHER category by itself is NOT a guarantee that an ordinary video/browser app can project to the car.
- AABrowser's public AndroidManifest declares appCategory=game with CAR_LAUNCHER, NAVIGATION, APP_MAPS and distractionOptimized=true. This may account for its nonstandard car presentation, but is not an approved distribution declaration for our web player: https://github.com/kododake/AABrowser/blob/main/app/src/main/AndroidManifest.xml
- Fermata uses older com.google.android.apps.auto.sdk.CarActivity and a bundled nonstandard car SDK, separate projection services, with Xposed in some configurations: https://github.com/AndreyPavlenko/Fermata/blob/master/fermata/src/auto/java/me/aap/fermata/auto/MainCarActivity.java
- We deliberately do not misclassify a browser as a game or navigation application, and do not claim this build implements car-display video.

QA: Android 15 emulator opened our Web Media by tapping the actual TV-section YouTube Web control. No AndroidRuntime fatal error and topResumedActivity was MediaWebActivity. It is a PHONE emulator test, not a real electric vehicle display test.

## 2026-10-09: Moving-vehicle feasibility research (POCO F5 -> EV Cockpit Android Auto)

**User's requirement:** Video on the front, driver-visible EV Cockpit Android Auto screen while the car is moving. This is a safety-restricted use case. Do not fake a navigation service or bypass driver-distraction restrictions.

### Official Android Auto

- https://developer.android.com/training/cars/platforms/android-auto — Android Auto parked activities on Android 15+ are automatically exited on vehicle motion. The only officially supported Android Auto parked category is currently games; those are parked-only.
- https://developer.android.com/training/cars/parked/auto — Declaring CAR_LAUNCHER enables eligibility in supported parked categories but does not grant a moving-vehicle video surface.
- https://developer.android.com/training/cars/parked — Android Auto and Android Automotive OS are distinct; video streaming and browsers are supported under Android Automotive OS parked apps, not general Android Auto video apps.
- https://developer.android.com/training/cars/parked/video — Some Android Automotive OS vehicles support **audio while driving** from an otherwise parked-only video app, with the visual activity hidden and required MediaSession and car UX restriction handling. This is **not** an Android Auto video-in-motion entitlement on EV Cockpit.
- https://developer.android.com/docs/quality-guidelines/car-app-quality — Driver distraction guideline DD-2 prohibits launching/showing parked video/browser UI while driving; DD-4 is a limited audio-only exception on eligible AAOS.

### AABrowser

- https://github.com/kododake/AABrowser README explicitly marks the app Stationary Use Only and recommends parked/passenger usage.
- https://github.com/kododake/AABrowser/discussions/40 — March 2026 'persistent display and audio while driving' proposal was **closed/rejected by maintainer**; author proposed identifying as navigation, but maintainer said the idea is not a reliable solution for real head units and is unsupported.
- Our user has confirmed AABrowser shows YouTube on the EV Cockpit display while stationary. This **does not imply** it can remain visible during vehicle motion. Netflix web page loads, but protected Netflix video fails independently.

### Fermata

- https://github.com/AndreyPavlenko/Fermata — separate legacy CarActivity / car projection services; various historical sideload/root/installer conditions. Technical existence of a third-party workaround does not establish compatibility, policy compliance or driving safety.

### Development decision

1. Keep Media3 radio / podcast / other authorized audio available through Android Auto's MediaLibraryService in the driver-safe system UI while moving.
2. Build a real IPTV and web video player for the phone; do not represent that phone view as vehicle projection. Video on the car front display is only a parked research target.
3. On supported parked UX surfaces, handle motion transitions by pausing/hiding visual video and restoring controls only on return to parked.
4. Never substitute a navigation/game category, falsify driving state/speed/parking signals, disable motion guards, or force an exposed Surface to defeat car UX rules.
5. Passenger video while the vehicle moves belongs on an independent passenger-only display that the driver cannot see. The existing single front EV Cockpit infotainment display has no verified passenger-safe projection mode.
6. Next proof test: with the car stationary, collect Android Auto launcher visibility and Media Hub audio controls; do not ask user to repeat Shizuku/Android Auto setup already performed.

**Status:** Research completed; moving-video front-display functionality not implemented and not promised. No APK is produced for this research finding.

## 2026-10-09: User-forwarded AAAD support statement and dongle suitability

- AAAD support told user that AAAD and AAAD-managed apps have **Android 15 compatibility problems** and offered an **AAWireless USB dongle** as a workaround. The email itself has no verified issue-resolution date; do not generalize it as a failure of every Android 15 Android Auto app.
- Official AAAD source: https://github.com/shmykelsa/AAAD ; AAAD is an app downloader/installer, not the projection/browser rendering engine of the Media Hub.
- AABrowser's own source and release: https://github.com/kododake/AABrowser ; supports Android 15+ independent of AAAD and worked on the user's EV Cockpit for a YouTube website.
- **AAWireless OEM compatibility**: https://www.aawireless.io/en/products/aawireless — the manufacturer explicitly states that cars which **already have built-in wireless Android Auto** are *not compatible* with the dongle. The user's EV Cockpit uses its native wireless Android Auto, so DO NOT recommend buying an AAWireless dongle as a generic fix.
- Do not repeat the existing Shizuku configuration or recommend installing AAAD again. The next testable item is in-car media-library connection diagnostics, then a genuinely supported parked video surface when platform compatibility permits it.

## 2026-10-09 — DiPlay 0.2.16 user-supplied APK analysis

User provided social screenshots, https://shihabal3amri.github.io/DiPlay/, and the original DiPlay-0.2.16.apk for analysis. Checked local SHA-256: 3d9e4b5da8457ea722112c48df99f2999c1eb56e02f365c6626c47d7bdbe31fc, exactly equal to the official v0.2.16 GitHub release asset digest (2026-10-09). GitHub release: https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.16 .

**Projection direction is crucial:**
- DiPlay is an **Apple CarPlay receiver** for an Android head unit / Android phone acting as a screen. An **iPhone sends** CarPlay to DiPlay. It is NOT a CarPlay or Android Auto sender from the Android phone to an existing car display.
- The first screenshot depicts an Android-based DIY monitor acting as a receiver. The second shows a Redmi phone as the receiving display, **not** sending Android applications to a factory Volkswagen infotainment screen.
- Our electric vehicle factory screen is itself a CarPlay/Android Auto **receiver**; the POCO F5 Android 15 is an Android Auto sender. Two receivers (POCO running DiPlay and VW factory CarPlay) do not form a usable projection chain. Installing DiPlay on the POCO therefore does not cause it to project arbitrary Android video or Netflix to the VW screen.
- Installation requires an Android head unit which permits third-party APKs. There is no established stock EV Cockpit APK install path; BYD DiLink is the documented target platform (other brands unsupported by DiPlay maintainers).

**Source architecture / reusable ideas:** https://github.com/shihabal3amri/DiPlay ; its :mobile module hosts the receiver, :common/:shared contain media transport; 0.2.16 adds experimental low-latency H.264 decode, direct compositor output and received/displayed FPS and decode-time diagnostics, jitter/backlog handling, audio buffering and wireless reconnect logic. These *quality-engineering patterns* may inspire our own Media3 IPTV playback instrumentation, but CarPlay receiver code cannot be dropped into an Android Auto projected video activity. The compiled APK includes native CarPlay-related libraries and bundled experimental offline accessory identity; it is not Apple-certified. Its identity and other licenses must not be copied into this project. DiPlay attributes xcertplay GPL-3.0 and DiAuto-derived UI AGPL-3.0; preserve license obligations before reusing sources.

**Project decision:** Do **not** merge DiPlay CarPlay receiver, do not tell user to buy hardware or repeat Shizuku setup, do not claim VW CarPlay video support. Worthwhile follow-up: add optional non-identifying IPTV playback diagnostics (buffering count, startup latency, decoder name, dropped frames where Media3 exposes them) and improve recovery on short HLS network stalls; retain park-only car-video investigation separately.
