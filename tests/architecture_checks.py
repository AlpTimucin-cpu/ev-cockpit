"""Offline source/manifest smoke tests; Android SDK build is a separate required gate."""
from pathlib import Path
from xml.etree import ElementTree
root = Path(__file__).resolve().parents[1]
xml = ElementTree.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
NS = '{http://schemas.android.com/apk/res/android}'
services = list(xml.iter('service'))
assert any(s.get(NS + 'foregroundServiceType') == 'mediaPlayback' for s in services)
assert any(i.get(NS + 'name') == 'androidx.media3.session.MediaLibraryService' for i in xml.iter('action'))
appdesc = ElementTree.parse(root / 'app/src/main/res/xml/automotive_app_desc.xml').getroot()
assert [i.get('name') for i in appdesc.iter('uses')] == ['media']
assert any(a.get(NS + 'name') == '.PlayerActivity' and a.get(NS + 'exported') == 'false' for a in xml.iter('activity'))
assert xml.find('application') is not None
queries = [v.get(NS + 'name') for v in xml.findall('.//queries/package')]
assert 'com.netflix.mediaclient' in queries

for file in (root / 'app/src/main/res').rglob('*.xml'):
    ElementTree.parse(file)
service = (root / 'app/src/main/java/com/evcockpit/mediahub/AudioLibraryService.kt').read_text()
for required in ('onGetLibraryRoot', 'onGetChildren', 'onGetItem', 'onSearch', 'onGetSearchResult', 'onAddMediaItems', 'onSetMediaItems', 'matchStation', 'FAVORITES', 'GROUPS', 'GROUP_PREFIX'):
    assert required in service, required
for forbidden in ('VideoStore', 'PlayerActivity', 'setVideoSurface', 'speedhack', 'video_bypass', 'drive_spoof'):
    assert forbidden not in service, forbidden
station = (root / 'app/src/main/java/com/evcockpit/mediahub/StationStore.kt').read_text()
assert 'toggleFavorite' in station and 'favorite' in station
assert 'includeUri: Boolean = false' in station and 'if (includeUri)' in station
assert 'toMediaItem(includeUri = true)' in service
assert 'Audio playback failure: ${error.errorCodeName}' in service
main = (root / 'app/src/main/java/com/evcockpit/mediahub/MainActivity.kt').read_text()
assert 'PlaylistLoader.fromHttps' in main and 'VideoStore.addAll' in main and 'StationStore.toggleFavorite' in main
assert 'getLaunchIntentForPackage(netflixPackage)' in main and 'com.netflix.mediaclient' in main
assert 'M3uExporter.export' in main and 'EXPORT_PLAYLIST' in main
assert 'PAGE_SIZE = 20' in main and 'VideoStore.add' in main and 'StationStore.add' in main
assert 'onPlayerError' in main
assert 'M3uExporter.kt' in [p.name for p in (root / 'app/src/main/java/com/evcockpit/mediahub').glob('*.kt')]


# v0.6 performance and real-time media browser refresh checks.
assert 'ctrl.setMediaItem(station.toMediaItem())' in main
assert 'ctrl.setMediaItems(playlist.map' not in main
assert 'notifyCatalogueChanged()' in service and 'notifyChildrenChanged' in service
assert 'RECEIVER_NOT_EXPORTED' in service
assert 'ACTION_CATALOG_UPDATED' in station
queue = (root / 'app/src/main/java/com/evcockpit/mediahub/RadioQueuePlanner.kt').read_text()
assert 'MAX_QUEUE_ITEMS = 41' in queue
assert 'sources.subList(from, from + windowSize)' in queue

print('PASS: XML, Android Auto library, station update signals, bounded playback queue, mediaId, M3U, privacy')
print('LIMITATION: Full Android Gradle build and on-device/vehicle tests are not part of this smoke test')

# v0.6.1 preset/source / SportzX integration safety checks
trusted = (root / 'app/src/main/java/com/evcockpit/mediahub/TrustedSources.kt').read_text()
assert 'iptv-org.github.io/iptv/countries/tr.m3u' in trusted
assert 'devstreaming-cdn.apple.com/videos/streaming/examples' in trusted
launcher = (root / 'app/src/main/java/com/evcockpit/mediahub/SportzXLauncher.kt').read_text()
assert 'queryIntentActivities' in launcher and 'getLaunchIntentForPackage' in launcher
assert 'SportzXLauncher.openIfInstalled(this)' in main
assert 'importFromHttps(source.url)' in main
assert 'getLaunchIntentForPackage' not in service
assert 'android.intent.category.LAUNCHER' in (root / 'app/src/main/AndroidManifest.xml').read_text()
print('PASS: Public IPTV presets, Apple HLS sample and external SportzX launcher wiring')

assert 'TrustedSources.TRT_HABER' in main
assert 'TRT_HABER' in trusted
print('PASS: TRT CDN channel added to phone-only video catalogue')

# v0.7 user-specified site in browser, and HEAD-only phone diagnostics.
assert 'https://sportzxlive.com/' in launcher
assert 'fun openSite(activity: Activity)' in launcher
assert 'SportzXLauncher.openSite(this)' in main
probe = (root / 'app/src/main/java/com/evcockpit/mediahub/StreamProbe.kt').read_text()
assert 'requestMethod = "HEAD"' in probe and 'instanceFollowRedirects = false' in probe
assert 'fun checkStream(name: String, url: String)' in main
assert 'Thread {' in main and 'StreamProbe.check(url)' in main
assert 'StreamProbe' not in service and 'SportzXLauncher' not in service
print('PASS: v0.7 SportzX browser shortcut, network HEAD probe and driver-safe service isolation')

# v0.8: paginated Media3 objects, lifecycle-safe imports, and CI APK production.
assert 'LibraryPage.slice(StationStore.load' in service
assert 'val found = LibraryPage.slice(matches(query), page, pageSize)' in service
assert 'isFinishing || isDestroyed' in main
assert 'M3U bağlantı hatası: ${e.message' not in main
assert (root / 'app/src/test/java/com/evcockpit/mediahub/MediaHubUnitTest.kt').exists()
workflow = (root / '.github/workflows/android-debug-apk.yml').read_text()
assert ':app:testDebugUnitTest :app:assembleDebug' in workflow
assert 'test -s app/build/outputs/apk/debug/app-debug.apk' in workflow
assert 'sha256sum' in workflow
assert 'versionName = "0.8"' in (root / 'app/build.gradle.kts').read_text()
print('PASS: v0.8 paginated browsing, safe UI errors, Gradle JVM tests and APK checksum workflow')
