'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const assets=path.join(__dirname,'../overlay/fermata/src/main/assets/evcockpit');
const core=require(path.join(assets,'eya-trip-planner.js'));
const nav=fs.readFileSync(path.join(assets,'navigator.html'),'utf8');
const css=fs.readFileSync(path.join(assets,'eya-trip-planner.css'),'utf8');
assert.ok(Math.abs(core.consumption(100,15)-18.4)<.001);
assert.ok(core.consumption(100,-5)>core.consumption(100,15));
const minutes=core.chargingMinutes(10,80,52,15);
assert.ok(minutes>24&&minutes<45, '100 kW with taper predicts finite charge duration');
assert.ok(core.chargingMinutes(80,10,52,15)===null);
const base={distanceKm:200,durationMin:130,capacity:52,
  consumptionKwh100:18,minArrival:10,maxCharge:80,temperature:15};
assert.equal(core.forecast(base).arrivalSoc,null,'missing SoC must not be synthesized');
const known=core.forecast({...base,soc:80});
assert.ok(Math.abs(known.arrivalSoc-(80-36/52*100))<.01);
assert.equal(core.forecast({...base,soc:80,stopIncluded:true}).arrivalSoc,null,
  'unknown selected-stop charge amount cannot yield arrival estimate');
const candidate=core.forecast({...base,soc:60,candidate:{routeProgressKm:85}});
assert.ok(candidate.candidate&&candidate.candidate.feasible,
  'real candidate supports explicitly modelled stop scenario');
assert.ok(candidate.candidate.chargeMin>0);
assert.equal(core.forecast({...base,soc:10,candidate:{routeProgressKm:180}}).candidate.chargeMin,null,
  'do not claim station is reachable with inadequate energy');
assert.ok(nav.includes('src="eya-trip-planner.js"')&&
  nav.includes('href="eya-trip-planner.css"'));
assert.ok(nav.indexOf('src="eya-trip-planner.js"')>
  nav.indexOf('src="evcockpit-tesla-cockpit.js"'), 'observer loads after native bridge');
assert.ok(css.includes('#eya-trip-panel[hidden]'));
assert.ok(!/Örnek DC istasyonu|stationFactory|syntheticGPS/.test(
  fs.readFileSync(path.join(assets,'eya-trip-planner.js'),'utf8')));
console.log('PASS: actual-route planner, source gating, safety, chart and dock integration');
