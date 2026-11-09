/* Only native EPDK route packet candidates: never geo-invent stations. */
(function(root){'use strict';
function finite(x){return typeof x==='number'&&Number.isFinite(x)}
function select(packet,roadKm){
 if(!packet||packet.kind!=='route'||!Array.isArray(packet.items)||
    !finite(roadKm)||roadKm<=0||Number(packet.ccsCorridorRadiusKm)>5.1)return [];
 const seen=new Set();
 return packet.items.filter(function(s){
  if(!s||s.cluster||s.ccs!==true||
    !finite(s.lat)||!finite(s.lon)||s.lat<35||s.lat>43||s.lon<25||s.lon>46||
    !finite(s.corridorKm)||s.corridorKm<0||s.corridorKm>5||
    !finite(s.routeProgressKm)||s.routeProgressKm<0||s.routeProgressKm>roadKm)return false;
  const segment=Math.floor(s.routeProgressKm/25);
  const id=(String(s.operator||'').toLocaleLowerCase('tr-TR')+'|'+segment+'|'+
    String(s.title||'').toLocaleLowerCase('tr-TR')).slice(0,180);
  if(seen.has(id))return false;seen.add(id);return true;
 }).map(function(s){return {
   id:String(s.id||s.operator+'-'+s.title+'-'+Math.round(s.routeProgressKm)),
   title:String(s.title||'DC CCS istasyonu'),operator:String(s.operator||''),
   ccs:true,lat:s.lat,lon:s.lon,routeProgressKm:s.routeProgressKm,
   corridorKm:s.corridorKm,detourMinutes:null,
   powerKw:finite(s.powerKw)&&s.powerKw>=20&&s.powerKw<=500?s.powerKw:null,
   snapshotAt:String(s.snapshotAt||packet.updatedAt||'')||null,
   sockets:String(s.sockets||'CCS')
 }}).sort(function(a,b){return a.routeProgressKm-b.routeProgressKm||
     (b.powerKw||0)-(a.powerKw||0)});
}
const api={select:select};if(typeof module==='object'&&module.exports)module.exports=api;
root.EyaCorridorStations=api;
})(typeof window==='object'?window:globalThis);
