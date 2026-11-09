# FAZ 6 — Android Auto navigation, audio priority, and driver-safe display

Stacked **draft** on FAZ 5 PR #30. Only native Android Auto and the adjacent phone video/audio safety boundary are changed. No changes to `main`, signed release flow, ELM327 CAN decoding, battery calibration, OSRM route selection or media player content. This phase is not a promise of Google/vehicle host approval.

## Android Auto template and host lifecycle

- The Android Auto CarAppService continues to advertise only `androidx.car.app.category.NAVIGATION`; its Screen renders Google Car App Library `PaneTemplate` for older hosts and `MapWithContentTemplate` on API 7+ with a MapLibre car-surface map. **No WebView, ExoPlayer, Netflix, YouTube or video template is exposed to the vehicle head unit.**
- Template content is now short and Turkish. Route length is labelled **total OSRM estimate**, not real-time traffic or remaining distance. A route requires real validated geometry **and route ID**; the GPS badge is healthy only with a fresh fix of ≤35 m accuracy.
- A host "Start navigation" action is available only with a real route, a current matched guidance snapshot and GPS no older than five seconds. Validation happens **again when clicked**, so a stale previously drawn template cannot start navigation.
- While navigating, GPS loss causes voice to stop and excludes stale trip updates; after 15 seconds without matched GPS, the prior lifecycle explicitly ends navigation. Changing the selected route also ends the former trip.
- `CarTripUpdateGate` now separates `shouldPublish` from `onPublished` and `onPublishFailed`. A rejected `NavigationManager.updateTrip` call does **not** falsely advance the last-successful-trip state. Host failures retry at most after 2 seconds; successful unchanged trip snapshots remain 10-second throttled. The original route-step update thresholds are retained.
- A car Screen stop/destroy ends ownership, closes TTS and releases surface and GPS. No background GPS service, mock route, simulated driving, or Car App video UI has been introduced.

## Priority / focus / media

- When a real Android Auto trip starts, it cancels any optional Turkish startup greeting. While navigation is active, later car/OBD greetings are suppressed; optional welcome speech never starts if Android AudioFocus cannot be acquired.
- `AppAudioDucking` has a read-only `hasNavigationSpeech()` query. In-app radio and active-sound ducking remain owner-scoped; no system-wide media volume mutation is introduced. The actual third-party app's AudioFocus behavior still depends on the OS/head unit.
- Phone-only WebView video remains inaccessible through Android Auto. Moreover, the **phone app** now refuses embedded playback when the CarConnection reports a projected host, when there is no connected OBD adapter, or when no **fresh** vehicle-speed reading is available. Driver's own "parked" checkbox AND a connected ELM327 speed at 0–0.5 km/h are additionally required. A zero-speed value does **not** prove the transmission is in Park, so this is a safety gate, **not** gear-position verification. On reconnect, projection or movement the video view closes. Radio/audio tab is preserved.

## Test matrix

```bash
python3 -m unittest discover -s tests -p 'test_native_phase0_policy.py' -v
./gradlew :nativecockpit:lintDebug :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug --no-daemon --stacktrace
python3 scripts/check_apk_secrets.py nativecockpit/build/outputs/apk/debug/nativecockpit-debug.apk
```

Deterministic unit/source tests now verify:
- Failed host update retry vs actual acceptance, host throttling, maneuver changes;
- Missing route identity and coarse/stale GPS labels;
- Navigation speech ownership persists even if welcome owner is released;
- No in-car WebView/video/audio templates, no synthetic 0 km/h fallback;
- The phone video policy rejects projection, motion, missing OBD and missing current speed.

**Physical tests still required:** Android Auto host on VW ID.4 with DHU/real projection, Android API 28–35 and car API 1–7 host behavior, user clicking Start/Stop with a real route; turn guidance and host maneuvers while using radio; temporary phone connectivity/GPS loss, host IPC refusal, car session restart, late TTS callbacks, greeting while navigation is active, safe handling of screen surface recreation, phone video when OBD drops or vehicle starts moving. Android Auto template row limits, OEM support and play-store driver-distraction approvals can only be accepted via host/device testing.

No production Release signing or publication before historic credential concerns are resolved; CI artifacts remain **Debug-only** and last seven days. Closing this PR leaves FAZ 5 unchanged.
