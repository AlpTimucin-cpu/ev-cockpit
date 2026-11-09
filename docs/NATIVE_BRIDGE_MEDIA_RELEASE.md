# Native GPS, speech, media, and Release verification

## Effective GPS and speech

`DebugRouteSimulation.frames` emits `SimulationFrame` values containing domain `GpsFix` and fixture telemetry. It deliberately does not write Android mock `Location` objects into the real location repository. NavigationViewModel selects a simulation frame only if its route ID matches the currently selected route. Real GPS and test GPS then feed the same `NavigationSpeechBridge`.

The bridge enriches route progress, projects vehicle and maneuver positions along verified route geometry, and applies the 200 m / 50 m stages. Segment lengths use Haversine distance; perpendicular distance still rejects off-route fixes. A route/step/stage map prevents repeated announcements after jitter. An integer last-spoken-step index would be insufficient when a route is replaced or a previous step is temporarily reselected.

Every proposed cue receives a monotonically increasing token. TextToSpeech onStart confirms only that token. onError, onStop, focus denial, and failed enqueue leave an unconfirmed stage retryable. Late callbacks cannot acknowledge or cancel another route/step cue. Route changes stop the previous utterance and reset the bridge. Android Auto navigation ownership prevents duplicate phone speech.

Tests cover the actual simulator Flow through the bridge, both distance thresholds, duplicate prevention, stale/off-route fixes, route replacement, and callbacks arriving after reset. An onStart callback does not prove that a physical speaker produced audible guidance.

## Companion media

Open Media in the native cockpit and choose Open Media Engine. The launcher resolves the pinned build's real package IDs, adds NEW_TASK for non-Activity callers, and handles ActivityNotFoundException and SecurityException. Manifest queries grant the necessary Android package visibility. If the companion app is absent, a dark warning appears and the internal site choices remain available. Playback eligibility checks remain in place.

YouTube remains available through the internal MediaWebViewScreen. onShowCustomView transfers the video View into a separate full-window Compose Dialog. System bars are hidden for that dialog and restored on dismissal. Back, lifecycle pause, playback ineligibility, renderer loss, and browser disposal exit fullscreen and release the corresponding callback. Each browser host releases only its own fullscreen session.

## Map overlay order

MapLibreCockpit preserves this bottom-to-top order: route, OSRM profile-speed segments, charging clusters/markers, DC markers, weather symbols. Existing layer instances are removed with removeLayer(layer), then re-added with addLayerAbove. Removing by layer ID invalidates the retained instance and previously caused CannotAddLayerException: Cannot add layer twice. Keep rules cannot fix this ownership error.

## Release checks

The root Gradle properties include android.enableR8.fullMode=false. Native Release sets isMinifyEnabled=false and isShrinkResources=false. Existing ProGuard rules are dormant in this configuration; no additional speculative keep rules are added.

Android visual QA builds both Debug and the non-debuggable Release build type. Release is signed with an ephemeral emulator-only certificate and installed only on the disposable emulator. This workflow uploads screenshots and Logcat, not the test-signed Release APK. The separate production workflow still requires the persistent owner signing bundle.

The emulator check inspects Debug portrait/landscape/compact layouts, the missing-companion warning, and Release cold start. Fatal Java/native errors fail the job and startup Logcat is retained even when an assertion fails. YouTube playback, installed-companion behavior, and audible guidance still require physical-device verification.

## Mobile GitHub steps

1. Select feature/ev-cockpit in the repository branch selector. The source changes are already committed there.
2. Open Actions and inspect Native Cockpit and Android visual QA. Download the native Debug APK from the build artifact. Open the visual QA artifact to inspect Debug/Release screenshots and startup-logcat.txt.
3. On the phone, enable test simulation in Settings, allow the test route to load, and check the 200 m and 50 m announcements. Turn simulation off before checking real GPS. Use the English speech-data warning to diagnose a missing voice package.
4. Install the separate media-engine APK from its own build artifact, then use Media > Open Media Engine. Without that application, confirm the dark missing-app warning. Choose YouTube to check fullscreen, Back, rotation, and pause/resume on the phone.
5. For a production Release crash, retain FATAL EXCEPTION, Process, and the complete Caused by chain from Logcat. Add only a targeted keep rule if a retraced stack trace identifies a shrinking/reflection issue after shrinking is re-enabled.
6. To rename the repository in a mobile browser: Settings > General > Repository name > ev-cockpit > Rename. Use the browser's desktop-site mode if the controls are hidden. Update hard-coded repository data/source URLs after renaming; repository renaming does not change application IDs.
