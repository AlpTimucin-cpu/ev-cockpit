'use strict';const assert=require('node:assert/strict');
const m=require('../overlay/fermata/src/main/assets/evcockpit/eya-charging-model.js');
const old=require('../overlay/fermata/src/main/assets/evcockpit/eya-trip-planner.js');
for(const v of [70,90,110,130])for(const t of [-10,0,15,35]){
const a=m.consumptionKwhKm(v,t),b=old.consumption(v,t);
assert.ok(Math.abs(a*100-b)<.00000001)}
for(const [a,b] of [[10,50],[50,80],[80,100],[10,80]]){
const x=m.chargeMinutes(a,b,52,15),y=old.chargingMinutes(a,b,52,15);
assert.ok(Math.abs(x-y)/y<.01,'charging curve within 1 percent')}
const b={distanceKm:400,capacityKwh:52,speedKmh:110,tempC:15,minArrivalPct:10,maxChargePct:80};
assert.equal(m.plan({...b,startSoc:null}).arrivalSoc,null);
assert.equal(m.plan({...b,startSoc:80}).complete,false);
const st={id:'real',ccs:true,title:'EPDK CCS',corridorKm:3,routeProgressKm:150};
const p=m.plan({...b,startSoc:80,stations:[st]});assert.equal(p.stops.length,1);
assert.equal(p.stops[0].source,'EPDK');assert.equal(p.stops[0].costTl,null);
assert.equal(m.plan({...b,startSoc:80,stations:[{...st,ccs:false}]}).stops.length,0);
assert.equal(m.plan({...b,startSoc:80,stations:[{...st,corridorKm:8}]}).stops.length,0);
console.log('PASS: model matches Word prototype and requires actual DC stations');
