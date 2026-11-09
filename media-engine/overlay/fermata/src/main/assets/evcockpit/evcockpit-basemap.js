/* OpenFreeMap vector basemap rendered by bundled MapLibre + Leaflet adapter.
 * Public instance: https://openfreemap.org/ (no API key).
 * Data: OpenMapTiles / OpenStreetMap. Renderer: BSD-3-Clause / ISC.
 * Natural Earth fallback is country geography, NOT offline street navigation. */
(function(g){
'use strict';
var L=g.L,map=null,layer=null,outline=null,cityMarkers=[];
var notify=function(){},state='uninitialized',success=false,errors=0;
var timer=null,epoch=0,srcIndex=0,hasStarted=false,cityPoints=[];
var dayMode=false;
function sources(){return [{name:'OpenFreeMap',
 url:'https://tiles.openfreemap.org/styles/'+(dayMode?'liberty':'dark')}];}
function emit(kind,detail){
 state=kind;
 notify({status:kind,detail:detail,source:sources()[srcIndex]?.name||'',
  online:kind==='online'});
}
function makeOutline(){
 if(!map||!L||!L.geoJSON||!g.EVCOCKPIT_OFFLINE_COUNTRIES)return;
 if(map.createPane && !map.getPane('evcockpit-country-outline')){
  var pane=map.createPane('evcockpit-country-outline');
  pane.style.zIndex='275';pane.style.pointerEvents='none';
 }
 outline=L.geoJSON(g.EVCOCKPIT_OFFLINE_COUNTRIES,{
  interactive:false,pane:map.getPane&&map.getPane('evcockpit-country-outline')?'evcockpit-country-outline':'overlayPane',
  style:function(f){return {
   color:f.properties?.name==='Turkey'?'#36819c':'#24566d',
   weight:f.properties?.name==='Turkey'?1.6:1,
   opacity:1,fillColor:f.properties?.name==='Turkey'?'#114055':'#0b3143',
   fillOpacity:.93
  }}
 });
 cityPoints.filter(function(c){
  return Array.isArray(c)&&c.length>=3&&
    ['Ankara','İstanbul','İzmir','Antalya','Konya','Bursa','Eskişehir',
     'Kayseri','Adana','Samsun','Gaziantep','Trabzon','Niğde','Kocaeli',
     'Mersin','Diyarbakır','Erzurum'].includes(c[0]);
 }).forEach(function(c){
  if(!L.marker||!L.divIcon)return;
  var marker=L.marker([c[1],c[2]],{interactive:false,
   pane:map.getPane&&map.getPane('evcockpit-country-outline')?'evcockpit-country-outline':'markerPane',
   icon:L.divIcon({className:'evcockpit-offline-city',
    html:'<span>'+String(c[0]).replace(/&/g,'&amp;').replace(/</g,'&lt;')+'</span>',
    iconSize:[82,18],iconAnchor:[41,9]})});
  cityMarkers.push(marker);
 });
}
function outlineVisible(visible){
 if(!outline||!map)return;
 if(visible){
  if(!map.hasLayer||!map.hasLayer(outline))outline.addTo(map);
  cityMarkers.forEach(function(m){if(!map.hasLayer||!map.hasLayer(m))m.addTo(map)});
 }else{
  if(map.hasLayer&&map.hasLayer(outline))map.removeLayer(outline);
  cityMarkers.forEach(function(m){if(map.hasLayer&&map.hasLayer(m))map.removeLayer(m)});
 }
}
function discard(){
 if(timer!==null){clearTimeout(timer);timer=null}
 if(layer&&map&&map.removeLayer)map.removeLayer(layer);
 layer=null;
}
function exhausted(){
 discard();outlineVisible(true);
 emit('offline','Çevrimiçi harita yüklenemedi · yalnız ülke sınırları · Yeniden dene');
}
// Apply actual vector paint properties, not a color filter over raster tiles.
function styleCockpit(gl){
 if(dayMode||!gl.getStyle||!gl.setPaintProperty)return;
 gl.getStyle().layers.forEach(function(item){
  var id=item.id,type=item.type;
  if(type==='background')gl.setPaintProperty(id,'background-color','#081d2b');
  else if(type==='fill'){
   if(id==='water')gl.setPaintProperty(id,'fill-color','#052d49');
   else if(/wood|park|grass/.test(id)){
    gl.setPaintProperty(id,'fill-color','#153b35');
    if(item.paint&&item.paint['fill-pattern'])gl.setPaintProperty(id,'fill-pattern',null);
   }else if(/residential/.test(id))gl.setPaintProperty(id,'fill-color','#173344');
   else if(/building/.test(id)){
    gl.setPaintProperty(id,'fill-color','#183343');
    gl.setPaintProperty(id,'fill-outline-color','#27495b');
   }
  }else if(type==='line'){
   if(/waterway/.test(id))gl.setPaintProperty(id,'line-color','#19516b');
   else if(/highway|road/.test(id))gl.setPaintProperty(id,'line-color',
    /casing/.test(id)?'#102c40':/motorway/.test(id)?'#5287a2':/major/.test(id)?'#37647c':'#25475c');
   else if(/boundary/.test(id))gl.setPaintProperty(id,'line-color','#476072');
  }else if(type==='symbol'&&item.layout&&item.layout['text-field']){
   gl.setPaintProperty(id,'text-color',/water/.test(id)?'#75a5bf':'#bbd4e2');
   gl.setPaintProperty(id,'text-halo-color','#081d2b');
   gl.setPaintProperty(id,'text-halo-width',1.2);
   if(/place|poi|water_name/.test(id))gl.setLayoutProperty(id,'text-field',
    ['coalesce',['get','name:tr'],['get','name:latin'],['get','name']]);
  }
 });
}
function attempt(i){
 var generation=++epoch;
 discard();success=false;errors=0;srcIndex=0;
 outlineVisible(true);
 if(!map||!L||typeof L.maplibreGL!=='function')return exhausted();
 emit('loading','OpenFreeMap vektör haritası yükleniyor…');
 timer=setTimeout(function(){
  if(generation===epoch&&!success)exhausted();
 },20000);
 try{
  layer=L.maplibreGL({style:sources()[0].url,interactive:false,
   attribution:'© OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors',
   maxZoom:19});
  layer.addTo(map);
  var gl=layer.getMaplibreMap();
  gl.on('style.load',function(){styleCockpit(gl)});
  function loaded(){
   if(generation!==epoch||success||!gl.loaded()||!gl.areTilesLoaded())return;
   success=true;
   if(timer!==null){clearTimeout(timer);timer=null}
   outlineVisible(false);
   emit('online','OpenFreeMap · vektör harita');
  }
  gl.on('load',loaded);
  gl.on('idle',loaded);
  gl.on('error',function(){
   // A single missing label/tile must not discard the whole map. The initial
   // load deadline handles an unavailable provider without faking success.
   if(generation===epoch&&!success)errors++;
  });
  loaded();
 }catch(e){exhausted()}
}
g.EvCockpitBaseMap={
 init:function(m,fn,cities){
  map=m;notify=typeof fn==='function'?fn:notify;
  cityPoints=Array.isArray(cities)?cities:[];
  makeOutline();outlineVisible(true);hasStarted=true;
  attempt(0);
 },
 retry:function(){if(!map)return false;attempt(0);return true},
 // Traffic credentials never replace the user's chosen OpenFreeMap basemap.
 setTomTomKey:function(value){return typeof value==='string'},
 setDay:function(value){
  var next=!!value;if(dayMode===next)return;
  dayMode=next;if(map)attempt(0);
 },
 status:function(){return {status:state,online:state==='online',
  source:sources()[srcIndex]?.name||'',fallbackOnly:state==='offline'}},
 sources:function(){return sources().map(function(x){return x.name})},
 close:function(){epoch++;discard();outlineVisible(false);hasStarted=false;
  emit('uninitialized','Harita kapatıldı.')}
};
})(window);
