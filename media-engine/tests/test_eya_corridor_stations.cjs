'use strict';
const assert=require('node:assert/strict'),m=require('../overlay/fermata/src/main/assets/evcockpit/eya-corridor-stations.js');
const common={title:'X',operator:'Operator',lat:38,lon:35,ccs:true,routeProgressKm:150,corridorKm:4.8};
const p={kind:'route',updatedAt:'2026-10-26',ccsCorridorRadiusKm:5,
items:[common,{...common,corridorKm:6},{...common,ccs:false},{...common,routeProgressKm:170}]};
const s=m.select(p,400);assert.equal(s.length,1);
assert.equal(s[0].snapshotAt,'2026-10-26');
assert.equal(s[0].detourMinutes,null);
assert.deepEqual(m.select({kind:'visible',items:p.items},400),[]);
console.log('PASS: 5km EPDK CCS only, duplicates, dated status, no fabricated detour');
