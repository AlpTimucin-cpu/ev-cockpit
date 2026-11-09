// Offline contract regression tests for the actual packaged Android WebView
// adapter. Fake packets are TEST fixtures only, never vehicle measurements.
'use strict';
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const source=fs.readFileSync(path.resolve(__dirname,
  '../overlay/fermata/src/main/assets/evcockpit/evcockpit-navigator-adapter.js'),'utf8');
const order=[],calls=[];
let routeOriginalThrows=false;
const originalAndroidBridge=Object.freeze({nativeMethod(){return 'preserved'}});
const addon={
  attachExistingMap(map){calls.push('attach');return !!map},
  ingestNativeRoute(p){calls.push('route');return !!p},
  setRouteStations(p){calls.push('stations');return p.items.length},
  updateLocation(lat,lon){calls.push('gps');return true},
  updateCarData(speed,soc){calls.push(['telemetry',speed,soc]);return true},
  clearRoute(){calls.push('clear')},
  validateEPDKDataContract(p){
    calls.push('validate');
    return {ok:!!(p.metadata && p.metadata.coverage==='country' &&
      p.stations.length===5000)};
  },
  setStations(stations,metadata){
    if(!stations){calls.push('countryReset');return 0}
    calls.push('country');return stations.length
  },
  getState(){return {soc:18,position:null,countryStationsVerified:false}}
};
let mediaOpened=0;
const w={EVCOCKPITLiveMap:addon,evcockpitLiveLeafletMap:{addLayer(){}},
 AndroidBridge:originalAndroidBridge,
 MediaEngineNative:{openMedia(){mediaOpened++}}};
for(const name of ['mediaengineRoute','mediaengineStations','mediaenginePosition','mediaengineTelemetry','mediaengineStatus']){
 w[name]=()=>{
  order.push(name);
  if(name==='mediaengineRoute' && routeOriginalThrows)throw new Error('test callback failure');
 };
}
const d={querySelector(){return null},getElementById(){return null}};
vm.runInNewContext(source,{window:w,document:d,console,Date,Number,String,
 Array,JSON,Object,Math,RegExp}, {filename:'evcockpit-navigator-adapter.js',timeout:3000});
assert.strictEqual(w.AndroidBridge,originalAndroidBridge,'do not replace Java interface');
assert(w.EVCOCKPITNativeBridge,'typed JS entrypoint exists');
const bridge=w.EVCOCKPITNativeBridge;
assert.equal(bridge.receive('untrusted',{}),false);
assert.equal(bridge.receive('telemetry','{invalid'),false);
assert.equal(bridge.receive('status',{command:'LAUNCH_MEDIAENGINE_32_NO_PARK'}),true,
 'legacy tab handoff must call the safe native media launcher');
assert.equal(mediaOpened,1,'MediaEngine opened through allowlisted native method once');
assert.equal(bridge.receive('status',{command:'DISABLE_PARK_WINDOW'}),false,
 'never accept a driving-restriction bypass command');
assert.equal(bridge.receive('status',{command:'LAUNCH_MEDIAENGINE_32_NO_PARK',arg:'unexpected'}),false,
 'reject extra command arguments rather than forwarding attacker-controlled extras');
assert.equal(mediaOpened,1,'no additional native launch on malformed packet');
assert.equal(bridge.receive('telemetry',[]),false);
assert.equal(bridge.receive('route',JSON.stringify({coordinates:[[35,39],[36,40]]})),true);
assert.deepEqual(order,['mediaengineRoute']);
assert(calls.includes('route'));
assert.equal(bridge.receive('stations',{kind:'route',items:[{id:'x'}]}),true);
assert(calls.includes('stations'));
assert(!calls.includes('country'),'route subset must never become national data');
assert.equal(bridge.epdkCountry({metadata:{coverage:'route'},stations:[]}),false);
assert.equal(bridge.epdkCountry({metadata:{coverage:'country'},stations:[]}),false);
const national=Array.from({length:5000},(_,i)=>({id:'test-'+i}));
assert.equal(bridge.epdkCountry({metadata:{coverage:'country'},stations:national}),true);
assert(calls.includes('country'),'only validated full-country may reach core');
// A malformed country refresh must explicitly clear any old highlight.
const resetsBefore=calls.filter(x=>x==='countryReset').length;
assert.equal(bridge.epdkCountry('{invalid JSON'),false);
assert.equal(calls.filter(x=>x==='countryReset').length,resetsBefore+1);
assert.equal(bridge.receive('telemetry',{fresh:true,soc:'18.3'}),true);
assert(calls.some(p=>Array.isArray(p)&&p[0]==='telemetry'&&p[2]===18.3));
assert.equal(bridge.receive('telemetry',{fresh:false,soc:'12'}),true);
assert(calls.some(p=>Array.isArray(p)&&p[0]==='telemetry'&&p[2]===null),
 'stale SoC must become null');
assert.equal(bridge.receive('gps',{
 lat:39,lon:35,timeMs:Date.now()-999999,accuracy:8,speedKmh:65
}),true);
const gpsCount=calls.filter(x=>x==='gps').length;
assert.equal(gpsCount,0,'old GPS does not enter addon');
assert.equal(bridge.receive('gps',{
 lat:39,lon:35,timeMs:Date.now(),accuracy:8,speedKmh:65
}),true);
assert.equal(calls.filter(x=>x==='gps').length,1);
routeOriginalThrows=true;
assert.equal(bridge.receive('route',{coordinates:[[35,39],[36,40]]}),true,
 'a MediaEngine callback exception must not kill the bridge');
assert.equal(bridge.snapshot().soc,18);
console.log('PASS: typed Android bridge, malformed packets, stale SoC/GPS, no country escalation');
