// Contract: TomTom Orbis traffic only. Test source, key gate, successful tileload,
// failed tile requests and fallback. Never claim actual production API availability.
const assert=require('assert'),fs=require('fs'),vm=require('vm'),path=require('path');
const source=fs.readFileSync(path.join(__dirname,
 '../overlay/fermata/src/main/assets/evcockpit/traffic-provider.js'),'utf8');
assert(!/google\.maps|global\.ymaps|api-maps\.yandex|maps\.googleapis/.test(source));
function create(key,fail){
 const states=[],tiles=[],removed=[],panes={};
 const window={EVCOCKPIT_TRAFFIC_CONFIG:{tomtomKey:key},
  mediaengineTrafficStatus(o){states.push(o)},
  L:{tileLayer(url,options){
   return {url,options,callbacks:{},
    on(event,fn){this.callbacks[event]=fn;return this},
    addTo(){tiles.push(this);
     if(url.includes('/flow/')){
      if(fail){for(let i=0;i<3;i++)this.callbacks.tileerror()}
      else this.callbacks.tileload();
     }
     return this;
    },redraw(){}
   };
  }}};
 const sandbox={window,document:{},Number,Math,Array,Promise,
  setTimeout(){return 1},clearTimeout(){},
  setInterval(){return 1},clearInterval(){}};
 vm.runInNewContext(source,sandbox,{filename:'traffic-provider.js'});
 window.EvCockpitTrafficProviders.attachLeaflet({
  removeLayer(v){removed.push(v)},
  createPane(name){const p={style:{}};panes[name]=p;return p},
  getPane(name){return panes[name]||null}
 });
 return {api:window.EvCockpitTrafficProviders,states,tiles,removed,panes};
}
(async()=>{
 const none=create('',false);
 assert.strictEqual(none.api.configured().tomtom,false);
 assert.strictEqual(await none.api.activate('google'),false);
 assert.strictEqual(await none.api.activate('yandex'),false);
 assert.strictEqual(await none.api.activate('tomtom'),false);
 assert.strictEqual(none.tiles.length,0);
 assert.strictEqual(none.api.setTomTomKey('short'),false);
 assert.strictEqual(none.api.setTomTomKey('TEST_TOMTOM_KEY_123456789'),true);
 assert.strictEqual(none.api.configured().tomtom,true);
 assert.strictEqual(none.tiles.length,0,
  'Saving a key without a route must not start paid traffic tiles');
 assert(!none.states.some(s=>s.status==='active'),
  'Saving a key is not evidence of live traffic');
 assert.strictEqual(none.api.setTomTomKey(''),true);
 assert.strictEqual(none.api.getActive(),'osm');
 const ok=create('TEST_TOMTOM_KEY_123456789',false);
 assert.strictEqual(await ok.api.activate('tomtom',[39,35],10),true);
 assert.strictEqual(ok.tiles.length,2);
 assert(ok.tiles[0].url.includes('/maps/orbis/traffic/flow/raster/tile/'));
 assert(ok.tiles[1].url.includes('/maps/orbis/traffic/incidents/raster/tile/'));
 assert(ok.tiles[0].url.includes('apiVersion=2'));
 assert.equal(ok.tiles[0].options.updateWhenIdle,true);
 assert.equal(ok.tiles[0].options.keepBuffer,2);
 assert.equal(ok.tiles[0].options.pane,'evcockpit-live-traffic');
 assert.strictEqual(ok.panes['evcockpit-live-traffic'].style.zIndex,'425',
  'TomTom transparent tiles must be ABOVE the opaque MapLibre overlay');
 assert.strictEqual(ok.panes['evcockpit-live-traffic'].style.pointerEvents,'none',
  'TomTom visual layer must not block route/station taps');
 assert.strictEqual(await ok.api.close(),true);
 assert(ok.removed.length>=2);
 const bad=create('TEST_TOMTOM_KEY_123456789',true);
 assert.strictEqual(await bad.api.activate('tomtom',[39,35],10),false);
 assert.strictEqual(bad.api.getActive(),'osm');
 assert(bad.states.every(s=>s.status!=='active'));
 console.log('PASS: TomTom-only traffic, correct tile endpoints, no bogus live claims');
})().catch(e=>{console.error(e);process.exitCode=1});
