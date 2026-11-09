#!/usr/bin/env python3
"""Validate the actual MediaEngine auto-flavor manifest AFTER patch_mediaengine.py ran.
Native Android Auto POI is discoverable; this is not a parked CAR_LAUNCHER app.
This structural test cannot override Google Play distribution restrictions.
"""
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

root=Path(sys.argv[1])
a='{http://schemas.android.com/apk/res/android}'
main=ET.parse(root/'fermata/src/main/AndroidManifest.xml').getroot()
auto=ET.parse(root/'fermata/src/auto/AndroidManifest.xml').getroot()
desc=ET.parse(root/'fermata/src/auto/res/xml/automotive_app_desc.xml').getroot()

app=main.find('application')
assert app is not None
meta=[m for m in app.findall('meta-data')
      if m.get(a+'name')=='com.google.android.gms.car.application']
assert len(meta)==1, 'exactly one Android Auto discovery metadata'
assert meta[0].get(a+'resource')=='@xml/automotive_app_desc'

auto_app=auto.find('application')
services=[s for s in auto_app.findall('service')
          if s.get(a+'name')=='me.aap.fermata.ev.EvCockpitCarPoiService']
assert len(services)==1,'single existing POI CarAppService registration'
service=services[0]
assert service.get(a+'exported')=='true'
assert service.get(a+'enabled','true')=='true'
all_actions=[i.get(a+'name') for f in service.findall('intent-filter')
             for i in f.findall('action')]
all_categories=[c.get(a+'name') for f in service.findall('intent-filter')
                for c in f.findall('category')]
assert 'androidx.car.app.CarAppService' in all_actions
assert 'androidx.car.app.category.POI' in all_categories
assert 'androidx.car.app.category.NAVIGATION' not in all_categories, (
    'navigation category requires an implemented CarAppService')
assert 'android.intent.category.CAR_LAUNCHER' not in all_categories, (
    'CAR_LAUNCHER is reserved for supported parked app Activities')
capabilities={u.get('name') for u in desc.findall('uses')}
assert {'template','media'}.issubset(capabilities), (
    'retain MediaEngine media and CarAppLibrary template simultaneously')
print('PASS Android Auto: metadata, real POI service, media+template retained')
