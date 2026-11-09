// Integration checks for the actual packaged navigation HTML: no network required.
const fs=require('fs');
const vm=require('vm');
const assert=require('assert');
const path=require('path');
const file=process.argv[2] || path.join(__dirname,'../overlay/fermata/src/main/assets/evcockpit/navigator.html');
const html=fs.readFileSync(file,'utf8');
const inline=[...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)]
 .map(m=>m[1]).find(x=>x.includes('mediaengineSuggestions'));
assert(inline,'MediaEngine native navigation JavaScript missing');
for(const id of ['from','to','from-list','to-list','locate','plan','swap']){
 assert(html.includes('id="'+id+'"'),'missing input/button '+id);
}
const elements={};
function el(){
 return {
  value:'',style:{},children:[],textContent:'',onclick:null,handlers:{},
  classList:{_set:new Set(),add(c){this._set.add(c)},remove(c){this._set.delete(c)},contains(c){return this._set.has(c)},toggle(c,force){if(typeof force==='boolean'){if(force)this._set.add(c);else this._set.delete(c);return force}if(this._set.has(c)){this._set.delete(c);return false}this._set.add(c);return true}},
  addEventListener(name,fn){this.handlers[name]=fn},
  appendChild(child){this.children.push(child)},
  replaceChildren(){this.children=[]},
  setAttribute(){},
  get length(){return this.children.length}
 };
}
function get(id){return elements[id]||(elements[id]=el());}
const messages=[],timeouts=[],intervals=[];
const calls={suggest:[],route:[],routeVia:[],location:0,stop:0,selectObd:0,obdSettings:0,socChoice:[],disconnectObd:0,visible:[],saved:[],orientation:[],chosen:[],filtered:[]};
const drawnMarkers=[],drawnLines=[];
const mapActions={pans:[],events:{},tileEvents:{}};
const L={
 map(){return {setView(){return this},getZoom(){return 7},
  on(event,handler){mapActions.events[event]=handler;return this},removeLayer(){},
  fitBounds(bounds,opts){globalThis.lastRouteBoundsOptions=opts},
  getSize(){return {x:390,y:780}},
  getBounds(){return {getSouth(){return 36},getWest(){return 30},
     getNorth(){return 40},getEast(){return 35}}},
  invalidateSize(){return this},
  zoomIn(){},zoomOut(){},panTo(coords,opts){mapActions.pans.push({coords,opts})},closePopup(){}}},
 tileLayer(){return {addTo(){return this},
   on(name,callback){mapActions.tileEvents[name]=callback;return this}}},
 circleMarker(){return {popup:null,
  addTo(){drawnMarkers.push(this);return this},
  bindPopup(content){this.popup=content;return this},setLatLng(){}}},
 divIcon(options){return options},
 marker(coords,options){return {coords,options,popup:null,events:{},
  on(name,handler){this.events[name]=handler;return this},
  addTo(){drawnMarkers.push(this);return this},
  bindPopup(content){this.popup=content;return this},
  setLatLng(next){this.coords=next}}},
 polyline(coords,options){return {coords,options,handlers:{},
    addTo(){drawnLines.push(this);return this},
    on(name,fn){this.handlers[name]=fn;return this},
    bringToFront(){return this},getBounds(){return []}}}
};
const win={
 EVCOCKPIT_OFFLINE_COUNTRIES:{},
 EvCockpitBaseMap:{init(map,callback){mapActions.baseStatus=callback},setDay(){},retry(){}},
 MediaEngineNative:{
  suggest(field,q,id){calls.suggest.push({field,q,id})},
  route(...args){calls.route.push(args)},
  routeVia(...args){calls.routeVia.push(args)},
  location(){calls.location++},stop(){calls.stop++},
  voice(){},back(){calls.back=(calls.back||0)+1},openMedia(){calls.media=(calls.media||0)+1},stations(){},sensors(){},selectObd(){calls.selectObd++},obdSettings(){calls.obdSettings++},setScenarioSoc(pct){calls.socChoice.push(pct)},disconnectObd(){calls.disconnectObd++},
  visibleStations(...args){calls.visible.push(args)},
  visibleStationsFiltered(...args){calls.filtered.push(args)},
  saveStation(...args){calls.saved.push(args)},
  configureTomTom(){calls.tomtomKey=(calls.tomtomKey||0)+1},
  tripJournal(){calls.journal=(calls.journal||0)+1},
  roadSafety(...args){calls.safety=(calls.safety||[]);calls.safety.push(args)},
  safetyAnnounce(...args){calls.safetySpeech=(calls.safetySpeech||[]);calls.safetySpeech.push(args)},
  landscape(...args){calls.orientation.push(args)},
  chooseAlternative(index){calls.chosen.push(index)}
 }
};
const context={
 window:Object.assign(win,{innerWidth:390}),document:{
  getElementById:get,createElement:el,body:{classList:el().classList},
  querySelector(){return get('sheet')}
 },
 L,setTimeout(fn){timeouts.push(fn);return timeouts.length},
 setInterval(fn){intervals.push(fn);return intervals.length},
 clearTimeout(){},console,Number,Array,Object,String,Math,
 };
const wCards=get('weather-cards');
const compactWeather=get('compact-weather');
for(let i=0;i<4;i++){
 const row=el();const title=el();const desc=el();
 row.children=[title,desc];wCards.children.push(row);
 compactWeather.children.push(el());
}
vm.createContext(context);vm.runInContext(inline,context,{timeout:2000});
function typing(field,query){
 get(field).value=query;get(field).handlers.input();
 while(timeouts.length)timeouts.shift()();
 return calls.suggest[calls.suggest.length-1];
}
// Weather, traffic and fixed cameras have NO driver-facing toggles.
for(const id of ['dock-traffic','dock-safety','layer-weather','layer-traffic','layer-safety']){
 assert(new RegExp('id="'+id+'"[^>]*hidden|hidden[^>]*id="'+id+'"').test(html),
  'legacy '+id+' toggle must be hidden from drivers');
}
assert(html.includes('id="route-weather-shortcut" class="header-weather-button" role="status"'),
 'weather is a read-only status, not a button');
assert(html.includes('safetyActive=true;') &&
       html.includes('pinPositions.indexOf(weatherIndex)!==-1') &&
       html.includes('(w.available===true||w.nowAvailable===true)'),
 'route must enable source-backed weather markers with an overlap-safe 3-pin cap');
assert(html.includes("if(traffic.configured().tomtom&&traffic.getActive()!=='tomtom')"),
 'configured live traffic must activate automatically on route');

// Combined map and vehicle HUD: measured values only, with explicit OBD pairing.
assert(html.includes('id="vehicle-cockpit"'),'landscape cockpit not on real map');
assert(html.includes('id="cockpit-tripbar"'),'image-matched bottom route HUD missing');
assert(html.includes('id="co-trip-track"')&&
       html.includes("classList.add('gps-confirmed')")&&
       html.includes("classList.remove('gps-confirmed')")&&
       html.includes('Math.min(92,Math.max(0,pct))'),
 'route car icon must only follow fresh on-route GPS and stay inside track');
assert(!html.includes("content:'EV Cockpit'"),
 'legacy vehicle brand must not appear in rendered CSS');
assert(html.includes('class="vw-symbol"'),'reference header VW artwork missing');
assert(html.includes('id="header-clock"'),'actual local clock missing');
assert(html.includes('id="header-temp"'),'actual modeled temperature slot missing');
assert(html.includes('id="co-maneuver-distance"'),'right turn indicator missing');
assert(html.includes('id="dock-apps"')&&html.includes('id="dock-music"'),
 'bottom bar must preserve real MediaEngine entrypoints');
assert(html.includes('.cockpit-right{display:block!important}')&&
       html.includes('.cockpit-tripbar{display:grid!important}'),
 'right and lower dashboard may not disappear before route is entered');
assert(html.includes('id="dock-traffic-label"')&&html.includes('id="dock-obd-label"'),
 'status updates must change text without removing original dock icons');
assert(!html.includes('id="fake-soc"')&&!html.includes('68% </div>'),
 'do not insert demonstration numbers as live ECU readings');
for(const id of ['dock-home','dock-route','dock-stations','dock-traffic','dock-obd','dock-journal','dock-safety']){
 assert(html.includes('id="'+id+'"'),'reference-style dock missing '+id);
}
assert(html.includes('left:39%')&&html.includes('grid-template-columns:28%'),
 'wide cockpit left instruments and bottom route layout missing');
assert(html.includes('id="layer-safety"')&&html.includes('id="safety-speed"'),
 'OSM camera UI and GPS speed indicator missing');
get('dock-safety').onclick();
assert(get('safety-status').textContent.includes('yakınlaştır'),
 'OSM queries must be prevented at zoom 7');
assert.equal((calls.safety||[]).length,0,'must not query wide camera bounding box');
assert(html.includes('updateSafetyGps(x)'),'GPS speed must bind to native position updates');
context.window.mediaengineSafetyCameras({source:'© OpenStreetMap contributors (ODbL)',
 items:[{id:112233,lat:38.124,lon:34.77,kind:'fixed'}]});
assert(get('safety-status').textContent.includes('1 sabit kamera'));
get('layer-safety-voice').onclick();
assert(get('layer-safety-voice').textContent.includes('açık'));
get('dock-safety').onclick();
assert(get('safety-status').textContent.includes('kapalı'));
get('dock-journal').onclick();
assert.equal(calls.journal,1,'journal button reaches Android');
get('co-media').onclick();
assert.equal(calls.media,1,'top MediaEngine tab must dispatch to safe media launcher');
assert.equal(calls.back||0,0,'media handoff must not destroy navigator Activity');
get('dock-route').onclick();
assert(get('sheet').classList.contains('search-open'),'dock route must open search');
get('dock-route').onclick();
assert(!get('sheet').classList.contains('search-open'),'dock route must close search');
assert(html.includes('id="traffic-tomtom"'),'TomTom selector missing');
assert(html.includes('id="traffic-tomtom-key"'),'TomTom API settings button missing');
assert(html.includes('attachLeaflet(map)'),'TomTom must bind native Leaflet map');
assert(html.includes('id="layer-all"')&&html.includes('id="layer-ccs"')&&
 html.includes('id="layer-route"')&&html.includes('id="layer-traffic"'),
 'selectable full Turkey, route chargers and honest traffic mode missing');
assert(html.includes('id="journey-points"'),
 'full route weather overlay should show the whole drive');
get('layer-all').onclick();
assert.equal(calls.filtered.length,1,
 'the all stations layer must request the visible EPDK area');
assert.equal(calls.filtered[0][4],false,'all stations must include AC sockets');
get('layer-ccs').onclick();
assert.equal(calls.filtered[1][4],true,'CCS selector must filter out AC-only stations');
win.mediaengineStations({kind:'visible',items:[
 {cluster:true,lat:38,lon:34,count:800,ccsCount:280}
 ],total:800,clustered:true,updatedAt:'2026-10-22T11:00:00Z'});
assert(get('layer-status').textContent.includes('küme'),
 'nationwide cluster must be clearly distinguished from individual stations');
const cluster=drawnMarkers[drawnMarkers.length-1];
assert(typeof cluster.events.click==='function',
 'station grouping must zoom to show constituent station pins');
cluster.events.click();
assert(get('layer-status').textContent.includes('küme'),
 'zooming must not fabricate extra station features');
get('traffic-tomtom-key').onclick();
assert.equal(calls.tomtomKey,1,'TomTom settings must open Android credential dialog');
get('dock-traffic').onclick();
assert(get('traffic-choices').classList.contains('open'),
 'dock must expose traffic options');
get('traffic-choices').classList.remove('open');
get('layer-traffic').onclick();
assert(get('traffic-choices').classList.contains('open'),
 'traffic selector must expose official licensed provider choices');
assert(get('traffic-help').textContent.includes('API anahtarı gerekli'),
 'without credentials the user must see the real provider limitation');
get('traffic-tomtom').onclick();
assert(!html.includes('id="traffic-google"')&&!html.includes('id="traffic-yandex"'),
 'Google/Yandex unavailable UI removed');
get('traffic-osm').onclick();
assert(get('layer-status').textContent.includes('korunuyor'),
 'missing provider must preserve the working map instead of faking traffic');
get('layer-route').onclick();

assert(html.includes('id="route-map-alts"'),'all-route map chooser absent');
assert(html.includes('allBounds.extend(alt.getBounds())'),
 'map bounds must include all genuine alternative routes');
assert(html.includes("drawRoutePreviews(genuinePreviews,chosenRouteIndex)"),
 'OSRM geometry alternatives not drawn on map');
assert(html.includes('id="co-alt-toggle"')&&html.includes('id="co-alts-list"'),
 'charge-aware route chooser must be available without hiding the map');
assert(html.includes('id="co-cost"'),'auto-cost summary must appear on route bar');
assert(html.includes("className:'charge-map-pin'"),
 'real coordinates must use visible CCS lightning icons');
assert(html.includes("className:'vehicle-map-pin'"),
 'GPS position needs its own visible map marker');
assert(html.includes('.sheet.route-active:not(.details-open){display:none!important}'),
 'old legacy route sheet still covers landscape map');
assert(html.includes('id="co-trip-arrival"')&&html.includes('id="co-trip-charge-button"'),
 'trip bar missing transparent charge and model arrival elements');
assert(html.includes('id="map-health"')&&html.includes('id="map-retry"')&&
       html.includes('id="layer-map-retry"'),
 'blocked public tile servers require user-visible retry');
assert(html.includes('evcockpit-basemap.js')&&html.includes('evcockpit-offline-geography.js'),
 'offline geographic fallback must be packaged and loaded before navigation');
assert(!html.includes('class="dock-climate"')&&!html.includes('>KLİMA<'),
 'unsupported climate controls must be removed, not merely disabled');
assert(html.includes('id="map-credit"'),
 'OpenStreetMap attribution must be visible and not hidden by route sheet');
assert.equal(typeof mapActions.baseStatus,'function');
mapActions.baseStatus({status:'offline',online:false,detail:'Harita yüklenemedi'});
assert(get('map-health-text').textContent.includes('yüklenemedi'));
assert(get('map-credit').textContent.includes('sokak/yol haritası değildir'));
mapActions.baseStatus({status:'online',online:true,source:'OpenFreeMap',detail:'Vektör harita'});
assert(get('map-credit').textContent.includes('OpenFreeMap'));
assert(!get('map-health').classList.contains('is-offline'));
assert(html.includes('id="co-soc"')&&html.includes('id="co-voltage"')&&
 html.includes('id="co-energy"'),'transparent cockpit missing core measurements');
assert(html.includes('backdrop-filter:blur'),'cockpit overlays must be translucent');
assert(html.includes('text-shadow:-1px -1px 0 #000'),
 'large telemetry numbers must have high contrast black outline');
assert(html.includes('@media (orientation:landscape)'),
 'full-screen cockpit must be landscape responsive');
assert(html.includes('.sheet:not(.route-active):not(.search-open){display:none!important}'),
 'legacy sidebar must not obscure the map in landscape');
assert(html.includes('id="go-fullscreen"'),'portrait to landscape button missing');
assert(html.includes('id="co-focus"')&&html.includes('id="co-fit-route"'),
 'driving focus and route fit controls must exist');
get('co-focus').onclick();
assert(context.document.body.classList.contains('focus-drive'),
 'map-focused view must respond to a deliberate tap');
assert(get('co-focus').textContent.includes('Tüm göstergeler'),
 'user must be able to restore all vehicle measurements');
get('co-focus').onclick();
assert(!context.document.body.classList.contains('focus-drive'),
 'normal view must restore the full telemetry panel');
get('co-fit-route').onclick();
assert(get('status').textContent.includes('Önce rota'),
 'route fit must refuse to claim a non-existent route');
assert(html.includes('id="co-quick-route"')&&html.includes('id="co-quick-layers"'),
 'reference-style landscape route and layer shortcuts missing');
get('map-layers').classList.remove('open'); // reset menu opened by deliberate dock test
assert(!get('map-layers').classList.contains('open'),
 'all map filters must start collapsed to preserve the full-screen cockpit');
get('co-quick-layers').onclick();
assert(get('map-layers').classList.contains('open'),
 'map filter menu should open only on explicit tap');
get('layer-daynight').onclick();
assert.equal(get('layer-daynight').textContent,'◐ Gece haritası',
 'day/night option must work within map layer menu');
get('layer-daynight').onclick();
assert.equal(get('layer-daynight').textContent,'◐ Gündüz haritası',
 'day/night option should be reversible');
get('layer-close').onclick();
assert(!get('map-layers').classList.contains('open'),
 'single tap must restore unobstructed route map');
assert(html.includes('id="open-layers"'),
 'portrait phone must have a way to open charging and weather layers');
get('open-layers').onclick();
assert(get('map-layers').classList.contains('open'),
 'portrait layer shortcut did not open charging and weather filters');
get('layer-close').onclick();
get('go-fullscreen').onclick();
assert.equal(calls.orientation.length,1,'cannot request landscape from portrait');
assert.equal(calls.orientation[0][0],true,'landscape request must be explicit');
get('co-expand').onclick();
assert.equal(calls.orientation[1][0],false,'must be able to return to auto orientation');
get('co-route-tab').onclick();
assert(get('sheet').classList.contains('search-open'),
 'route search should be opt-in, not obscure initial cockpit');
get('co-route-tab').onclick();
assert(!get('sheet').classList.contains('search-open'),'route form should close');
get('open-route').onclick();
assert(get('sheet').classList.contains('search-open'),'route search shortcut broken');
get('open-route').onclick();
assert(html.includes('id="live-soc"'),'live battery HUD missing');
assert(html.includes('id="live-voltage"'),'live HV voltage HUD missing');
assert(html.includes('id="live-temp"'),'live battery temperature HUD missing');
get('connect-obd').onclick();
assert.equal(calls.selectObd,1,'OBD selection must be a direct user action');
win.mediaengineTelemetry({fresh:true,soc:'64 %',voltage:'370 V',temperature:'23 °C',
 chargeMode:'Ready',status:'ELM327 bağlı'});
assert.equal(get('live-soc').textContent,'64 %');
assert.equal(get('co-trip-battery').textContent,'OBD SoC: 64 %',
 'trip bar should show fresh measured battery SoC only');
assert.equal(get('live-voltage').textContent,'370 V');
assert.equal(get('live-temp').textContent,'23 °C');
assert.equal(get('vehicle-soc').textContent,'64 %');
assert.equal(get('co-soc').textContent,'64 %','full-screen SoC does not use real packet');
assert(get('co-sensor-age').textContent.includes('ölçüm yeni'),
 'measured SoC must show verified freshness status');
assert.equal(get('co-soc-fill').style.width,'64%','battery progress is not linked to BMS SoC');
assert.equal(get('co-voltage').textContent,'370 V');
assert.equal(get('co-temp').textContent,'23 °C');
assert.equal(get('co-mode').textContent,'Ready');
assert.equal(get('co-range').textContent,'106–159 km',
 'only verified SoC may generate clearly-labelled model range');
assert(html.includes('TAHMİNİ MENZİL · %15 REZERV'),
 'the model-range HUD label must disclose modelling and reserve assumption');
assert.equal(get('co-power').textContent,'— kW','unverified kW must stay blank');
get('co-connect').onclick();
assert.equal(calls.obdSettings,1,'cockpit OBD settings must open without Activity navigation');
get('dock-obd').onclick();
assert.equal(calls.obdSettings,2,'OBD yok should open in-place settings');
get('layer-obd-settings').onclick();
assert.equal(calls.obdSettings,3,'OBD connection settings must be in tools');
get('co-disconnect').onclick();
assert.equal(calls.disconnectObd,1,
 'user must be able to release the ELM327 adapter for Car Scanner');
assert(html.includes('Car Scanner kapalıyken ELM327 bağla'),
 'OBD help must explain Car Scanner cannot share the same Bluetooth SPP connection');
assert(html.includes('-webkit-text-stroke:0!important'),
 'black thick outlines must be disabled to prevent double-bordered text');
assert(html.includes('font-size:clamp(15px,1.5vw,23px)'),
 'right side ETA and destination should use smaller readable type');
get('co-station-tab').onclick();
assert.equal(calls.filtered.length,3,
 'cockpit charger tab must query all currently visible EPDK stations');
calls.filtered.length=0; // isolate initial all/CCS map checks
win.mediaengineTelemetry({fresh:false,status:'OBD bağlı değil'});
assert.equal(get('live-soc').textContent,'—','stale SoC was not cleared');
assert.equal(get('vehicle-soc').textContent,'—','stale vehicle SoC was not cleared');
assert.equal(get('co-soc').textContent,'— %','cockpit must clear stale SoC');
assert(get('co-sensor-age').textContent.includes('güncel değil'),
 'missing SoC must not claim a fresh measured value');
assert.equal(get('co-trip-battery').textContent,'OBD SoC: —',
 'trip bar should clear stale measured battery');
assert.equal(get('co-trip-arrival').textContent,'—',
 'stale OBD should withdraw SoC arrival model');
assert.equal(get('co-range').textContent,'— km','model range must disappear with stale SoC');
assert(html.includes('id="follow-map"'),'GPS follow button is missing');
get('follow-map').onclick();
assert.equal(calls.location,1,'GPS follow must request location only on explicit click');
assert.equal(get('follow-map').title,'GPS takibini kapat');
win.mediaenginePosition({lat:37.9667,lon:34.6833,accuracy:8,timeMs:Date.now()});
assert.equal(mapActions.pans.length,1,'valid GPS must move followed map');
assert.equal(mapActions.pans[0].coords[0],37.9667);
const oldPanCount=mapActions.pans.length;
win.mediaenginePosition({lat:37.5,lon:34.1,accuracy:8,timeMs:Date.now()-120000});
assert.equal(mapActions.pans.length,oldPanCount,'old GPS must never drag map');
mapActions.events.dragstart();
win.mediaenginePosition({lat:37.8,lon:34.0,accuracy:8,timeMs:Date.now()});
assert.equal(mapActions.pans.length,oldPanCount,'user map drag should suspend following');
get('follow-map').onclick();
assert.equal(mapActions.pans.length,oldPanCount+1,'follow button must re-center on last good fix');
get('follow-map').onclick();
calls.location=0; // prior request was explicit; subsequent search/plan must not trigger GPS
assert.equal(get('co-voltage').textContent,'— V','cockpit must clear stale voltage');
assert.equal(get('co-soc-fill').style.width,'0%','battery bar must clear when stale');
// Offline fallback works even if Photon returns errors.
const first=typing('from','Niğde');
assert.equal(first.field,'from');assert.equal(first.q,'Niğde');
assert(get('from-list').children.length>=1,'offline Niğde result must show immediately');
assert(get('from-list').children[0].textContent.includes('Niğde'),'offline Niğde label missing');
win.mediaengineSuggestions({field:'from',requestId:first.id,items:[],
 error:'External server unavailable'});
assert(get('from-list').children.length>=1,'server outage hid offline city');
get('from-list').children[0].onclick();
const next=typing('to','Antalya');
assert.equal(next.field,'to');assert.equal(next.q,'Antalya');
assert(get('to-list').children.length>=1,'offline Antalya result must show immediately');
const before=get('to-list').children.length;
win.mediaengineSuggestions({field:'to',requestId:next.id-1,
 items:[{label:'Wrong stale match',lat:36.9,lon:30.7}]});
assert.equal(get('to-list').children.length,before,'outdated response replaced offline result');
win.mediaengineSuggestions({field:'to',requestId:next.id,items:[
 {label:'Antalya Eğitim ve Araştırma Hastanesi',lat:36.893,lon:30.68},
 {label:'Antalya, Türkiye',lat:36.895,lon:30.7}
]});
const suggestions=get('to-list').children;
assert(suggestions.length>=3,'remote location results not merged with offline city');
const hospital=suggestions.find(x=>x.textContent.includes('Eğitim ve Araştırma'));
assert(hospital,'remote detailed Antalya hospital not listed');
hospital.onclick();
get('plan').onclick();
assert.equal(calls.route.length,1,'manual route never called');
assert(Math.abs(calls.route[0][0]-37.9667)<0.002,'Niğde source coordinate wrong');
assert(Math.abs(calls.route[0][1]-34.6833)<0.002,'Niğde longitude wrong');
assert.equal(calls.route[0][2],36.893);
assert.equal(calls.route[0][3],30.68);
assert.equal(calls.route[0][4],false,'manual route must not demand GPS');
assert.equal(calls.location,0,'GPS triggered without user choice');
get('swap').onclick();get('plan').onclick();
assert.equal(calls.route[1][0],36.893);
assert(Math.abs(calls.route[1][2]-37.9667)<0.002);
const accent=typing('from','Nigde');
assert(get('from-list').children.some(x=>x.textContent.includes('Niğde')),
 'accent-insensitive matching must work');
get('from-list').children[0].onclick();
const finalCity=typing('to','Antalya');
assert(finalCity.field==='to');
get('to-list').children[0].onclick();
assert(get('from').value.includes('Niğde'));
assert(get('to').value.includes('Antalya'));
// Weather cards update only with an actual provider result.
win.mediaengineWeather({source:'Open-Meteo',cards:[
 {minutes:1,temperature:12,precipitation:0.2,wind:11,resolution:'15dk model'},
 {minutes:5,temperature:11,precipitation:0.2,wind:13,resolution:'15dk model'},
 {minutes:10,temperature:10,precipitation:0.3,wind:14,resolution:'15dk model'},
 {minutes:15,temperature:9,precipitation:0.4,wind:15,resolution:'15dk model'}
]});
win.mediaengineJourneyWeather({cards:[
 {percent:0,etaMinutes:0,latitude:37.97,longitude:34.68,
  available:true,temperature:14,rainProbability:10,wind:8,weatherCode:1},
 {percent:25,etaMinutes:80,latitude:37.72,longitude:33.5,
  available:true,temperature:11,rainProbability:40,wind:16,weatherCode:51},
 {percent:50,etaMinutes:160,latitude:37.3,longitude:32.2,
  available:true,temperature:9,rainProbability:30,wind:10,weatherCode:3},
 {percent:75,etaMinutes:240,latitude:36.9,longitude:31.2,
  available:false},
 {percent:100,etaMinutes:320,latitude:36.9,longitude:30.7,
  available:true,temperature:20,rainProbability:5,wind:11,weatherCode:0}
]});
assert.equal(get('journey-points').children.length,5,
 'whole-journey forecast must show five source-backed segments');
assert(get('journey-points').children[1].children[1].textContent.includes('yağış %40'),
 'precipitation probability should come from Open-Meteo model');
assert(get('journey-points').children[3].children[1].textContent.includes('Tahmin yok'),
 'unknown forecast must never be fabricated');
get('layer-weather').onclick();
assert(!get('journey-weather').classList.contains('open'),
 'weather must never show a separate route modal');
assert(get('weather-cards').children[0].children[1].textContent.includes('12°C'),
  'weather data did not update actual card');
assert(get('compact-weather').children[0].textContent.includes('12°C'),
  'compact weather did not update');
assert(get('weather-source').textContent.includes('model'),
  'model forecast not labeled');
assert(get('co-weather').textContent.includes('4 varış tahmini'),
 'source-backed route forecast should appear as on-map weather summary');
// Real route callback must update visible polyline, not merely route inputs.
// The older GPS fix from the follow-map test is now stale: route cannot invent progress.
win.mediaenginePosition({lat:37.8,lon:34.0,accuracy:8,timeMs:Date.now()-120000});
win.mediaengineRoute({
 coordinates:[[34.6833,37.9667],[33.8,37.4],[30.7056,36.8841]],
 distanceKm:510,durationMin:385,alternatives:2,provider:'OSRM test',
 energyLowKwh:81.6,energyHighKwh:122.4,chargingNeededByModel:true,
 navigationActive:false,steps:['Devam et','Sola dön']
});
assert(get('trip').style.display==='block','route summary not visible');
assert(get('compact-trip').textContent.includes('510 km'),'compact route summary missing');
assert.equal(get('co-route-km').textContent,'510 km','cockpit route distance not updated');
globalThis.lastRouteBoundsOptions=null;
get('co-fit-route').onclick();
assert(globalThis.lastRouteBoundsOptions && globalThis.lastRouteBoundsOptions.maxZoom===15,
 'full-route button must reframe the actual OSRM route');
assert(get('co-trip-kilometers').textContent.includes('510 km'),'bottom route bar distance missing');
win.mediaengineJourneyWeather({cards:[
 {percent:0,latitude:37.97,longitude:34.68,available:true,
  temperature:14,rainProbability:10,wind:8,weatherCode:1},
 {percent:25,latitude:37.72,longitude:33.5,available:true,
  temperature:11,rainProbability:40,wind:16,weatherCode:51},
 {percent:50,latitude:37.3,longitude:32.2,available:true,
  temperature:9,rainProbability:30,wind:10,weatherCode:3},
 {percent:75,latitude:36.9,longitude:31.2,available:false},
 {percent:100,latitude:36.9,longitude:30.7,available:true,
  temperature:20,rainProbability:5,wind:11,weatherCode:0}
]});
const beforeWeatherPins=drawnMarkers.length;
assert.equal(beforeWeatherPins>0,true,
 'weather map badges must appear automatically with a genuine route');
assert.equal(get('journey-weather').classList.contains('open'),false,
 'a separate weather panel must not appear');
assert.equal(get('route-weather-map-status').hidden,false,
 'weather map overlay must show model freshness and counts');
assert(get('route-weather-map-status').textContent.includes('otomatik yenileme'),
 'driver must see refresh cadence and distinguish model from live sensors');
get('layer-weather').onclick();
assert.equal(drawnMarkers.length,beforeWeatherPins,
 'hidden legacy weather button must not redraw markers or open a modal');
assert.equal(get('journey-weather').classList.contains('open'),false,
 'no modal is accessible via legacy layer handler');
win.mediaengineJourneyWeather({source:'Open-Meteo',updatedAt:Date.now(),cards:[
  {percent:0,latitude:37.97,longitude:34.68,nowAvailable:true,
   nowTime:Math.floor(Date.now()/1000),nowTemperature:16,
   nowPrecipitation:0.2,nowWind:12,nowWeatherCode:1,
   available:true,temperature:15,rainProbability:23,wind:11,weatherCode:0},
  {percent:50,latitude:37.3,longitude:32.2,nowAvailable:true,
   nowTime:Math.floor(Date.now()/1000),nowTemperature:8,
   nowPrecipitation:1.3,nowWind:22,nowWeatherCode:61,
   available:true,temperature:10,rainProbability:82,wind:25,weatherCode:61,
   risks:['Yüksek yağış olasılığı']},
  {percent:100,latitude:36.9,longitude:30.7,nowAvailable:true,
   nowTime:Math.floor(Date.now()/1000),nowTemperature:20,
   nowPrecipitation:0,nowWind:8,nowWeatherCode:0,
   available:true,temperature:21,rainProbability:4,wind:7,weatherCode:0}
]});
assert.equal(drawnMarkers.length,beforeWeatherPins+3,
 'a new source-backed weather fetch must update route badge markers automatically');
assert(get('route-weather-map-status').classList.contains('risk') &&
 get('route-weather-map-status').textContent.includes('risk noktası'),
 'weather risk summary must be visible directly on the map');
assert(drawnMarkers[drawnMarkers.length-2].popup.textContent.includes('Şimdi') &&
 drawnMarkers[drawnMarkers.length-2].popup.textContent.includes('Varış'),
 'each weather map popup must distinguish current model from ETA forecast');
assert(get('vehicle-cockpit').classList.contains('has-route'),'trip bar not enabled for route');
assert.equal(get('co-trip-arrival').textContent,'—',
 'must not fabricate arrival SoC without current BMS measurement');
assert(get('co-eta').textContent!=='—','real route must derive a traffic-free local ETA');
win.mediaengineAlternatives({selectedIndex:0,fastestIndex:0,shortestIndex:1,
 chargeAwareIndex:1,options:[
 {index:0,km:510,minutes:385,energyLowKwh:81.6,energyHighKwh:122.4,
  stationsKnown:true,ccsCount:22,socKnown:true,chargeNeededModel:true,
  chargeLowKwh:55,chargeHighKwh:96},
 {index:1,km:520,minutes:400,energyLowKwh:83.2,energyHighKwh:124.8,
  stationsKnown:true,ccsCount:35,reachableCcsModel:true,
  socKnown:true,chargeNeededModel:true,chargeLowKwh:61,
  chargeHighKwh:98,costMinTry:792,costMaxTry:1616,
  tariffOperator:'ZES',tariffChecked:'2026-10-22'}
 ]});
assert.equal(get('co-alts-list').children.length,2,
 'real OSRM alternatives should be selectable without manual route entry');
assert(get('co-alts-list').children[1].children[0].children[0].textContent.includes('CCS önerili'),
 'station-aware route must be labeled by model rather than distance alone');
assert(get('co-cost').textContent.includes('55–96 kWh'),
 'unknown operator must show charge energy, not invent a TL rate');
get('co-alt-toggle').onclick();
assert(get('co-alts').classList.contains('open'),
 'route chooser should open from the compact trip bar');
get('co-alts-list').children[1].onclick();
assert.equal(calls.chosen[0],1,
 'choosing alternative must use the cached native OSRM route index');
assert(!get('co-alts').classList.contains('open'),
 'after selection, the map needs to be visible');
win.mediaengineAlternatives({selectedIndex:1,fastestIndex:0,shortestIndex:1,
 chargeAwareIndex:1,options:[
 {index:0,km:510,minutes:385,energyLowKwh:81.6,energyHighKwh:122.4,
  socKnown:true,chargeNeededModel:true,chargeLowKwh:55,chargeHighKwh:96},
 {index:1,km:520,minutes:400,energyLowKwh:83.2,energyHighKwh:124.8,
  stationsKnown:true,ccsCount:35,socKnown:true,chargeNeededModel:true,
  costMinTry:792,costMaxTry:1616,tariffOperator:'ZES',
  tariffChecked:'2026-10-22'}]});
assert(get('co-cost').textContent.includes('792–1.616 TL'),
 'automatic estimate must use known verified network price range');
assert(get('co-cost-note').textContent.includes('soket'),
 'cost summary must explain it is not a live confirmed bill');
win.mediaengineAlternatives({selectedIndex:0,fastestIndex:0,shortestIndex:0,
 chargeAwareIndex:-1,options:[{
 index:0,km:525,minutes:405,energyLowKwh:84,energyHighKwh:126,
 stationsKnown:false,ccsCount:0,socKnown:false,socAssumed:true,
 chargeNeededModel:true,chargeStopsMin:2,chargeStopsMax:3,
 chargeLowKwh:50.2,chargeHighKwh:92.2,
 costMinTry:386.5,costMaxTry:1520.38,
 priceType:'market-scenario',priceBasis:'Türkiye pazar senaryosu',
 tariffChecked:'2026-10-16'
}]});
assert(get('co-alts-list').children[0].children[0].children[1].textContent.includes('2–3 DC mediaengine'),
 'model stop count must be visible even without BMS SoC');
assert(get('co-cost').textContent.includes('2–3 DC mediaengine')&&
 get('co-cost').textContent.includes('387–1.520 TL'),
 'model-priced charge count and TRY range must replace Fiyat yok');
assert(get('co-cost-note').textContent.includes('%80 çıkış senaryosu'),
 'unknown BMS SoC must never be represented as a measured state');
win.mediaengineSocScenario({measured:false,percent:80,userSelected:false});
assert(get('soc-scenario-choice').hidden===false,
 'when measured SoC is missing the 80/100 choice is visible');
get('soc-choice-100').onclick();
assert.equal(calls.socChoice[calls.socChoice.length-1],100,
 'driver-chosen 100 must trigger native recalculation');
win.mediaengineSocScenario({measured:false,percent:100,userSelected:true});
assert(get('soc-choice-100').classList.contains('selected'),
 'selected 100 percent scenario is reflected by UI');
win.mediaengineAlternatives({selectedIndex:0,fastestIndex:0,shortestIndex:0,
 chargeAwareIndex:-1,options:[{
 index:0,km:525,minutes:405,energyLowKwh:84,energyHighKwh:126,
 stationsKnown:false,ccsCount:0,socKnown:false,socAssumed:true,
 scenarioStartSoc:100,chargeNeededModel:true,chargeStopsMin:1,chargeStopsMax:3,
 chargeLowKwh:40,chargeHighKwh:86,costMinTry:300,costMaxTry:1400
}]});
assert(get('co-cost-note').textContent.includes('%100 çıkış senaryosu'),
 'UI must describe 100% as a user scenario and not a vehicle reading');
win.mediaengineSocScenario({measured:true,percent:74.5,userSelected:true});
assert(get('soc-scenario-choice').hidden===true,
 'when fresh BMS SoC arrives the fallback selection is hidden');


assert.equal(get('co-maneuver').textContent,'Devam et',
 'cockpit first maneuver must be drawn from OSRM instructions');
win.mediaengineManeuver({instruction:'Sağa dön',straightLineMeters:420,
 fixTimeMs:Date.now(),accuracy:12});
assert(get('co-maneuver').textContent.includes('Sağa dön · yaklaşık 420 m'),
 'real GPS maneuver proximity must update HUD');
assert(get('co-maneuver').textContent.includes('kuş uçuşu'),
 'straight-line maneuver proximity must not claim road distance');
win.mediaengineManeuver({instruction:'Eski talimat',straightLineMeters:30,
 fixTimeMs:Date.now()-80000,accuracy:12});
assert(!get('co-maneuver').textContent.includes('Eski talimat'),
 'old GPS fix must not overwrite current navigation guidance');
win.mediaengineManeuver({instruction:'Yanlış konum',straightLineMeters:30,
 fixTimeMs:Date.now(),accuracy:170});
assert(!get('co-maneuver').textContent.includes('Yanlış konum'),
 'inaccurate GPS fix must never render as a real maneuver');
assert(get('route-progress-text').textContent.includes('GPS rota ilerlemesi yok'),
 'do not simulate trip progress without a GPS observation');
win.mediaenginePosition({lat:37.4,lon:33.8,accuracy:8,timeMs:Date.now()});
assert(get('route-progress-text').textContent.includes('GPS yaklaşık %'),
 'GPS map route progression not displayed');
assert(get('co-trip-progress-label').textContent.includes('GPS yaklaşık %'),
 'new trip bar must use only verified GPS');
assert(parseFloat(get('co-trip-fill').style.width)>0,
 'trip progress bar must advance with real GPS fix');
assert(parseFloat(get('route-progress-fill').style.width)>0,
 'real GPS route progress bar did not update');
win.mediaenginePosition({lat:37.4,lon:33.8,accuracy:8,timeMs:Date.now()-120000});
assert(get('route-progress-text').textContent.includes('GPS rota ilerlemesi yok'),
 'stale GPS location must be rejected');
assert.equal(get('co-trip-fill').style.width,'0%','stale GPS must reset trip progress');
win.mediaenginePosition({lat:40.5,lon:31.7,accuracy:10,timeMs:Date.now()});
assert(get('route-progress-text').textContent.includes('rota dışında'),
 'off-route GPS must not make up distance progression');
assert.equal(get('co-reroute').style.display,'inline-block',
 'the user must be offered an explicit reroute when truly off-route');
assert.equal(get('co-energy').textContent,'81,6–122,4 kWh',
 'cockpit route energy not labeled as model');
assert(get('vehicle-cockpit').classList.contains('has-route'),
 'landscape route progress panel should replace placeholder');
assert(get('trip-energy').textContent.includes('81.6–122.4 kWh'),
 'automatic route energy model not displayed');
assert(get('trip-energy').textContent.includes('şarj'),
 'route model should flag needed charging without fabricated SoC');
assert(get('sheet').classList.contains('route-active'),'route panel did not collapse');
assert(get('map-note').style.display==='none','old map hint still overlays the route');
get('route-details').onclick();
assert(get('sheet').classList.contains('details-open'),'details could not be expanded');
get('route-details').onclick();
assert(!get('sheet').classList.contains('details-open'),'details could not be collapsed');

assert(get('traffic-source').textContent.includes('trafik yoğunluğunu içermez') &&
       !get('traffic-source').textContent.includes('canlı trafik rota süresine dahil'),
  'OSRM test route must disclose that live traffic is excluded from ETA');
assert(get('tripdata').textContent.includes('510 km'),'route distance absent');
assert(globalThis.lastRouteBoundsOptions.paddingBottomRight[1]>=200,
  'route viewport hides map beneath info sheet');
win.mediaengineRoute({
 coordinates:[[34.6833,37.9667],[34.7000,37.9800]],
 distanceKm:12,durationMin:15,alternatives:1,provider:'OSRM test',
 energyLowKwh:1.92,energyHighKwh:2.88,
 arrivalSocLower:74.5,arrivalSocUpper:76.0,chargingNeededByModel:false,steps:[]
});
assert(get('trip-energy').textContent.includes('güncel GPS ve OBD'),
 'old planning SoC must not override live-source requirements');
assert.equal(get('co-trip-arrival').textContent,'—',
 'stale or off-route GPS must suppress model arrival percentage');
win.mediaengineRoute({coordinates:[[34.68,37.96],[34.9,37.8],[30.7,36.9]],
 distanceKm:513,durationMin:397,stopIncluded:true,
 energyLowKwh:82,energyHighKwh:123,steps:[]});
assert(get('compact-trip').textContent.includes('1 şarj mediaenginesı'),
 'waypoint route must show charging stop label');
assert(get('co-eta').textContent.includes('şarj hariç'),
 'ETA must not imply unknown charging dwell time is included');
assert(get('trip-energy').textContent.includes('Durakta şarj miktarı bilinmiyor'),
 'charging stop must never invent terminal SoC');
// Same-map charging: request viewport only on user action; never show invented availability.
get('charge-map').onclick();
assert.equal(calls.filtered.length,1,'filtered EPDK viewport bridge never invoked');
assert.equal(calls.filtered[0][0],36);
get('layer-route').onclick();
win.mediaengineStations({kind:'route',items:[
 {lat:37.8,lon:34.9,title:'Deneme CCS',operator:'ŞarjAğı',
  sockets:'CCS 2 · Type 2 1',ccs:true,distanceKm:3.2},
 {lat:37.7,lon:34.7,title:'Deneme AC',operator:'ŞarjB',
  sockets:'CCS 0 · Type 2 2',ccs:false,distanceKm:4.1}
 ],total:2,updatedAt:'2026-10-20T08:00:00Z',
 snapshotAgeHours:48,liveAvailability:false,livePrices:false});
assert(get('station-summary').textContent.includes('2 rota yakınında'),
 'route CCS stations summary missing');
assert(get('station-note').textContent.includes('Soket durumu/fiyat doğrulanmadı'),
 'snapshot incorrectly claims real-time status');
assert(get('station-suggestion').style.display==='none',
 'missing SoC and suggestion must not produce fake charging recommendation');
win.mediaengineStations({kind:'route',items:[],total:1,
  updatedAt:'2026-10-20T08:00:00Z',snapshotAgeHours:48,
  chargeModel:'charge-likely',
  suggested:{lat:37.8,lon:34.9,title:'Deneme CCS',operator:'ŞarjAğı',
    ccs:true,routeProgressKm:75.2},
  liveAvailability:false,livePrices:false});
assert(get('station-suggestion').style.display==='flex',
 'charge suggestion should appear in single-screen route summary');
assert.equal(get('co-trip-charge-button').style.display,'inline-block',
 'CCS recommendation must be available from bottom car HUD');
assert(get('co-trip-charge-km').textContent.includes('75 km'),
 'CCS progress distance must be shown as approximate route model');
assert(get('station-suggestion-label').textContent.toLowerCase().includes('model'),
 'suggestion must explicitly identify model assumptions');
// Switching to nationwide stations does not cancel independent CCS route advice.
get('layer-all').onclick();
win.mediaengineStations({kind:'route',items:[],total:1,
 updatedAt:'2026-10-20T08:00:00Z',snapshotAgeHours:48,
 chargeModel:'charge-likely',
 suggested:{lat:37.8,lon:34.9,title:'Deneme CCS',operator:'ŞarjAğı',
  ccs:true,routeProgressKm:75.2}});
assert.equal(get('co-trip-charge-button').style.display,'inline-block',
 'route charge advice must survive browsing all Turkish stations');
assert(get('station-suggestion').style.display==='flex',
 'non-route layers must not suppress model charging advice');
get('layer-route').onclick();
assert(get('layer-status').textContent.includes('rota yakınında'),
 'returning to route CCS must restore cached station corridor');
const savedBeforeCharge=calls.saved.length;
get('station-suggestion-choose').onclick();
assert.equal(calls.routeVia.length,1,'suggestion must invoke explicit charging waypoint route');
assert.equal(calls.saved.length,savedBeforeCharge+1,'suggested CCS operator should be recorded');
// Arrival suggestion is not authoritative when no fresh SoC is available.
win.mediaengineStations({kind:'route',items:[],total:0,chargeModel:'no-soc',
 updatedAt:'2026-10-20T08:00:00Z',snapshotAgeHours:48});
assert(get('station-suggestion').style.display==='none',
 'no SoC must withdraw suggested charging stop');
assert.equal(get('co-trip-charge-button').style.display,'none',
 'bottom bar charging button must vanish when fresh SoC is missing');

const popupMarker=drawnMarkers.find(x=>x.popup && x.popup.children &&
 x.popup.children.some(c=>c.textContent==='Deneme CCS'));
assert(popupMarker,'station marker popup was not rendered');
assert.equal(popupMarker.popup.children[3].textContent,'Şarj mediaenginesı olarak ekle');
popupMarker.popup.children[3].onclick();
assert.equal(calls.routeVia.length,2,'CCS station should be usable as a waypoint');
assert.equal(calls.routeVia[1][2],37.8,'waypoint latitude wrong');
assert.equal(calls.routeVia[1][3],34.9,'waypoint longitude wrong');
assert(Math.abs(calls.routeVia[1][4]-36.8841)<0.002,
 'original final destination must be kept');
popupMarker.popup.children[2].onclick();
assert.equal(calls.saved.length,3,'suggested and chosen waypoints plus destination must persist operator');
assert.equal(calls.saved[0][1],'ŞarjAğı');
assert.equal(get('to').value,'Deneme CCS','chosen charger not set as destination');
assert.equal(calls.route.length,3,'choosing a station must replan the route automatically');
assert.equal(calls.route[2][2],37.8,'new route target latitude wrong');
assert.equal(calls.route[2][3],34.9,'new route target longitude wrong');
win.mediaengineStationError({detail:'Ağ erişimi yok'});
assert(get('station-summary').textContent.includes('alınamadı'),
 'station failure must be visible');
assert(get('co-destination').textContent.includes('Deneme CCS'),
 'cockpit destination must update when charging station chosen');
get('route-edit').onclick();
assert(!get('vehicle-cockpit').classList.contains('has-route'),
 'route reset must also reset cockpit state');
assert.equal(get('co-route-km').textContent,'— km',
 'route reset must clear out-of-date dashboard distance');
assert.equal(get('co-trip-arrival').textContent,'—',
 'route reset must clear old trip arrival');
assert.equal(get('co-maneuver').textContent,'Rota bekleniyor',
 'old turn instructions must disappear when route is reset');
assert(get('route-progress-text').textContent.includes('GPS rota ilerlemesi yok'),
 'route reset must clear the progress bar');
// Arrival SoC must recompute from the CURRENT fresh BMS SoC and
// matched remaining route, never display stale/static route planning SoC.
win.mediaengineRoute({
 coordinates:[[34.6833,37.9667],[34.6,37.95]],distanceKm:40,
 durationMin:50,energyLowKwh:6.4,energyHighKwh:9.6,
 steps:['İlerleyin'],navigationActive:false
});
win.mediaenginePosition({lat:37.9667,lon:34.6833,accuracy:5,timeMs:Date.now()});
assert.equal(get('co-trip-arrival').textContent,'—',
 'without fresh BMS SoC, route GPS alone must not imply arrival SoC');
win.mediaengineTelemetry({fresh:true,soc:'64 %',voltage:'370 V',temperature:'23 °C',
 chargeMode:'Ready',status:'ELM327 bağlı'});
assert.equal(get('co-trip-arrival').textContent,'%46–%52',
 'fresh BMS + 40km remaining should show 52kWh/16-24kWh model band');
win.mediaengineTelemetry({fresh:true,soc:'70 %',voltage:'370 V',temperature:'23 °C',
 chargeMode:'Ready',status:'ELM327 bağlı'});
assert.equal(get('co-trip-arrival').textContent,'%52–%58',
 'arrival SoC should respond to actual SoC updates');
win.mediaenginePosition({lat:37.9667,lon:34.6833,accuracy:5,timeMs:Date.now()-120000});
assert.equal(get('co-trip-arrival').textContent,'—',
 'stale GPS must hide predicted arrival despite a fresh SoC');
win.mediaenginePosition({lat:37.9667,lon:34.6833,accuracy:5,timeMs:Date.now()});
assert.equal(get('co-trip-arrival').textContent,'%52–%58',
 'arrival model may return after a new accurate GPS fix');
win.mediaengineTelemetry({fresh:false,status:'OBD bağlantısı koptu'});
assert.equal(get('co-trip-arrival').textContent,'—',
 'stale OBD must clear model arrival SoC');
win.mediaengineRoute({
 coordinates:[[34.6833,37.9667],[34.6,37.95]],distanceKm:40,
 durationMin:50,stopIncluded:true,energyLowKwh:6.4,energyHighKwh:9.6,
 steps:['Şarj mediaenginesına devam'],navigationActive:false
});
win.mediaengineTelemetry({fresh:true,soc:'70 %',voltage:'370 V',
 temperature:'23 °C',chargeMode:'Ready',status:'ELM327 bağlı'});
assert.equal(get('co-trip-arrival').textContent,'—',
 'model cannot predict arrival SoC after a charger without charge amount');
assert(get('co-trip-arrival-note').textContent.includes('Durak sonrası'),
 'missing post-charge amount must be explained');

// Explicit, not automatic, user-approved reroute from a recent GPS fix.
win.mediaengineRoute({
 coordinates:[[34.6833,37.9667],[33.8,37.4]],
 distanceKm:145,durationMin:125,steps:['İlerle'],
 energyLowKwh:23.2,energyHighKwh:34.8
});
win.mediaenginePosition({lat:40.5,lon:31.7,accuracy:9,timeMs:Date.now()});
assert.equal(get('co-reroute').style.display,'inline-block',
 'offroute reroute shortcut should only appear with fresh accurate GPS');
const routesBeforeTap=calls.route.length;
get('co-reroute').onclick();
assert.equal(calls.route.length,routesBeforeTap+1,
 'rerouting requires an explicit user action');
assert.equal(calls.route[routesBeforeTap][0],40.5,
 'reroute must use actual recent GPS latitude');
assert.equal(calls.route[routesBeforeTap][4],true,
 'manual GPS reroute should enable verified position guidance');
assert.equal(get('co-reroute').style.display,'none',
 'rerouting must hide the previous-route warning');

assert(html.includes('id="traffic-map-badge"') && html.includes('id="traffic-map-exit"'),
 'licensed map must disclose provider and allow one-tap OSM return');
assert(html.includes('id="traffic-station-panel"'),
 'charging station action panel retains destination and CCS controls');
win.mediaengineTrafficStationClick({lat:NaN,lon:34,title:'Invalid'});
assert(!get('traffic-station-panel').classList.contains('open'),
 'invalid station cannot open map action panel');
win.mediaengineTrafficStationClick({lat:38.25,lon:34.45,title:'AC örnek istasyonu',
 operator:'Deneme',ccs:false});
assert(get('traffic-station-panel').classList.contains('open'),
 'clicking provider station must show its actual details');
assert(get('traffic-station-stop').disabled,
 'AC-only station cannot be selected as a CCS fast-charge waypoint');
get('traffic-station-close').onclick();
const chargeRoutesBefore=calls.routeVia.length;
win.mediaengineTrafficStationClick({lat:38.25,lon:34.45,title:'CCS örnek istasyonu',
 operator:'Deneme',ccs:true});
assert(!get('traffic-station-stop').disabled,
 'validated CCS waypoint should be offered when route exists');
get('traffic-station-stop').onclick();
assert.equal(calls.routeVia.length,chargeRoutesBefore+1,
 'CCS pin selection must route through existing native waypoint bridge');
assert.equal(calls.routeVia[chargeRoutesBefore][2],38.25,
 'selected waypoint must use actual station latitude');
assert(!get('traffic-station-panel').classList.contains('open'),
 'confirmation must close charging popup');

// User-reported white card collision: in landscape the legacy summary is
// hidden until details are requested, preserving right telemetry panel.
assert(html.includes('.sheet.route-active:not(.details-open){display:none!important}'),
 'old duplicate white route panel must not sit above the cockpit');
assert(html.includes('.sheet.route-active.details-open{')&&
 html.includes('background:rgba(3,30,45,.97)!important'),
 'details panel must use the same dark, non-overlapping cockpit styling');
assert(html.includes('.buttons #plus,.buttons #minus{display:none!important}'),
 'zoom buttons must not cover live route lines in the narrow center map');
assert(html.includes('id="current-weather-chip"'),
 'compact current weather chip must be shown without duplicating route details');
const modelTime=Math.floor(Date.now()/1000)-900;
win.mediaengineCurrentWeather({temperature:16.2,precipitation:0,wind:12.4,
 weatherCode:2,kind:'route-start',modelBased:true,liveStationObservation:false,
 modelTime,receivedAt:Date.now()});
assert(get('current-weather-label').textContent.includes('Rota başlangıcı'),
 'weather cannot falsely claim GPS-based location');
assert(get('current-weather-label').textContent.includes('model'),
 'Open-Meteo model weather cannot pretend to be observed station data');
const weatherBefore=get('current-weather-label').textContent;
win.mediaengineCurrentWeather({temperature:70,precipitation:0,wind:10,
 weatherCode:0,kind:'gps',modelBased:false,modelTime,receivedAt:Date.now()});
assert.equal(get('current-weather-label').textContent,weatherBefore,
 'unverified current observations must be discarded');
win.mediaengineCurrentWeather({temperature:11,precipitation:0,wind:9,
 weatherCode:1,kind:'gps',modelBased:true,
 modelTime:Math.floor(Date.now()/1000)-8000,receivedAt:Date.now()});
assert.equal(get('current-weather-label').textContent,weatherBefore,
 'stale current model must not overwrite a recent weather packet');
get('current-weather-refresh').onclick();
assert(get('status').textContent.includes('Android bağlantı hatası')||
 get('current-weather-label').textContent===weatherBefore,
 'manual weather refresh must not fabricate a sensor value');
win.mediaengineRoute({coordinates:[[34.6833,37.9667],[33.8,37.4]],
 distanceKm:145,durationMin:120,steps:['Devam et'],navigationActive:false});
win.mediaengineAlternatives({selectedIndex:0,fastestIndex:0,shortestIndex:0,
 chargeAwareIndex:-1,options:[{index:0,km:145,minutes:120,
 energyLowKwh:23.2,energyHighKwh:34.8,socKnown:false,stationsKnown:true,
 ccsCount:0,chargeNeededModel:false}]});
get('co-alt-toggle').onclick();
assert(!get('co-alts').classList.contains('open'),
 'a single OSRM route should not cover the map with a fake comparison');
assert(get('status').textContent.includes('bir rota'),
 'one route must be explained as a limitation, not a feature');

assert(html.includes('NEREDEN') && html.includes('NEREYE'));
console.log('PASS: 81-city offline fallback, failed Photon, Turkish accents, autocomplete, stale results, manual route and swap.');

const routeCase={
 coordinates:[[30,39],[31,39.5],[32,40]],distanceKm:185,
 durationMin:141,routeIndex:0,alternatives:3,
 routePreviews:[
  {index:0,coordinates:[[39,30],[39.5,31],[40,32]],km:185,minutes:141},
  {index:1,coordinates:[[39,30],[39.3,30.8],[40,32]],km:197,minutes:157},
  {index:2,coordinates:[[39,30],[39.8,31.5],[40,32]],km:202,minutes:168}
 ]};
context.window.mediaengineRoute(routeCase);
assert.equal(get('route-map-alts').style.display,'block');
const colored=drawnLines.filter(x=>x.options&&x.options.dashArray);
assert(colored.length>=2,'all provided OSRM options must appear on map');
colored[0].handlers.click();
assert(calls.chosen.includes(1),'tap alternative polyline must select real route');
assert(get('route-map-alts').textContent.includes('3 rota · haritada 3'),
 'show actual route total and limited uncluttered on-map preview');
context.window.mediaengineRoute({...routeCase,alternatives:1,
 routePreviews:[routeCase.routePreviews[0]]});
assert.equal(get('route-map-alts').style.display,'none',
 'one provider route is not multiple alternatives');
console.log('PASS: actual OSRM alternatives retained in selector, up to two map previews clickable');

/* Safety HUD smoke test after all earlier GPS opt-in assertions. */
context.window.mediaenginePosition({lat:38.123,lon:34.77,accuracy:10,
 timeMs:Date.now(),speedKmh:63});
assert.equal(get('safety-kmh').textContent,'63',
 'valid measured speed must appear in the optional speed chip');
context.window.mediaenginePosition({lat:38.123,lon:34.77,accuracy:80,
 timeMs:Date.now(),speedKmh:70});
assert(!get('safety-speed').classList.contains('active'),
 'poor-accuracy GPS may not claim current measured speed');
// Test a current sampled OSM camera response and reject a prior route response.
context.window.mediaengineRoute({...routeCase,routeGeneration:42});
const priorCameraCount=drawnMarkers.length;
context.window.mediaengineSafetyCameras({source:'© OpenStreetMap contributors (ODbL)',
 routeGeneration:41,coverage:'sampled route neighborhoods only',
 items:[{id:100101,lat:38.99,lon:34.13,kind:'fixed'}]});
assert.equal(drawnMarkers.length,priorCameraCount,
 'old route-camera response must never draw stale pins');
context.window.mediaengineSafetyCameras({source:'© OpenStreetMap contributors (ODbL)',
 routeGeneration:42,coverage:'sampled route neighborhoods only',
 items:[{id:100101,lat:38.99,lon:34.13,kind:'fixed'}]});
assert(get('safety-status').textContent.includes('sınırlı örnekleme'),
 'limited OSM camera coverage must be disclosed');
assert.equal(drawnMarkers.length,priorCameraCount+1,
 'automatic route should show real OSM camera record');
// Toll labels never claim that a free road was verified: public Valhalla may
// ignore a requested exclusion, and no bridge/road-fee tariffs are available.
assert(html.includes('alt-filter-avoid')&&
       html.includes('Ücretli yoldan kaçınma isteği · teyitsiz'),
  'traffic-free and toll-avoid routing preferences must not fabricate confirmed toll status');
assert(html.includes('window.mediaengineRouteVariants=function(packet)'),
  'slowly discovered real alternatives must update map without replacing route');
assert(html.includes('5 dk otomatik yenileme (model)'),
  'route weather is polled on a model cadence, not called second-by-second live');
context.window.mediaengineRoute({...routeCase,routeGeneration:42});
const beforeIncidentPins=drawnMarkers.length;
context.window.mediaengineTrafficIncidents({source:'TomTom Orbis Incident Details',
 routeGeneration:41,items:[{id:'x',lat:38.99,lon:34.13,
  category:'roadWorks',label:'Yol çalışması bildirimi',aheadMeters:2000}]});
assert.equal(drawnMarkers.length,beforeIncidentPins,
 'stale TomTom incident batch must never overwrite current route');
context.window.mediaengineTrafficIncidents({source:'TomTom Orbis Incident Details',
 routeGeneration:42,items:[{id:'live-event',lat:38.99,lon:34.13,
  category:'roadWorks',label:'Yol çalışması bildirimi',aheadMeters:2000,
  description:'Bakım çalışması'}]});
assert.equal(drawnMarkers.length,beforeIncidentPins+1,
 'provider incident must appear as a genuine map pin');
assert(get('traffic-event-map-status').textContent.includes('1 olay bildirimi'),
 'current incident coverage must display provider and count, not say no accidents');
assert(drawnMarkers[drawnMarkers.length-1].popup.textContent.includes('yön eşleşmesi'),
 'incidents must not be portrayed as verified same-lane road closures');
// OBD unavailable: scenario appears explicitly in the cockpit without
// becoming a measured battery bar or taking over a true ECU reading.
win.mediaengineTelemetry({fresh:false,status:'OBD bağlı değil'});
win.mediaengineSocScenario({measured:false,percent:80,userSelected:false});
assert.equal(get('co-soc').textContent,'80 %');
assert(get('co-sensor-age').textContent.includes('SENARYOSU'));
assert.equal(get('co-soc-fill').style.width,'0%',
 'a planning scenario must not fill the measured BMS progress bar');
get('soc-choice-100').onclick();
win.mediaengineSocScenario({measured:false,percent:100,userSelected:true});
assert.equal(get('co-soc').textContent,'100 %',
 '100 percent scenario should be visible even without OBD');
assert.equal(get('co-trip-battery').textContent,
 'Çıkış senaryosu: %100 (ölçüm değil)');
win.mediaengineTelemetry({fresh:true,soc:'62 %',status:'OBD bağlı'});
assert.equal(get('co-soc').textContent,'62 %',
 'actual validated BMS measurement always takes priority');
win.mediaengineSocScenario({measured:true,percent:62,userSelected:true});
win.mediaengineTelemetry({fresh:false,status:'OBD kesildi'});
assert.equal(get('co-soc').textContent,'— %',
 'do not show a stale measurement or earlier scenario as measured');
console.log('PASS: automatically enabled, scoped cameras and no driver-facing toggles');
