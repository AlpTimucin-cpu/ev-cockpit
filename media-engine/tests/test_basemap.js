// Offline-only integration test for packaged interactive basemap and real failover.
// These mocked tile events are not a claim of working live Android internet.
'use strict';
const assert=require('assert'),fs=require('fs'),vm=require('vm'),path=require('path');
const assets=path.join(__dirname,'../overlay/fermata/src/main/assets/evcockpit');
const geographyJs=fs.readFileSync(path.join(assets,'evcockpit-offline-geography.js'),'utf8');
const overlayPatch=fs.readFileSync(path.join(__dirname,'../patch_mediaengine.py'),'utf8');
const copyLoop=overlayPatch.match(/for f in \[([^\]]+)\]:\s*\n\s*target=root\/f.relative_to\(overlay\)/);
assert(copyLoop,"APK overlay copy list missing");
assert(copyLoop[1].includes('basemap_controller')&&
       copyLoop[1].includes('basemap_geography'),
       "basemap JS/geography must be COPIED into APK inputs");
 
const basemapJs=fs.readFileSync(path.join(assets,'evcockpit-basemap.js'),'utf8');
assert(!basemapJs.includes('tile.openstreetmap.'), 'old raster basemap must not return');
const tiles=[],outlines=[],labels=[],statuses=[],allLayers=new Set(),panes={};
const map={
 createPane(name){const pane={style:{}};panes[name]=pane;return pane},
 getPane(name){return panes[name]||null},
 hasLayer(layer){return allLayers.has(layer)},
 removeLayer(layer){allLayers.delete(layer)},
 addLayer(layer){allLayers.add(layer)}
};
const L={
 geoJSON(data,options){
  assert(data?.type==='FeatureCollection'&&data.features.length>=15);
  assert(data.features.some(f=>f.properties.name==='Turkey'));
  const layer={data,options,addTo(m){outlines.push(this);m.addLayer(this);return this}};
  return layer;
 },
 marker(coords,options){
  return {coords,options,addTo(m){labels.push(this);m.addLayer(this);return this}};
 },
 divIcon(opts){return opts},
 maplibreGL(opts){
  const gl={events:{},ready:false,on(name,fn){this.events[name]=fn;return this},
   loaded(){return this.ready},areTilesLoaded(){return this.ready}};
  const layer={opts,gl,getMaplibreMap(){return gl},
   addTo(m){tiles.push(this);m.addLayer(this);return this}};
  return layer;
 }
};
const scheduled=[];
const window={L};
const ctx={window,console,Array,Number,Math,
 setTimeout(fn){scheduled.push(fn);return scheduled.length},
 clearTimeout(){}};
vm.createContext(ctx);
vm.runInContext(geographyJs,ctx,{timeout:2500});
assert(window.EVCOCKPIT_OFFLINE_COUNTRIES.features.length>=15,'must ship geography, not blank');
vm.runInContext(basemapJs,ctx,{timeout:2500});
const controller=window.EvCockpitBaseMap;
assert(controller&&typeof controller.init==='function');
controller.init(map,s=>statuses.push(s),[['Ankara',39.9208,32.8541],['İstanbul',41.0082,28.9784]]);
assert.equal(tiles.length,1);
assert.equal(tiles[0].opts.style,'https://tiles.openfreemap.org/styles/dark');
assert.equal(controller.status().online,false);
assert(allLayers.has(outlines[0]),'show geography while vector tiles load');
assert(labels.length>=1);
tiles[0].gl.events.load();
assert.equal(controller.status().online,false,'style-only event is not loaded tiles');
tiles[0].gl.ready=true;tiles[0].gl.events.idle();
assert.equal(controller.status().online,true);
assert(!allLayers.has(outlines[0]));
assert.equal(controller.status().source,'OpenFreeMap');
controller.retry();
const failed=tiles.at(-1);
failed.gl.events.error();
assert.equal(controller.status().online,false);
scheduled.at(-1)();
assert.equal(controller.status().status,'offline');
assert(allLayers.has(outlines[0]));
controller.retry();
// Stale successes from a removed map must never overwrite current state.
failed.gl.ready=true;failed.gl.events.load();
assert.equal(controller.status().online,false);
const next=tiles.at(-1);next.gl.ready=true;next.gl.events.load();
assert.equal(controller.status().online,true);
const beforeKey=tiles.length;
controller.setTomTomKey('TEST_KEY_NOT_A_SECRET');
assert.equal(tiles.length,beforeKey,'traffic key must not replace chosen basemap');
controller.setDay(true);
assert.equal(tiles.at(-1).opts.style,'https://tiles.openfreemap.org/styles/liberty');
controller.setDay(false);
assert(tiles.at(-1).opts.style.endsWith('/dark'));
controller.close();
assert.equal(controller.status().status,'uninitialized');
console.log('PASS: vector loading, real readiness, timeout, stale events, retry, day mode');
