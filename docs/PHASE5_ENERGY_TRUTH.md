# FAZ 5 — Verified SoC and remaining-route energy truth

This is a draft PR stacked on FAZ 4 PR #29. `main`, the original `feature/ev-cockpit` branch and all current releases remain unchanged.

## Three concrete fixes

1. **Single user-confirmed configuration source:** Both cockpit and charging planner now subscribe to `AppConfigRepository.configuration` (DataStore). The charging planner no longer reads obsolete `eya_energy_calibration` keys `usableBatteryKwh` and `referenceWhPerKm`, which SettingsScreen never wrote. Only stop preference and a **user-changed reserve override** remain in the old SharedPreferences. If no explicit override exists, the user's confirmed vehicle profile reserve is used. The visible reserve slider covers 0–50%, consistent with validated profile input.
2. **Remaining road, not full trip:** In `NavigationViewModel`, GPS projection runs **before** energy estimation. `RemainingRouteEnergyPolicy` requires a fresh, precise GPS fix successfully snapped by `RouteProgressTracker`, a validated OSRM route, and `remainingKm` consistent with OSRM `totalKm * (1 - GPS progress)`. It projects **current** confirmed BMS SoC against **remaining** distance. A mid-route SoC is never applied to the already driven part of the trip. Instantaneous speed is not extrapolated as the remaining trip's average.
3. **BMS provenance:** `VehicleTelemetry.socEvidence` provides independent `CoreEvidence.Available<Double>` typed evidence. `VerifiedBatterySocPolicy` checks source `VERIFIED_OBD`, classification `MEASUREMENT`, matching time/value, 0–100% range, and ≤10 s age. A raw response, unknown PID conversion, UI entry or stale reading is **not** a verified battery reading.

The current ELM327 implementation returns Mode 01 speed and exposes user-triggered MEB UDS read **raw data only**. It intentionally does **not** populate `socEvidence` (or proven battery SoC); consequently actual arrival SoC and automatic charging recommendations remain unavailable until a specific ID.4 BMS mapping is validated on a real car. No invented SoC, mileage or capacity has been added.

## Charging plan integration

- Planned stops use the same DataStore user-confirmed capacity and consumption assumptions as cockpit. They **do not** claim those parameters are measured battery capacity.
- The planner also requires a current GPS/OSRM match, fresh proven BMS SoC, and the FAZ 4 station-specific OSRM road detour evidence.
- It uses remaining kilometres and excludes stations already behind the vehicle.
- To avoid misapplying OSRM station comparisons measured from the **original** departure point, automatic stop suggestion is restricted to the initial ≤0.5% of the route. Once past that gate, the UI directs the driver to reroute from the current GPS point. Read-only station road data remains visible.
- Route distance/energy/SoC estimates are a **user-consumption MODEL**, not observed energy draw, an SoH test, charger occupancy, price, dwell time, or a safe arrival guarantee. Negative arrival SoC remains a model deficit and is not rendered as a real battery percentage.

## Future trip calibration — not yet live

`TripEnergyCalibration` is a pure, testable measurement-evidence gate ready for a future **validated BMS discharged-kWh counter** and true GPS travelled-distance accumulator. It requires at least 20 km per trip, bounded durations, synchronized timestamped actual sensors, no charging event and reasonable kWh/100 km. Only after at least three valid samples and 150 km total does it produce an **opt-in proposal**. This proposal is *never automatically written* to DataStore and is not treated as an official VW consumption/SoH measurement.

**There is no BMS cumulative-energy PID integration in this phase.** If the user later confirms a real BMS signal and wants calibration, add its verified decoder, explicit opt-in workflow, charging detection, and on-device QA in a separate PR. No undocumented MEB DIDs or write operations are introduced.

## Exit gates and phone/ID.4 acceptance

```bash
./gradlew :nativecockpit:lintDebug :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug --no-daemon --stacktrace
python3 scripts/check_apk_secrets.py nativecockpit/build/outputs/apk/debug/nativecockpit-debug.apk
```

Same-commit native lint + JVM tests + Debug APK + Gitleaks + APK secret scan are required before phase acceptance. New tests cover true/false provenance, stale telemetry, GPS-progress mismatch, mid-trip route distance, consumption sample rejection and proposal minimums.

Actual phone, ID.4, ELM327 and Android Auto tests are not performed here. Test no GPS/no OBD/no self-hosted HTTPS OSRM, reset GPS or change route mid-trip, select station behind vehicle, change user profile and reserve, inspect media/TTS/audio regression, and confirm **no fake battery SoC appears**. API 26 is not claimed (`minSdk=28`).

Previous concerns about historical signing keys/TomTom secrets and production Release safety remain open; no Release APK is signed or published. Close/revert this draft to roll back without disturbing FAZ 0–4.
