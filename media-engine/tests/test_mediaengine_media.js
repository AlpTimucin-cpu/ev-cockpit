// In-place MediaEngine 3.2 media shortcuts must not replace route/OBD or media engines.
'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,
 '../overlay/fermata/src/main/assets/evcockpit/evcockpit-mediaengine-media.js'),'utf8');
const css=fs.readFileSync(path.join(__dirname,
 '../overlay/fermata/src/main/assets/evcockpit/evcockpit-mediaengine-media.css'),'utf8');
const html=fs.readFileSync(path.join(__dirname,
 '../overlay/fermata/src/main/assets/evcockpit/navigator.html'),'utf8');
assert.match(html,/src="evcockpit-mediaengine-media.js"/);
assert.match(html,/href="evcockpit-mediaengine-media.css"/);
assert.match(css,/evcockpit-mediaengine-drawer\[hidden\]/);
const entries={};
function el(tag='div'){
 return {tagName:tag,id:'',className:'',children:[],hidden:false,onclick:null,
  attrs:{},addEventListener(type,fn){this['on'+type]=fn},
  setAttribute(k,v){this.attrs[k]=v},
  appendChild(c){this.children.push(c);if(c.id)entries[c.id]=c},
  textContent:'',type:''};
}
const body=el('body');
const doc={readyState:'complete',body,createElement:el,getElementById(id){
 return entries[id] || null;
},addEventListener(){}};
entries['dock-apps']=el('button');entries['dock-music']=el('button');
const calls=[];
const root={MediaEngineNative:{openMediaEngineCard(id){calls.push(id)},openOriginalMedia(){calls.push('external-original')}},console:{warn(){}}};
vm.runInNewContext(source,{window:root,document:doc}, {filename:'evcockpit-mediaengine-media.js'});
assert(root.EVCOCKPITMediaEngineMediaHub,'media hub API missing');
assert.equal(root.EVCOCKPITMediaEngineMediaHub.isOpen(),false);
assert.equal(root.EVCOCKPITMediaEngineMediaHub.items().length,15);
assert(entries['evcockpit-mediaengine-external-original'],
 'external original 3.2 optional launcher must be visible');
assert.equal(typeof root.EVCOCKPITMediaEngineMediaHub.openOriginalMedia,'function');

for(const id of ['netflix','disney','prime','equalizer','radio','tv','mirror',
 'web','windasist','shortcuts','settings']){
 assert(root.EVCOCKPITMediaEngineMediaHub.items().includes(id),'missing module '+id);
}
entries['dock-apps'].onclick();
assert.equal(root.EVCOCKPITMediaEngineMediaHub.isOpen(),true,'dock must open in-place panel');
entries['evcockpit-mediaengine-external-original'].onclick();
assert.equal(calls.at(-1),'external-original');
assert.equal(root.EVCOCKPITMediaEngineMediaHub.isOpen(),false);
entries['dock-apps'].onclick();
assert.equal(root.EVCOCKPITMediaEngineMediaHub.isOpen(),true,'dock reopens after external launch');

const layer=entries['evcockpit-mediaengine-drawer'];
assert.equal(layer.hidden,false);
assert.equal(layer.attrs['aria-hidden'],'false');
assert.equal(body.children.length,1,'navigation map must not be recreated');
entries['evcockpit-mediaengine-netflix'].onclick();
assert.equal(calls.at(-1),'netflix','site card must reach the native in-app router');
assert.equal(root.EVCOCKPITMediaEngineMediaHub.isOpen(),false);
entries['dock-music'].onclick();
entries['evcockpit-mediaengine-equalizer'].onclick();
assert.equal(calls.at(-1),'equalizer','MediaEngine original equalizer must be used');
assert.equal(root.EVCOCKPITMediaEngineMediaHub.isOpen(),false);
root.EVCOCKPITMediaEngineMediaHub.open();
entries['evcockpit-mediaengine-close'].onclick();
assert.equal(layer.hidden,true);
delete root.MediaEngineNative;
assert.equal(root.EVCOCKPITMediaEngineMediaHub.open(),true);
assert.doesNotThrow(()=>entries['evcockpit-mediaengine-prime'].onclick());
assert.equal(root.EVCOCKPITMediaEngineMediaHub.isOpen(),false);
assert(!source.includes('MediaSession.setPlaybackSpeed') &&
 !source.includes('webview.loadUrl("https://www.netflix'),
 'DRM playback and original audio engines must not be spoofed');
console.log('PASS: MediaEngine in-place catalogue, 15 cards, embedded site routing and equalizer');
