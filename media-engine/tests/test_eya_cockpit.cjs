// Guard the actual APK asset graph: skin + unchanged route/media bridge.
'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const dir=path.resolve(__dirname,'../overlay/fermata/src/main/assets/evcockpit');
const read=name=>fs.readFileSync(path.join(dir,name),'utf8');
const html=read('navigator.html'),css=read('eya-cockpit-3070.css');
const js=read('evcockpit-tesla-cockpit.js');
assert.match(html,/<title>EV Cockpit/);
assert.match(html,/data-eya-layout="cockpit-v4"/);
assert.equal((html.match(/id="map"/g)||[]).length,1,'must keep exactly one Leaflet map');
assert.equal((html.match(/window\.evcockpitLiveLeafletMap=map/g)||[]).length,1);
for(const file of ['eya-cockpit-3070.css','evcockpit-tesla-cockpit.js',
                   'evcockpit-live-map.js','evcockpit-navigator-adapter.js',
                   'traffic-provider.js','evcockpit-mediaengine-media.js']){
 assert(html.includes((file.endsWith('.css')?'href="':'src="')+file+'"'),file+' not loaded');
}
for(const id of ['dock-route','dock-stations','dock-apps','dock-journal','dock-layers',
 'co-trip-track','co-trip-fill','co-trip-arrival','co-maneuver',
 'evcockpit-drive-speed','evcockpit-side-soc','evcockpit-side-range','evcockpit-route-stop-markers']){
 assert(html.includes('id="'+id+'"'),'lost existing control '+id);
}
for(const label of ['Rota','İstasyon','Medya','Yolculuklar','Ayarlar']){
 assert(html.includes('class="dock-label">'+label+'</span>'),'missing real tab '+label);
}
assert.match(css,/left:30%!important/);
assert.match(css,/width:70%!important/);
assert.match(css,/#26d5ff/);
assert.match(css,/#2385ff/);
assert.match(css,/#45ecc7/);
assert.match(css,/#vehicle-cockpit:not\(\.has-route\) \.cockpit-tripbar\s*\{display:none!important\}/);
assert.match(css,/\.tripbar-track\.gps-confirmed::after/);
assert.match(css,/prefers-reduced-motion:reduce/);
assert.match(css,/@media \(orientation:portrait\), \(max-width:639px\)/);
assert.match(js,/function markDockActive\(/);
assert.match(js,/observe\('mediaengineTelemetry',onTelemetry\)/);
assert.match(js,/observe\('mediaenginePosition',onPosition\)/);
assert.match(js,/observe\('mediaengineRoute',onRoute\)/);
assert.match(js,/observe\('mediaengineStations',onStations\)/);
assert.match(js,/validAge\(S\.socAt,TTL,now\)/);
assert(!/^\s*(?:L\.map\(|window\.L\.map\()/m.test(js),'overlay must not create a second Leaflet map');
console.log('PASS: cockpit v4 linked to the working navigator, native OBD and media; route/GPS fail-safe');
