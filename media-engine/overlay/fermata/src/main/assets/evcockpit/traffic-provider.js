/* Official TomTom Orbis v2 live-traffic overlay for the native Leaflet/OSM map.
 * Google/Yandex live SDKs removed: not confirmed functional in this application.
 * Never display simulated traffic or change OSM route ETA without live routing.
 */
(function(global){
'use strict';
var active='osm',requested=0,leafletMap=null,tomtomTiles=[],
    liveAt=0,trafficPane=null,refreshErrors=0,
    tomtomTimer=null,tomtomPending=null,lastCenter=[38.9,35.1],lastZoom=7;
var config=global.EVCOCKPIT_TRAFFIC_CONFIG||{};
function validKey(key){return typeof key==='string'&&/^[A-Za-z0-9_-]{15,200}$/.test(key)}
function keyFor(){return config.tomtomKey||''}
function status(provider,detail,ready){
 if(typeof global.mediaengineTrafficStatus==='function')
  global.mediaengineTrafficStatus({provider:provider,status:ready?'active':'unavailable',detail:detail});
}
function reset(){
 if(tomtomTimer!==null){clearInterval(tomtomTimer);tomtomTimer=null}
 if(leafletMap&&leafletMap.removeLayer)tomtomTiles.forEach(function(layer){
   leafletMap.removeLayer(layer)
 });
 tomtomTiles=[];
 if(tomtomPending){var abort=tomtomPending;tomtomPending=null;abort(false)}
 liveAt=0;refreshErrors=0;
}
function ensureTrafficPane(){
 // Leaflet's default tilePane sits BEHIND the opaque MapLibre vector canvas.
 // Raster traffic tiles therefore loaded successfully but remained invisible.
 // A dedicated transparent pane must be ABOVE MapLibre's overlayPane (400),
 // BELOW markerPane (600) and NOT intercept route / station taps.
 if(!leafletMap||!leafletMap.createPane)return null;
 try{
  trafficPane=leafletMap.getPane&&leafletMap.getPane('evcockpit-live-traffic');
  if(!trafficPane)trafficPane=leafletMap.createPane('evcockpit-live-traffic');
  if(trafficPane&&trafficPane.style){
   trafficPane.style.zIndex='425';
   trafficPane.style.pointerEvents='none';
  }
  return trafficPane;
 }catch(_){return null}
}
function openTomTom(seq){
 if(!leafletMap||!global.L||!global.L.tileLayer){
  status('tomtom','Leaflet haritası hazır değil; trafik açılamadı.',false);
  return Promise.resolve(false);
 }
 var key=keyFor('tomtom');
 var root='https://api.tomtom.com/maps/orbis/traffic/';
 var q='?apiVersion=2&style=dark&tileSize=256&key='+encodeURIComponent(key);
 var pane=ensureTrafficPane();
 // If a vector basemap is attached, the pane is essential; do not silently
 // report "live" for raster tiles that are hidden under a MapLibre canvas.
 if(leafletMap.createPane&&!pane){
  status('tomtom','Trafik harita katmanı oluşturulamadı.',false);
  return Promise.resolve(false);
 }
 var options={tileSize:256,opacity:.92,maxZoom:19,noWrap:true,
  attribution:'© TomTom',updateWhenIdle:true,keepBuffer:2};
 if(pane)options.pane='evcockpit-live-traffic';
 // The companion EVCOCKPITLiveMap module creates the single shared flow layer.
 // Legacy/test pages still use the identical official TomTom endpoint.
 var flow=null;
 try{
  var factory=global.EVCOCKPITLiveMap&&global.EVCOCKPITLiveMap.createTomTomFlowTileLayer;
  if(typeof factory==='function')flow=factory(key,'evcockpit-live-traffic');
  if(!flow)flow=global.L.tileLayer(root+'flow/raster/tile/{z}/{x}/{y}'+q,options);
 }catch(_){status('tomtom','Trafik akış katmanı başlatılamadı.',false);return Promise.resolve(false)}
 var incidents=null;
 try{
  incidents=global.L.tileLayer(root+'incidents/raster/tile/{z}/{x}/{y}'+q,
   Object.assign({},options,{opacity:.95}));
 }catch(_){
  // Incident icons are optional; verified congestion lines still work.
  incidents=null;
 }
 reset();active='tomtom';tomtomTiles=incidents?[flow,incidents]:[flow];
 status('tomtom','TomTom trafik döşemeleri kontrol ediliyor…',false);
 return new Promise(function(resolve){
  var finished=false,errors=0,confirmed=false;
  function finish(ok){
   if(finished)return;finished=true;
   if(tomtomPending===finish)tomtomPending=null;
   resolve(ok);
  }
  tomtomPending=finish;
  var timeout=setTimeout(function(){
   if(finished)return;
   if(seq===requested&&!confirmed){
    reset();active='osm';
    status('tomtom','TomTom trafik zaman aşımı: OpenFreeMap haritası korundu.',false);
   }
   finish(false);
  },12000);
  function loadedFlow(){
   if(seq!==requested||active!=='tomtom'||finished)return;
   confirmed=true;liveAt=Date.now();refreshErrors=0;clearTimeout(timeout);
   status('tomtom','TomTom trafik döşemesi yüklendi · '+new Date(liveAt).toLocaleTimeString('tr-TR',{hour:'2-digit',minute:'2-digit'})+' · rota ETA ayrı doğrulanır.',true);
   finish(true);
  }
  function failedFlow(){
   if(seq!==requested||confirmed||finished)return;
   if(++errors>=3){
    clearTimeout(timeout);reset();active='osm';
    status('tomtom','TomTom trafik döşemesi alınamadı (izin, kota veya ağ). OpenFreeMap açık.',false);
    finish(false);
   }
  }
  flow.on('tileload',function(){
   if(confirmed&&seq===requested&&active==='tomtom'){
    liveAt=Date.now();refreshErrors=0;
   }else loadedFlow();
  }).on('tileerror',function(){
   if(!confirmed){failedFlow();return}
   if(seq!==requested||active!=='tomtom')return;
   if(++refreshErrors>=3){
    // Stale data is not "live": remove the failed layer instead of
    // showing a frozen green status forever.
    reset();active='osm';
    status('tomtom','TomTom trafik yenilenemedi (ağ/izin/kota).',false);
   }
  });
  try{
   flow.addTo(leafletMap);
   if(active!=='tomtom'||seq!==requested)return;
   if(incidents)incidents.addTo(leafletMap);
   if(active==='tomtom')tomtomTimer=setInterval(function(){
     if(seq===requested&&active==='tomtom'){
      if(typeof flow.redraw==='function')flow.redraw();
      if(incidents&&typeof incidents.redraw==='function')incidents.redraw();
     }
   },300000);
  }catch(e){
   clearTimeout(timeout);reset();active='osm';
   status('tomtom','TomTom katmanı başlatılamadı; OpenFreeMap açık.',false);
   finish(false);
  }
 });
}


function activate(which,center,zoom){
 var seq=++requested;
 if(which==='osm'){
  reset();active='osm';
  status('osm','OpenStreetMap açık · trafik katmanı kapalı.',false);
  return Promise.resolve(true);
 }
 if(which!=='tomtom')return Promise.resolve(false);
 if(!validKey(keyFor())){
  status('tomtom','TomTom API anahtarı gerekli; OSM korunuyor.',false);
  return Promise.resolve(false);
 }
 if(Array.isArray(center)&&center.length===2&&center.every(Number.isFinite))
  lastCenter=center.slice();
 if(Number.isFinite(zoom))lastZoom=Math.max(4,Math.min(19,zoom));
 return openTomTom(seq);
}
global.EvCockpitTrafficProviders={
 activate:activate,
 getActive:function(){return active},
 configured:function(){return {tomtom:validKey(keyFor())}},
 getHealth:function(){return {active:active,liveAt:liveAt,configured:validKey(keyFor())}},
 attachLeaflet:function(map){leafletMap=map},
 setTomTomKey:function(key){
  if(typeof key!=='string'||(key!==''&&!validKey(key)))return false;
  requested++;reset();active='osm';config.tomtomKey=key;
  status('tomtom',key?
     'TomTom anahtarı kaydedildi; rota oluşturulduğunda trafik otomatik yüklenecek.':
     'TomTom anahtarı temizlendi.',false);
  // The route, not a settings screen or app launch, starts paid traffic tiles.
  return true;
 },
 updateViewport:function(c,z){
  if(Array.isArray(c)&&c.length===2&&c.every(Number.isFinite))lastCenter=c.slice();
  if(Number.isFinite(z))lastZoom=z;
 },
 updateRoute:function(){},updateStations:function(){},updatePosition:function(){},
 close:function(){return activate('osm')}
};
})(window);
