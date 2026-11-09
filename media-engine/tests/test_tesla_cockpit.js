// Regression: the premium cockpit may observe but never replace native
// navigation, MediaEngine media, OBD freshness or strict country EPDK rules.
'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,
 '../overlay/fermata/src/main/assets/evcockpit/evcockpit-tesla-cockpit.js'),'utf8');
const realNow=Date.now();
let now=realNow,interval=null,positionCalls=0,telemetryCalls=0,weatherCalls=0;
let routeCalls=0,stationCalls=0,maneuverCalls=0,statusCalls=0,routeUi=false;
const elements=new Map();
function element(id){
 if(!elements.has(id)) elements.set(id,{
   id,hidden:false,style:{},attrs:{},children:[],
   classList:{contains(k){return k==='has-route'&&routeUi}},
   setAttribute(k,v){this.attrs[k]=v},
   removeAttribute(k){delete this.attrs[k]},
   appendChild(n){this.children.push(n)},
 });
 const e=elements.get(id);
 if(!Object.getOwnPropertyDescriptor(e,'textContent')){
   e._text='';
   Object.defineProperty(e,'textContent',{
     get(){return this._text},
     set(value){this._text=String(value);this.children=[]}
   });
 }
 return e;
}
const classes=new Set();
const original={
  mediaenginePosition(){positionCalls++},
  mediaengineTelemetry(){telemetryCalls++},
  mediaengineCurrentWeather(){weatherCalls++},
  mediaengineCurrentWeatherError(){},
  mediaengineRoute(){routeCalls++;routeUi=true},
  mediaengineStations(){stationCalls++},
  mediaengineManeuver(){maneuverCalls++},
  mediaengineStatus(){statusCalls++;routeUi=false},
};
const win={
 ...original,
 document:{
   getElementById:element,
   createElement(tag){return {tag,className:'',style:{},title:'',children:[],
     attrs:{},setAttribute(k,v){this.attrs[k]=v},
     appendChild(n){this.children.push(n)}}},
   body:{classList:{toggle(name,on){on?classes.add(name):classes.delete(name)}}}
 },
 evcockpitLiveLeafletMap:{invalidateSize(){win.mapResizes=(win.mapResizes||0)+1}},
 setInterval(fn){interval=fn;return 1},
 requestAnimationFrame(fn){fn()},
 console:{warn(){},error(){}},
};
const context=vm.createContext({window:win,Date:class extends Date {
 static now(){return now}
},Number,Math,String,Array});
vm.runInContext(source,context,{filename:'evcockpit-tesla-cockpit.js'});
assert.equal(win.mapResizes,1,'one existing map was resized, not recreated');
// Re-entry must not stack multiple native callback listeners or timers.
assert.equal(win.EVCOCKPITTeslaCockpit.start(),true);
assert.equal(win.mapResizes,1,'start() is idempotent');
// Missing/malformed packets are common when POCO F5 loses its ELM327 socket.
assert.doesNotThrow(()=>win.mediaengineTelemetry(undefined));
assert.doesNotThrow(()=>win.mediaenginePosition(null));
assert.doesNotThrow(()=>win.mediaengineCurrentWeather({modelBased:false}));
assert.equal(element('evcockpit-side-soc').textContent,'— %');
assert.equal(element('evcockpit-drive-speed').textContent,'—');
// Reset counts: the malformed data still passes through MediaEngine callback first.
telemetryCalls=0;positionCalls=0;weatherCalls=0;
assert.equal(win.EVCOCKPITTeslaCockpit.parseSoc('64 %'),64);
assert.equal(win.EVCOCKPITTeslaCockpit.parseSoc('105%'),null);
assert.equal(win.EVCOCKPITTeslaCockpit.parseSoc('<img onerror=alert(1)>'),null);
assert.equal(element('evcockpit-drive-speed').textContent,'—');
assert.equal(element('evcockpit-drive-gear').textContent,'Vites: —');
assert.equal(element('evcockpit-side-soc').textContent,'— %');
win.mediaengineTelemetry({fresh:true,soc:'64 %',chargeMode:'Charging',gear:'P'});
assert.equal(telemetryCalls,1,'pre-existing callback runs');
assert.equal(element('evcockpit-side-soc').textContent,'64 %');
assert.equal(element('evcockpit-side-power').textContent,'— kW','no validated power PID');
assert.equal(element('evcockpit-side-range').textContent,'— km','no fake 318km from artwork');
assert.equal(element('evcockpit-drive-gear').textContent,'Vites: —',
 'never treat chargeMode or unverified gear as actual gear');
assert.equal(element('evcockpit-side-bar-fill').style.width,'64%');
assert(!classes.has('evcockpit-low-battery'));
win.mediaengineTelemetry({fresh:true,soc:'19 %',gear:'D',gearVerified:true});
assert.equal(element('evcockpit-drive-gear').textContent,'Vites: D');
assert(classes.has('evcockpit-low-battery'),'low SoC visual alert');
assert.equal(element('evcockpit-energy-impact').hidden,false);
win.mediaengineTelemetry({fresh:true,soc:'20 %'});
assert(!classes.has('evcockpit-low-battery'),'20% is not below 20%');
win.mediaenginePosition({lat:38,lon:35,timeMs:now,accuracy:8,speedKmh:92});
assert.equal(positionCalls,1);
assert.equal(element('evcockpit-drive-speed').textContent,'92');
win.mediaenginePosition({lat:38,lon:35,timeMs:now-31000,accuracy:8,speedKmh:92});
assert.equal(element('evcockpit-drive-speed').textContent,'—','stale GPS must clear');
win.mediaenginePosition({lat:38,lon:35,timeMs:now,accuracy:8,speedKmh:0});
assert.equal(element('evcockpit-drive-speed').textContent,'0',
 'zero only allowed for verified actual zero speed');
win.mediaengineCurrentWeather({modelBased:true,temperature:-4,wind:33,precipitation:0,
 receivedAt:now,modelTime:Math.floor(now/1000)});
assert.equal(weatherCalls,1);
assert.equal(element('evcockpit-side-temp').textContent,'-4°C');
assert.equal(element('evcockpit-side-wind').textContent,'33 km/sa');
assert.equal(element('evcockpit-energy-impact').hidden,false,
 'weather warning derives from actual provider packet');
now+=31000;interval();
assert.equal(element('evcockpit-drive-speed').textContent,'—','GPS expires automatically');
assert.equal(element('evcockpit-side-soc').textContent,'— %','OBD expires automatically');
assert.equal(element('evcockpit-side-bar-fill').style.width,'0%');
assert(!classes.has('evcockpit-low-battery'),'stale SoC may not drive a warning');
now+=5400001;interval();
assert.equal(element('evcockpit-side-temp').textContent,'— °C',
 'expired weather estimate must not appear current');
assert.equal(element('evcockpit-energy-impact').hidden,true);
win.mediaengineTelemetry({fresh:false,soc:'17 %'});
assert.equal(element('evcockpit-side-soc').textContent,'— %',
 'unverified SoC does not unlock DC suggestions or illuminate battery');
// Model and battery values are native-backed, never guessed from screenshots.
win.mediaengineTelemetry({fresh:true,soc:'72 %',voltage:'355 V',temperature:'24 °C',
  chargeMode:'Sürüş',powerKw:28,consumptionKwh100:18.1});
assert.equal(element('evcockpit-side-voltage').textContent,'355 V');
assert.equal(element('evcockpit-side-battery-temp').textContent,'24 °C');
assert.equal(element('evcockpit-side-mode').textContent,'Sürüş');
assert.equal(element('evcockpit-side-power').textContent,'— kW','unsupported PID ignored');
assert.equal(element('evcockpit-side-consumption').textContent,'— kWh/100 km');
win.mediaengineTelemetry({fresh:true,soc:'72 %',powerKw:28,powerKwVerified:true,
  consumptionKwh100:18.1,consumptionVerified:true});
assert.equal(element('evcockpit-side-power').textContent,'28.0 kW');
assert.equal(element('evcockpit-side-consumption').textContent,'18.1 kWh/100 km');
// Native route is in lon/lat order. New HUD must NOT draw a second L.map.
win.mediaengineRoute({coordinates:[[35,38],[35.5,38]],distanceKm:55,durationMin:52,
  energyLowKwh:14.5,energyHighKwh:20.3,
  steps:['Gerçek ilk manevra','200 m sonra sağa dön','İleride sola dön']});
assert.equal(element('ev-upcoming-maneuvers').children.length,2,
 'only subsequent OSRM instructions should be displayed');
assert.equal(element('ev-upcoming-maneuvers').children[0].children[1].textContent,
 '200 m sonra sağa dön');

assert.equal(routeCalls,1);
assert.equal(element('evcockpit-side-energy').textContent,'14.5–20.3 kWh');
win.mediaengineStations({kind:'route',items:[],chargeModel:'charge-likely',
  suggested:{id:'s1',lat:38,lon:35.25,routeProgressKm:27.5}});
assert.equal(stationCalls,1);
assert.equal(element('evcockpit-route-stop-markers').children.length,1,
 'only genuine on-corridor native suggestion should get a route marker');
win.mediaengineStations({kind:'route',items:[],chargeModel:'charge-likely',
  suggested:{id:'s2',lat:40,lon:35.25,routeProgressKm:27.5}});
assert.equal(element('evcockpit-route-stop-markers').children.length,0,
 'station outside 5 km corridor must remove prior marker');
win.mediaengineStations({kind:'visible',items:[{id:'national'}]});
assert.equal(element('evcockpit-route-stop-markers').children.length,0,
 'country/viewport records are not route suggestions');
win.mediaengineManeuver({instruction:'Sağa dön',roadRef:'D100',
  straightLineMeters:250,fixTimeMs:now,accuracy:8});
assert.equal(element('evcockpit-road-badge').textContent,'D100');
assert.equal(element('evcockpit-road-badge').hidden,false);
now+=31000;interval();
assert.equal(element('evcockpit-road-badge').hidden,true,
 'stale maneuver must not remain visible');
win.mediaengineStatus({detail:'Rota durduruldu'});
assert.equal(element('evcockpit-route-stop-markers').children.length,0);
assert.equal(element('evcockpit-side-energy').textContent,'— kWh');
assert.equal(element('ev-upcoming-maneuvers').children.length,0,
 'no stale turn instructions after route cancellation');
assert.equal(routeCalls,1);
console.log('PASS Tesla cockpit: real telemetry, 30s TTL, 5 km corridor, model/route reset, callbacks');
