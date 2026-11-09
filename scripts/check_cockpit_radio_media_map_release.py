#!/usr/bin/env python3
"""Static regressions that do not require a device or release signing keys."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")

props = read("gradle.properties")
native_gradle = read("nativecockpit/build.gradle.kts")
assert re.search(r"(?m)^android\.enableR8\.fullMode=false\s*$", props), "R8 fullMode guard missing"
assert re.search(
    r'getByName\("release"\)\s*\{[^}]*\bisMinifyEnabled\s*=\s*true',
    native_gradle, flags=re.S
), "Native release R8 minification must be enabled"
assert re.search(
    r'getByName\("release"\)\s*\{[^}]*\bisShrinkResources\s*=\s*true',
    native_gradle, flags=re.S
), "Native release resource shrinking must be enabled"

radio_api = read("nativecockpit/src/main/java/com/evcockpit/data/radio/RadioBrowserApi.kt")
radio_screen = read("nativecockpit/src/main/java/com/evcockpit/presentation/media/RadioScreen.kt")
media_menu = read("nativecockpit/src/main/java/com/evcockpit/presentation/media/MediaHomeScreen.kt")
media_legacy = read("nativecockpit/src/main/java/com/evcockpit/presentation/media/ParkedStreamingDialog.kt")
cockpit = read("nativecockpit/src/main/java/com/evcockpit/presentation/cockpit/CockpitScreen.kt")
launcher = read("nativecockpit/src/main/java/com/evcockpit/presentation/media/MediaLauncher.kt")
webview = read("nativecockpit/src/main/java/com/evcockpit/presentation/media/MediaWebViewScreen.kt")
map_code = read("nativecockpit/src/main/java/com/evcockpit/presentation/map/MapLibreCockpit.kt")
manifest = read("nativecockpit/src/main/AndroidManifest.xml")

assert '@GET("json/stations/search?countrycode=TR")' in radio_api
assert '"clickcount"' in radio_api and 'limit: Int = 50' in radio_api
for field in ('name', 'url_resolved', 'favicon', 'tags', 'votes'):
    assert field in radio_api, f"Radio Browser field missing: {field}"
assert "ExoPlayer.Builder" in radio_screen and "RadioScreen(" in media_menu
assert "InternalMediaPlayer(" in media_menu and "TabRow(" in media_menu
assert "MediaWebViewScreen(" in media_menu and "MediaSite.entries" in media_menu
assert "MediaHomeScreen(" in cockpit and "CockpitMediaPanel(" not in cockpit
assert "MediaLauncher.launchOfficialApp" not in media_menu and "launchOfficialApp" not in media_legacy
repository = read("nativecockpit/src/main/java/com/evcockpit/data/radio/RadioRepository.kt")
assert "Flow<Result<List<RadioStation>>>" in repository
assert "CancellationException" in repository
assert "MediaWebViewScreen(" in media_menu
assert "onShowCustomView" in webview and "onHideCustomView" in webview
assert "getLaunchIntentForPackage" in launcher and "MediaLaunchState.Blocked" in launcher
assert "mediaEnginePackages" in launcher
for package in ('me.app.fermataX.auto.evcockpitpreview.debug', 'me.app.fermataX.auto.evcockpitpreview'):
    assert package in manifest, f"Package visibility query missing: {package}"
assert 'private const val ROUTE_LAYER = "eya-route-line"' in map_code
assert "style.addLayerAbove(weatherLayer, ROUTE_LAYER)" in map_code
assert "style.addLayerAbove(chargingLayer, ROUTE_LAYER)" in map_code
assert "ensureRouteOverlayZOrder(style)" in map_code

print("OK: Native media tab, four embedded WebViews, radio, weather map and release guards.")
