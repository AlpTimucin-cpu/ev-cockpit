# FAZ 3 — Route weather at actual estimated passage time

Scope: **only** `:nativecockpit` route weather and its foreground, non-intrusive TTS warning path. This is a stacked **draft** on FAZ 2 PR #27. Neither `main` nor `feature/ev-cockpit` is changed.

## Sources and claims

- Input route coordinates, distance fraction, and duration are taken from real OSRM data. The progress fraction is admitted only when `RouteProgressTracker` successfully projects a fresh, accurate Fused GPS fix onto the route.
- Arrival time at waypoint `d` uses `now + osrmDurationSeconds * (d/geometryTotal - gpsProgressFraction)`. OSRM travel time is a **routing-profile estimate**, not live traffic and not measured arrival time.
- At most **eight** samples across the *remaining* route, at approximately 40 km intervals. When over 280 km remains, spacing increases so the actual final destination is included. Distance is computed from route geometry (a proxy for route travel distance).
- Open-Meteo `/v1/forecast`: multiple latitude/longitude, `hourly=temperature_2m,wind_speed_10m,weather_code,precipitation_probability`, `timezone=GMT`, `timeformat=unixtime`, fixed SI-compatible temperature/wind units. A point is shown only if the nearest UTC hourly forecast is within one hour of the calculated passage time. Invalid/missing/misaligned values are rejected.
- The map continues to show the selected forecast samples; the foreground cockpit pill identifies **Open-Meteo forecast**, last fetch age, and a manual **Yenile** action. If a retry fails, the last data is retained and marked stale with a warning. Never label a prediction as an observed temperature.
- Active UI refreshes forecasts about every **30 minutes**, or on manual request. It waits for a valid GPS fix rather than generating a fake origin point. No location history is persisted.
- Severity policy is route-scoped: no repeated TTS for unchanged danger; warnings only when the forecast risk rises into a noteworthy level. TTS requires foreground phone mode, user voice setting, no imminent navigation turn, and acquired audio focus. Android Auto does not receive arbitrary non-template voice announcements from this path.
- WorkManager **not** scheduled for 30-minute background updates: its periodic minimum is 15 minutes, but execution under Doze/battery policy is inexact, and background GPS permission is absent. Only lifecycle-driven foreground observation is implemented.
- Open-Meteo data remains an estimate; forecasts, actual road conditions and arrival times can differ. Verify Open-Meteo attribution/terms for the deployed app.

## Verification

```bash
./gradlew :nativecockpit:lintDebug :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug --no-daemon --stacktrace
python3 scripts/check_apk_secrets.py nativecockpit/build/outputs/apk/debug/nativecockpit-debug.apk
```

New deterministic unit tests:
- `RouteForecastPolicyTest`: actual GPS progress, ≤8 stops, endpoint coverage, times, missing input.
- `HourlyForecastParserTest`: UTC closest-hour match, precip%, invalid/out-of-horizon rejection.
- `WeatherChangeAlertGateTest`: severity transitions, no duplicate alerts, route change, stale forecasts.

Continue to require same-commit GitHub Actions lint + unit test + Debug APK + Gitleaks + APK secret scanner. **CI is not physical-device, ELM327, Android Auto, or network-integration acceptance.**

## Device acceptance / open limitations

1. On a real phone, grant foreground precise location and verify only a real OSRM route creates forecast markers. Without a GPS match, show a clear error and do not invent departure position or ETA.
2. Start a long route (e.g. Niğde–Antalya). Verify ≤8 markers, exact route-end coverage, UTC-to-local display if shown, temperature, wind and rain probability against live Open-Meteo JSON. Compare estimated times with real driving; do not call them real-time traffic.
3. Kill the network, retain last forecast, visibly show age and refresh warning. Verify manual retry works after reconnect and a route change cannot show old-route forecasts.
4. Observe two unchanged forecast updates and a severity increase. Only the increase should prompt voice; no alert near an imminent navigation maneuver or without audio focus.
5. Verify map layer order (route below weather symbols), Android Auto template constraints, landscape HUD overlap, and that no background location/notification process is started.

Rollback: revert this phase's commits/close this draft PR; keep FAZ 2 draft intact. Production release remains on hold until prior historical signing/TomTom secret risk is resolved. `minSdk=28` remains unchanged; API 26 compatibility is not asserted.
