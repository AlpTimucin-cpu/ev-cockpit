// Offline regression test for the actual MediaEngine packaged WebView module.
// All measurements and coordinates here are test fixtures, never APK content.
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const asset=path.resolve(__dirname,'../overlay/fermata/src/main/assets/evcockpit');
const source=fs.readFileSync(path.join(asset,'evcockpit-live-map.js'),'utf8');
const adapter=fs.readFileSync(path.join(asset,'evcockpit-navigator-adapter.js'),'utf8');
assert.match(adapter,/wrap\('mediaengineStations'/,'real native route packet bridge missing');
assert.match(adapter,/mod\.setRouteStations\(p\)/,'route EPDK packet not delivered');
const els=new Map();
function el(id) {
 if(!els.has(id))els.set(id,{id,style:{},textContent:'',children:[],
  addEventListener(){},append(...v){this.children.push(...v)},
  appendChild(v){this.children.push(v)}});
 return els.get(id);
}
const doc={readyState:'loading',addEventListener(){},
 getElementById(id){return el(id)},
 createElement(){return {style:{},textContent:'',children:[],append(){},appendChild(){},addEventListener(){}}}};
const toRad=v=>v*Math.PI/180;
function metres(a,b){
 const dlat=toRad(a[0]-b[0]),dlon=toRad(a[1]-b[1]);
 const h=Math.sin(dlat/2)**2+Math.cos(toRad(a[0]))*Math.cos(toRad(b[0]))*Math.sin(dlon/2)**2;
 return 12742000*Math.asin(Math.min(1,Math.sqrt(h)));
}
const marker=()=>({addTo(m){m.addLayer(this);return this},bindPopup(){return this},setLatLng(){return this}});
const layerGroup=()=>({values:[],addTo(m){m.addLayer(this);return this},
 addLayer(v){this.values.push(v)},clearLayers(){this.values=[]},
 getLayers(){return this.values}});
const L={latLng(a){return {p:a,distanceTo(b){return metres(a,b.p)}}},
 layerGroup,divIcon(){return {}},marker,circleMarker:marker,
 polyline(){return {getBounds(){return {}}}},
 popup(){return {setLatLng(){return this},setContent(){return this},openOn(){return this}}}};
const map={addLayer(){},removeLayer(){},on(){},closePopup(){},
 fitBounds(){},setView(){},getBounds(){return {getSouth(){return 36},getWest(){return 28},getNorth(){return 42},getEast(){return 44}}}};
let clock=Date.now();
class ControlledDate extends Date { static now(){return clock} }
let forecast={temperature_2m:-5,wind_speed_10m:40};
const savedLocal={};
const win={EVCOCKPIT_LIVE_ATTACH_ONLY:true,
 localStorage:{getItem(k){return savedLocal[k]||null},setItem(k,v){savedLocal[k]=v}},
 setTimeout,clearTimeout,setInterval(){},addEventListener(){},dispatchEvent(){},
 fetch:async function(url){
   assert.match(url,/api\.open-meteo\.com\/v1\/forecast/);
   return {ok:true,json:async()=>({current:{...forecast,time:Math.floor(clock/1000)}})};
 }};
const ctx={window:win,document:doc,L,Map,Date:ControlledDate,Math,Number,String,Array,
 Set,JSON,Promise,Event,AbortController,console};
vm.runInNewContext(source,ctx,{timeout:2000,filename:'evcockpit-live-map.js'});
(async()=>{
 const m=win.EVCOCKPITLiveMap;
 assert(m.attachExistingMap(map));
 // Verify the actual packaged module creates a single transparent, bounded
 // Orbis v2 tile layer — never a second Leaflet map or artificial line colors.
 let madeFlow=null;
 L.tileLayer=function(url,options){madeFlow={url,options};return madeFlow};
 assert.equal(m.createTomTomFlowTileLayer('YOUR_API_KEY','evcockpit-live-traffic'),null);
 assert.equal(m.createTomTomFlowTileLayer('', 'evcockpit-live-traffic'),null);
 assert.equal(m.createTomTomFlowTileLayer('bad key', 'evcockpit-live-traffic'),null);
 const configured=m.createTomTomFlowTileLayer('TEST_TOMTOM_KEY_123456789',
     'evcockpit-live-traffic');
 assert.equal(configured,madeFlow);
 assert(configured.url.includes('api.tomtom.com/maps/orbis/traffic/flow/raster/tile/'), 'official TomTom v2 flow endpoint');
 assert.match(configured.url,/apiVersion=2/);
 assert.equal(configured.options.pane,'evcockpit-live-traffic');
 assert.equal(configured.options.updateWhenIdle,true);
 assert.equal(configured.options.keepBuffer,2);
 assert.equal(await m.toggleTraffic(),false,'missing provider must fail closed');
 assert(m.ingestNativeRoute({coordinates:[[35,39],[35.1,39.1]],distanceKm:15,durationMin:20}));
 // Native packet is a route subset; count may exceed the sent item count.
 assert.equal(m.setRouteStations({kind:'route',total:7,items:[
  {id:'official-1',lat:39.03,lon:35.03,ccs:true,title:'EPDK CCS'},
  {cluster:true,lat:39.04,lon:35.04,count:6}
 ]}),1);
 assert.equal(el('evcockpit-station-count').textContent,'7 (rota)');
 assert.equal(m.getState().corridorStations,1);
 // Personal accident/closure reports are local unverified notes. Old, future
 // and malformed records must never masquerade as current road intelligence.
 const localNow=new Date(clock).toISOString();
 assert.equal(m.importIncidents([
   {id:'acc1',lat:39, lng:35, type:'accident',timestamp:localNow},
   {id:'old',lat:39, lng:35, type:'closed',
    timestamp:new Date(clock-25*60*60*1000).toISOString()},
   {id:'future',lat:39, lng:35, type:'accident',
    timestamp:new Date(clock+60*60*1000).toISOString()},
   {id:'bad',lat:999, lng:35, type:'accident',timestamp:localNow}
 ]),1);
 assert.match(el('evcockpit-incident-count').textContent,/1 yerel ihbar.*doğrulanmadı/);
 assert.equal(JSON.parse(savedLocal['evcockpit-incidents-v1']).length,1);
 assert.equal(m.removeLocalIncident('acc1'),true);
 assert.equal(m.removeLocalIncident('acc1'),false);
 assert.equal(m.getState().localIncidents,0);
 assert.equal(JSON.parse(savedLocal['evcockpit-incidents-v1']).length,0);

 // A *partial* setStations array must NEVER become a global DC source.
 m.updateLocation(39.02,35.02);
 m.updateCarData(50,19);
 assert.match(el('evcockpit-nearest-dc').textContent,/Güncel ve tam EPDK/);
 const near={id:'near',lat:39.02,lon:35.02,dc:true,title:'Nearest DC'};
 assert.equal(m.setStations([near]),0);
 assert.equal(m.getEPDKValidation().ok,false);
 assert.equal(m.isCountryDataReady(),false);
 assert.equal(el('evcockpit-nearest-dc').textContent,'—');
 // Incomplete metadata must not promote a regional data slice.
 m.setStations([near],{source:'EPDK',coverage:'country',complete:true,
   geocodedCount:1,verifiedAtUtc:new Date(clock).toISOString()});
 assert.equal(m.isCountryDataReady(),false);
 // Deliberately using generated *test fixtures*, not claiming live EPDK records.
 const fixture=(id,lat=40,lon=36)=>({
   id,coordinates:{lat,lng:lon},socketType:'DC',power_kw:50,
   currentStatus:'unknown',title:'Test DC '+id,operator:'TEST ONLY'
 });
 const national=Array.from({length:5000},(_,i)=>fixture('test-'+i));
 national[0]=fixture('near',39.02,35.02);
 national[0].title='Nearest DC';
 const metadata={source:'EPDK',coverage:'country',complete:true,
   geocodedCount:national.length,verifiedAtUtc:new Date(clock).toISOString()};
 assert.equal(m.validateEPDKDataContract({stations:national,metadata}).ok,true);
 // Explicit mandatory-fields checks and fail-closed reject (no partial map).
 for (const field of ['id','coordinates','socketType','power_kw','currentStatus']){
   const batch=national.slice();
   batch[0]={...batch[0]};
   delete batch[0][field];
   assert.equal(m.validateEPDKDataContract({stations:batch,metadata}).ok,false,
     'reject missing '+field);
 }
 const duplicate=national.slice();
 duplicate[1]={...duplicate[0]};
 assert.equal(m.validateEPDKDataContract({stations:duplicate,metadata}).ok,false);
 const invalidLive=national.slice();
 invalidLive[0]={...invalidLive[0],currentStatus:'available'};
 assert.equal(m.validateEPDKDataContract({stations:invalidLive,metadata}).ok,false,
   'do not trust untimed live availability');
 const validLive=national.slice();
 validLive[0]={...validLive[0],currentStatus:'available',
   statusObservedAtUtc:new Date(clock).toISOString()};
 assert.equal(m.validateEPDKDataContract({stations:validLive,metadata}).ok,true);
 assert.equal(m.setStations(national,metadata),5000);
 assert.equal(m.isCountryDataReady(),true);
 assert.match(el('evcockpit-nearest-dc').textContent,/Nearest DC/);
 assert.equal(m.getState().countryStationsVerified,true);
 // An invalid or stale telemetry value cannot carry over prior SoC.
 m.updateCarData(30,101);
 assert.equal(el('evcockpit-soc').textContent,'—');
 assert.equal(el('evcockpit-nearest-dc').textContent,'—');
 m.updateCarData(30,21);
 assert.equal(el('evcockpit-nearest-dc').textContent,'—');
 m.updateCarData(0,0);
 assert.equal(el('evcockpit-soc').textContent,'0.0%');
 assert.match(el('evcockpit-nearest-dc').textContent,/Nearest DC/);
 // Fresh SoC with an old GPS fix does not justify a highlighted station.
 clock+=31000;
 m.updateCarData(0,19);
 assert.equal(el('evcockpit-nearest-dc').textContent,'—');
 m.updateLocation(39.02,35.02);
 assert.match(el('evcockpit-nearest-dc').textContent,/Nearest DC/);
 // Measured energy profile is mandatory and weather applies exactly once.
 assert.equal(m.setEnergyProfile({usableBatteryKWh:50,consumptionKWhPer100Km:18}),true);
 await new Promise(resolve=>setTimeout(resolve,40));
 assert.equal(m.getState().weather.multiplier,1.15);
 assert.match(el('evcockpit-range').textContent,/kWh/);
 // A stale EPDK date disables nearest-DC even when coverage was once complete.
 m.setStations(national,{...metadata,verifiedAtUtc:'2024-01-01T00:00:00Z'});
 assert.equal(m.isCountryDataReady(),false);
 assert.equal(el('evcockpit-nearest-dc').textContent,'—');
 // Geometric 5 km buffer: near 4.9 km accepted; far 5.2 km rejected.
 const horizontal={coordinates:[[35,39],[35.12,39]],distanceKm:11,durationMin:10};
 assert.equal(m.ingestNativeRoute(horizontal),true);
 m.updateLocation(39,35);
 m.updateCarData(0,80);
 const corridorNational=Array.from({length:5000},(_,i)=>fixture('away-'+i));
 corridorNational[0]=fixture('inside',39.044,35.06); // ~4.89 km
 corridorNational[1]=fixture('outside',39.047,35.06); // ~5.23 km
 assert.equal(m.setStations(corridorNational,{
   ...metadata,verifiedAtUtc:new Date(clock).toISOString()}),5000);
 assert.equal(m.getState().corridorStations,1);
 assert.equal(el('evcockpit-station-count').textContent,'1');
 // Replacing with a partial dataset must leave zero addon station pins.
 assert.equal(m.setStations([corridorNational[0]],metadata),0);
 assert.equal(m.getState().countryStationsVerified,false);
 assert.equal(el('evcockpit-station-count').textContent,'—');
 // Independently exercise temperature-only, wind-only and neutral conditions.
 const cases=[
  {temperature_2m:-5,wind_speed_10m:40,expected:1.15},
  {temperature_2m:-3,wind_speed_10m:10,expected:1.15},
  {temperature_2m:12,wind_speed_10m:35,expected:1.15},
  {temperature_2m:12,wind_speed_10m:10,expected:1}
 ];
 const kwh=[];
 for(const trial of cases){
  forecast={temperature_2m:trial.temperature_2m,wind_speed_10m:trial.wind_speed_10m};
  assert.equal(m.ingestNativeRoute(horizontal),true);
  await new Promise(resolve=>setTimeout(resolve,12));
  assert.equal(m.getState().weather.multiplier,trial.expected);
  kwh.push(Number.parseFloat(el('evcockpit-range').textContent));
 }
 assert(kwh.every(Number.isFinite),'measured energy profile must yield numeric kWh');
 assert(kwh[0]>kwh[3] && Math.abs(kwh[0]/kwh[3]-1.15)<0.07,
   'weather penalty should be applied once, not twice');
 // Re-opening/importing past the 24-hour policy must drop expired entries.
 const noteTime=new Date(clock).toISOString();
 assert.equal(m.importIncidents([{id:'exp',lat:39,lng:35,
   type:'closed',timestamp:noteTime}]),1);
 clock+=24*60*60*1000+1;
 assert.equal(m.importIncidents([]),0);
 assert.equal(JSON.parse(savedLocal['evcockpit-incidents-v1']).length,0);
 console.log('PASS: phase-3 EPDK strict contract, 5 km buffer, SoC, GPS fail-safe and single weather multiplier');
})().catch(error=>{console.error(error);process.exitCode=1});
