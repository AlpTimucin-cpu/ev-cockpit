#!/usr/bin/env python3
"""Guard against reintroducing fake input-driven energy screens into MediaEngine."""
from pathlib import Path
import sys

root=Path(sys.argv[1])
ev=(root/"fermata/src/main/java/me/aap/fermata/ev/EvCockpitEvActivity.java").read_text()
obd=(root/"fermata/src/main/java/me/aap/fermata/ev/EvCockpitObdActivity.java").read_text()
patch=Path(sys.argv[2]).read_text()
for term in ["Kullanılabilir kapasite (kWh)","Mevcut şarj (%)","TL/kWh operatör fiyatı",
             "Tamamen manuel hesaplar","Enerjiyi ve şarj mediaenginesını hesapla"]:
    assert term not in ev, "Manual energy screen remains: " + term
assert 'addTab(tabs,"🚘 Araç",()->startActivity(new Intent(this,EvCockpitOverviewActivity.class)))' in ev
assert 'addTab(tabs,"⚡ Şarj",this::stationsScreen)' in ev
assert "EvCockpitObdActivity.java" in patch
assert "EvCockpitNavigatorActivity.java" in patch
assert "EvCockpitTripJournal.java" in patch
assert "EvCockpitRoadSafety.java" in patch
camera=(root/"fermata/src/main/java/me/aap/fermata/ev/EvCockpitRoadSafety.java").read_text()
assert 'highway' in camera and 'speed_camera' in camera
assert 'LIMIT=120' in camera and '.42' in camera
assert "© OpenStreetMap contributors (ODbL)" in camera
assert "mobile radars" in camera
act=(root/"fermata/src/main/java/me/aap/fermata/ev/EvCockpitNavigatorActivity.java").read_text()
assert "safetyJobs.shutdownNow()" in act
assert "lastSafetyQueryAt<90000" in act
assert 'j.put("speedKmh"' in act
assert "distance[0]<=350" in act
trip=(root/"fermata/src/main/java/me/aap/fermata/ev/EvCockpitTripJournal.java").read_text()
assert "getAccuracy()>35" in trip
assert "getString(KEY" in trip
assert "actualEnergyKwh" in trip and "actualChargeCostTry" in trip
assert "EvCockpitTomTomRouting.java" in patch
tomtom=(root/"fermata/src/main/java/me/aap/fermata/ev/EvCockpitTomTomRouting.java").read_text()
assert "apiVersion=3" in tomtom
assert 'maxPathAlternativeRoutes' in tomtom
assert '"traffic","live"' in tomtom
assert 'setRequestProperty("TomTom-Api-Key",key)' in tomtom
assert 'setRequestProperty("Attributes","routes.summary,routes.legs")' in tomtom
assert 'trafficDelayDurationInSeconds' in tomtom
assert "alternatives=3" in (root/"fermata/src/main/java/me/aap/fermata/ev/EvCockpitNavigatorActivity.java").read_text()
assert "EvCockpitValhallaRouting.java" in patch
assert "EvCockpitCarPoiService.java" in patch
assert 'EvCockpitLauncher' in patch and '@drawable/evcockpit_launcher' in patch
assert "fermata/src/auto/AndroidManifest.xml" in patch
assert "old_launcher='android:name=" in patch
assert 'tools:replace="android:icon,android:label"' in patch
assert (root/"fermata/src/main/res/drawable/evcockpit_launcher.xml").is_file()
assert "EvCockpitLiveTelemetry" in (root/"fermata/src/main/java/me/aap/fermata/ev/EvCockpitOverviewActivity.java").read_text()
for term in [
    'heroSoc=label("— %"', 'heroRange=label("— km  •  Veri yok"',
    'heroVoltage=slot(', 'heroTemp=slot(', 'heroMode=slot(',
    'heroCost=slot(', 'heroConsumption=slot(', 'heroHealth=slot(',
    'heroPower=slot(', 'routeDistance=slot(', 'routeEnergyEstimate=slot(',
    'EvCockpitEnergyModel.estimate(', 'EvCockpitEnergyData.load(',
    'private void renderCore(',
    'renderCore(did,displayValue);', 'private void resetCore()',
    'getSharedPreferences(ADAPTER_PREFS,MODE_PRIVATE)',
    'ui.post(this::resetCore);'
]:
    assert term in obd, "Live dashboard field missing: "+term
assert 'Car Scanner' in obd and 'OBD' in obd
assert '"028C"' in obd and '"1E3B"' in obd and '"2A0B"' in obd
telemetry=(root/"fermata/src/main/java/me/aap/fermata/ev/EvCockpitLiveTelemetry.java").read_text()
navigator=(root/"fermata/src/main/java/me/aap/fermata/ev/EvCockpitNavigatorActivity.java").read_text()
html=(root/"fermata/src/main/assets/evcockpit/navigator.html").read_text()
for rule in [
    'Data displayAt(long now)', 'recent(socAt,now)', 'recent(voltageAt,now)',
    'recent(batteryTempAt,now)', 'recent(chargeModeAt,now)',
    'data.socAt=now', 'data.voltageAt=now',
]:
    assert rule in telemetry, "stale sensor protection missing: "+rule
assert 'd.displayAt(System.currentTimeMillis())' in navigator
assert 'latest.displayAt(System.currentTimeMillis())' in (root/"fermata/src/main/java/me/aap/fermata/ev/EvCockpitOverviewActivity.java").read_text()
for rule in ['energyLowKwh', 'energyHighKwh', 'arrivalSocLower',
             'arrivalSocUpper', 'chargingNeededByModel']:
    assert rule in navigator and rule in html, "route energy binding absent: "+rule
assert "case 'selectObd':bridge.selectObd();" in html, "OBD button not bridged"
assert 'getSharedPreferences("evcockpit_obd_pairing_v1",MODE_PRIVATE)' in navigator
assert 'getSharedPreferences("evcockpit_obd_pairing_v1",MODE_PRIVATE)' in (root/"fermata/src/main/java/me/aap/fermata/ev/EvCockpitOverviewActivity.java").read_text()

assert all("applyFullscreen();" in text for text in [ev,obd,
    (root/"fermata/src/main/java/me/aap/fermata/ev/EvCockpitOverviewActivity.java").read_text()])
assert "stationGps()" in ev and "stationMatches" in ev and "PAGE_SIZE=25" in ev
assert "Canlı soket: doğrulanamadı" in ev
assert "ESKİ genel fiyat (güncel değil)" in ev
assert "Do not show a campaign badge" in ev
assert "cards.setVisibility(View.GONE)" in obd
assert "refreshEpdkSnapshot" in ev
assert "stationScroll.smoothScrollTo(0,0)" in ev
assert ".vehicle-cockpit:not(.has-route) .cockpit-right" in html
assert "technical.setVisibility(android.view.View.GONE)" in (root/"fermata/src/main/java/me/aap/fermata/ev/EvCockpitOverviewActivity.java").read_text()
assert "EvCockpitStationTypes.ac" in ev and "EvCockpitStationTypes.dc" in ev
assert "searchHandler.postDelayed(searchNow,400)" in ev
assert "openEpdkOfficial" in ev and "tr.gov.epdk.sarjetTR" in ev
assert "station_types.relative_to(overlay)" in patch
print("PASS: full-screen phone pages; paged AC/DC, GPS distance and sourced tariffs")
print("PASS: no manual energy form; timestamps per OBD sensor; route energy connected.")

# An ELM327 socket is not a calibrated EV Cockpit sensor feed. Keep numbers blank
# until a vehicle-specific Car Scanner log comparison is confirmed.
decoder=(root/"fermata/src/main/java/me/aap/fermata/ev/EvCockpitMebDecoder.java").read_text()
assert 'VEHICLE_PROFILE_VALIDATED=false' in decoder
assert 'if(!VEHICLE_PROFILE_VALIDATED)return null;' in decoder
assert 'hasPositiveReply(String did,String response)' in decoder
assert 'Car Scanner karşılaştırması gerekli' in obd
assert 'lastRawResponseAt' in telemetry
assert '.cockpit-tripbar>div:first-child{display:none}' in html
assert 'max-height:395px' in html
