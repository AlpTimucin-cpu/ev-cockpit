# FAZ 4 — Road-network detour evidence for EPDK DC/CCS charging candidates

This is a **stacked, draft-only** change on PR #28. It preserves native production code and the foreground/Android Auto navigation route, and never merges `main`.

## Verified vs estimated

1. `StationCorridor` and `AutomaticDcChargingPlanner.candidates` prefilter EPDK DC stations within approximately 5 km of the OSRM line. This is a **geographic candidate filter only**, never a driving detour distance.
2. Only stations with EPDK-labelled DC + CCS sockets enter road verification. Pick at most **six** distributed route candidates, excluding the first and last 10 km. No mass geocoding/routing of 13k+ stations.
3. A configured **HTTPS, non-demo** OSRM server receives a read-only `route/v1/driving/ORIGIN;STATION;DESTINATION?alternatives=false&steps=false&overview=false` request for each selected candidate. No GPS history, authentication, telemetry or SoC is sent. The same OSRM profile is used for all checks.
4. A response is accepted only with `code=Ok`, exactly three waypoints and two nonzero road legs, middle waypoint snapping distance **≤500 m**, internally consistent leg/total metres and route total no shorter than the selected OSRM baseline. A shorter alternative or mismatched profile is **not** a negative detour: it is **unverified**, never clamped to zero.
5. `additionalKm = viaRoadKm - originalSelectedRoadKm` and `roadDistanceToStationKm = firstLegKm`, all tagged by original route ID + station ID. This is a verified **OSRM routing engine comparison**, NOT a promise about physical charger driveway, current road restrictions, real-time traffic or charging availability. If a selected alternate baseline differs from the server's fastest via-route, the result can be rejected.
6. `OsrmRoadDetourRepository` never calls `RouteRepository.calculateVia`: comparison **must not mutate** the active navigation polyline or selected alternative. Coroutine cancellation discards obsolete route checks. Requests are bounded and time-limited. Failure per station results in unknown evidence, not zero extra travel.
7. The Plan > Duraklar tab shows verified per-station extra road km and the from-origin OSRM first-leg distance independently from BMS readings. A station detail button still uses the existing **user-initiated** route recalculation to actually add a stop.
8. Automatic energy-based stop proposals require this same-route, same-station road evidence. A missing validation cannot be treated as a free 0-km detour. The existing energy heuristic conservatively charges the *full* per-station added distance on inbound and once again after a prior stop; this is explicitly **a model**, not a verified multi-stop itinerary or guaranteed reserve. A real route through all chosen stops must be recalculated via the existing route planner before driving.
9. Real BMS SoC remains required; capacity and consumption are not silently filled. Source EPDK coordinates/socket metadata may be stale, inaccessible or inaccurate; no live price, occupancy, charge duration or route cost is invented.

## Quality gates

```bash
./gradlew :nativecockpit:lintDebug :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug --no-daemon --stacktrace
python3 scripts/check_apk_secrets.py nativecockpit/build/outputs/apk/debug/nativecockpit-debug.apk
```

Add deterministic parser / MockWebServer / selection tests and updated plan safety regression tests. Same-commit Android lint + JVM tests + Debug APK + Gitleaks + APK secrets scan must all pass. APK must remain Debug; Release signing/old historic key safety remains **hold**.

## On-device acceptance

- With HTTPS OSRM configured, route Niğde–Antalya and wait for EPDK catalog: 0–6 CCS candidates should get *actual OSRM compare* requests. Verify request cap and independent original route ID.
- Stop inspection cannot silently change selected route, navigation maneuvers, TTS, map camera, or Android Auto session.
- Validate `additionalKm` and the server's `waypoints[1].distance` using saved OSRM JSON; a motorway snap >500 m, HTTP 4xx/5xx, missing legs, or different alternate yields **not verified**.
- Disconnect network, change route, and test stale EPDK. No wrong-route evidence can be reused. No unaudited battery SoC or automatic real charging action is created.
- Select one station via 'Detaylar ve rotaya ekle' and confirm user-initiated full OSRM route contains that stop.
- Real-world socket, driveway, price, traffic and BMS data still require operator/device acceptance. The app must never present model estimates as measured battery truth.

No physical phone, ID.4, ELM327 or Android Auto test is claimed here. `minSdk=28` unchanged. Revert/close this PR to roll back FAZ 4 without altering previous phases.
