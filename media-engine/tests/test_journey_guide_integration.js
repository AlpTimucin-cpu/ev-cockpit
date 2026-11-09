// Guard the provided route / native OBD / progress integration guide.
// No extra map, no simulated telemetry, and no second JS-to-Android bridge.
'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const root=path.resolve(__dirname,'../overlay/fermata/src/main');
const html=fs.readFileSync(path.join(root,'assets/evcockpit/navigator.html'),'utf8');
const live=fs.readFileSync(path.join(root,'assets/evcockpit/evcockpit-live-map.js'),'utf8');
const adapter=fs.readFileSync(path.join(root,'assets/evcockpit/evcockpit-navigator-adapter.js'),'utf8');
const android=fs.readFileSync(path.join(root,
  'java/me/aap/fermata/ev/EvCockpitNavigatorActivity.java'),'utf8');
// The app already has a single Leaflet map and genuine OSRM geometry.
// Do not paste an unrelated map-canvas sample from the technical guide.
assert.equal((html.match(/L\.map\('map'/g)||[]).length,1,
  'one Leaflet map belongs to navigator');
assert.match(adapter,/attachExistingMap/);
assert.match(html,/routeProgressGeometry\(/);
assert.match(html,/showRouteGpsProgress\(/);
assert.match(html,/x\.accuracy>60/);
assert.match(html,/Date\.now\(\)-x\.timeMs>30000/);
assert.match(html,/var pct=Math\.max\(0,Math\.min\(100,position\*100\)\)/);
assert.match(html,/var iconPct=Math\.min\(92,Math\.max\(0,pct\)\)/);
assert.match(html,/data\.roadKm\*\(1-position\)/);
// The display-only premium timeline is sourced from the existing native
// GPS projection; no static illustration values can reach it.
assert(html.includes('id="ev-km-travelled"'));
assert(html.includes('id="ev-km-remaining"'));
assert(html.includes('function setTimelineDistances(travelled,remaining)'));
assert(html.includes('setTimelineDistances(Math.max(0,data.roadKm-remaining),remaining)'));
assert(html.includes('setTimelineDistances(NaN,NaN)'));
// Existing native callback owns the source of truth; never poll a fabricated
// JSON payload with static 120km/h or 68% battery values.
assert.match(android,/new EvCockpitLiveTelemetry\(/);
assert.match(android,/send\("mediaengineTelemetry",obdPacket\(data\)\)/);
assert.match(html,/window\.mediaengineTelemetry=function/);
assert.doesNotMatch(html,/AndroidBridge\.getLiveOBDData\(/);
assert.doesNotMatch(live,/getLiveOBDData\(/);
// Keep native fallback & vehicle safety rules unchanged.
assert.match(android,/addJavascriptInterface\(new Bridge\(\),"MediaEngineNative"\)/);
assert.match(android,/videoLaunchBlockedByMotion/);
console.log('PASS: one map, native data, live OSRM geometry, GPS-driven bounded progress');
