/* Model SoC colours on the SAME real Leaflet map; no second map instance.
 * Never paint battery percentages without OBD/explicit scenario. */
(function(root){'use strict';
var active=[],signature='',legend=null;
function remove(){active.forEach(function(x){try{x.remove()}catch(_){}});active=[];signature='';
 if(legend){legend.remove();legend=null}}
function draw(coords,opts){
 if(!Array.isArray(coords)||coords.length<2||!opts||!Number.isFinite(opts.startSoc)||
   !Number.isFinite(opts.kwh100)||!Number.isFinite(opts.capacityKwh)){remove();return}
 var L=root.L,map=root.evcockpitLiveLeafletMap;
 if(!L||!map||!L.polyline)return;
 var sig=[opts.startSoc,opts.kwh100,opts.capacityKwh,coords.length,coords[0]&&coords[0][0],
  coords[coords.length-1]&&coords[coords.length-1][0]].join('|');
 if(sig===signature)return;remove();signature=sig;
 var good=coords.filter(function(c){return Array.isArray(c)&&Number.isFinite(c[0])&&Number.isFinite(c[1])});
 if(good.length<2)return;
 var total=0,dist=[0];for(var i=1;i<good.length;i++){
 var d=L.latLng(good[i-1][1],good[i-1][0]).distanceTo(L.latLng(good[i][1],good[i][0]))/1000;
 total+=d;dist.push(total)}
 if(total<=0)return;
 var distanceKm=Number(opts.distanceKm)||total;
 var start=0;while(start<good.length-1){
 var modelSoc=opts.startSoc-(dist[start]/total*distanceKm)*opts.kwh100/opts.capacityKwh;
 var color=modelSoc<15?'#ff6262':modelSoc<30?'#ffc45d':'#40d9a9';
 var end=Math.min(start+Math.max(1,Math.floor(good.length/100)),good.length-1);
 active.push(L.polyline(good.slice(start,end+1).map(function(c){return[c[1],c[0]]}),
  {color:color,weight:6,opacity:.93,interactive:false}).addTo(map));
 start=end;
 }
 legend=root.document.createElement('div');legend.className='eya-energy-legend';
 legend.textContent='Batarya renkleri · MODEL:  ≥%30 yeşil   %15–30 sarı   <%15 kırmızı';
 root.document.body.appendChild(legend);
}
var api={draw:draw,clear:remove};if(typeof module==='object'&&module.exports)module.exports=api;
root.EyaEnergyRoute=api;
})(typeof window==='object'?window:globalThis);
