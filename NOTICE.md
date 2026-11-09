# Third-Party Notices (NOTICE)

**EV Cockpit** is an independent experimental project. It is not endorsed by any vehicle manufacturer, map/data provider, or external media application. Third-party names and trademarks belong to their respective owners.

The **EV Cockpit preview APK** built from the MediaEngine/Fermata 2.2.2 foundation is derived from a pinned GPL-3.0 open-source codebase. The derived source and corresponding changes are subject to GPL-3.0 obligations (see [LICENSE](LICENSE)). The repository's independent media history and third-party dependencies may carry separate copyright/license terms; this notice does not change existing ownership.

> This table is informational, not legal advice. Check entries marked `[verify]` against their own sources/terms; versions and terms change over time.

| Component / data | Usage | License / terms | Required action |
|---|---|---|---|
| Fermata-derived media foundation (open source) | Base media application | GPL-3.0 verified in LICENSE at [pinned upstream commit](https://github.com/moris545454/araba-/tree/3f45ad3557a25822c2a3a9f152f32ef82f57e005) | Retain `LICENSE` in the repository; make the complete corresponding source available when distributing the APK and identify changes |
| OpenStreetMap data | Maps, speed-camera points | ODbL 1.0 | Display “© OpenStreetMap contributors” on the map and retain the license link |
| OSM Foundation tile server | Map tiles | [Tile Usage Policy](https://operations.osmfoundation.org/policies/tiles/) | Appropriate User-Agent/Referer; no bulk downloading or cache preloading |
| OSM France tiles | Fallback tile source | [Usage terms](https://www.openstreetmap.fr/usage/) | Use within the applicable terms and limits |
| TomTom Orbis | Traffic, maps | TomTom API/map terms; per-user key | “© TomTom” attribution `[verify]`; do not embed the key in the repository or APK; see [SECURITY_TOMTOM.md](SECURITY_TOMTOM.md) |
| Natural Earth 110m | Offline country boundaries | Public domain | Courtesy attribution may be provided |
| Turkish provincial-capital data | Offline fallback map | MIT (according to the project README) `[verify]` | Keep the source and license text alongside the file |
| Leaflet | Map library | BSD-2-Clause | Retain the license text in distributed copies |
| MapLibre GL and Leaflet adapter | Vector map | License files supplied with the distributed assets | Preserve packaging of `MAPLIBRE_LICENSE.txt` and `MAPLIBRE_LEAFLET_LICENSE.txt` |
| OSRM | Routing | Software: BSD-2-Clause; the demo server has a separate usage policy | Use a separate server for heavy usage |
| Photon | Address search | Software: Apache-2.0; data: OSM/ODbL | Follow the public server's policy |
| Open-Meteo | Weather forecasts | Data: CC BY 4.0; free API access may be restricted to non-commercial use `[verify]` | Provide attribution and check current terms |
| EPDK station registry | Charging-station listings | Publicly accessible data; reuse terms `[verify]` | Display the snapshot date and data source |

## Historical source preservation

The older `main` Media Hub had additional historical notices for Mola/Fermata,
OSM/Leaflet, TomTom and independent media dependencies. Its history and
corresponding source obligations remain applicable; this document does not
waive or supersede those licenses. Review all upstream licenses before any
redistribution, particularly when an old APK does not match the native
cockpit source tree.

## Exclusions

This project does not and must not include databases, premium entitlements, server access, trademarks, or code belonging to third-party commercial applications, such as radar/warning applications.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for detailed historical media dependencies.
