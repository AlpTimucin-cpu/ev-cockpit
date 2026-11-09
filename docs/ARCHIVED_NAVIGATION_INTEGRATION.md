# EV Cockpit — Map, Live Vehicle Data, and Route Progress Integration

> Source: Three sections of the user-uploaded technical guide `tesla_integration_guide.docx`. Its code examples were adapted to the working architecture rather than pasted directly into the project.

## 1. One map, actual OSRM geometry

The application already creates **one** `L.map('map')` in `navigator.html`. `evcockpit-live-map.js` and the traffic provider connect to it using `attachExistingMap(map)`; a second `L.map('map-canvas')` is not created.

Actual routes follow the OSRM schema `/route/v1/driving/{lon},{lat};{lon},{lat}?overview=full&geometries=geojson`. A Leaflet polyline is drawn after validating `code='Ok'`, `routes[0].geometry.coordinates`, distance, and duration. Route calculations can fail because of network errors, timeouts, or invalid geometry; a failed request does not clear the map.

- Server: public OSRM demo, with no guarantee for heavy use
- Basemap: actual open-map layers configured in the project; no second tile basemap is added
- Route duration: explicitly described as excluding traffic unless a traffic-service duration has been verified
- Origin/destination: user selection or explicitly authorized GPS; **no fixed Istanbul coordinates**

## 2. Android Bluetooth ELM327 / WebView event flow

`EvCockpitNavigatorActivity.java` forwards measurements from the existing `EvCockpitLiveTelemetry` module through the **single, already registered** JavaScript bridge and the `mediaengineTelemetry` event. No new `AndroidBridge` or periodic `getLiveOBDData()` bridge is added. Measurements are processed when received rather than polling every 500 ms; raw or uncalibrated PIDs are not treated as actual SoC/speed.

### Data sources

- **SoC, HV voltage, battery temperature, power:** valid, fresh, validated OBD readings only.
- **Speed, position, route progress:** actual, adequately accurate GPS fixes received within the last 30 seconds only.
- **Range and energy:** `—` when required measurements are missing; otherwise labeled “model/estimate”.
- Values such as `120 km/h`, `68%`, `355 V`, and `318 km` in the guide are **simulation examples and were not hardcoded into the actual application**.

Original media sessions, DRM, system Bluetooth pairing, and Android Auto driving restrictions are not modified.

## 3. Actual route progress and the moving vehicle icon

`navigator.html` reduces OSRM GeoJSON coordinates to approximately 1,800 nodes, projects each actual GPS fix onto the nearest route segment, and calculates the completed fraction. This also provides the actual remaining distance needed for the simple `(totalKm-remainingKm)/totalKm` formula.

- Progress is calculated within `0..100` when GPS is fresh (≤30 seconds), accuracy is ≤60 m, and route proximity is acceptable.
- The lower bar fill stays within `0..100%`, and the **🚙 vehicle icon stays within `0..92%`**, preventing overflow.
- If GPS is unavailable, stale, or off-route, the vehicle icon is hidden and “No GPS route progress / position off-route” is displayed.
- The same actual `mediaenginePosition` event updates all relevant screens. No sample or fabricated progress trigger is used.

## 4. Branding, privacy, and licensing boundaries

The application's **visible name** is `EV Cockpit`. Technical Java class names, some file paths, JSON event names, and package IDs have not yet been renamed, preserving backward compatibility and allowing the existing APK to update as the same application. Changing them all at once could cause data loss or break builds for existing users.

Third-party open-source attribution (for example, [LICENSE](../LICENSE) and [NOTICE](../NOTICE.md)) and names required by licenses are retained. The project is not considered production-validated until usage permissions and real-device tests are complete.
