// Visible-only brand guard. Historic GPL attribution and Android internal
// identifiers must remain intact for license / upgrade compatibility.
'use strict';
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../../');
const ui=fs.readFileSync(path.join(root,'media-engine/overlay/fermata/src/main/assets/evcockpit/navigator.html'),'utf8');
const legacy=fs.readFileSync(path.join(root,'media-engine/overlay/fermata/src/main/assets/evcockpit/index.html'),'utf8');
const cards=fs.readFileSync(path.join(root,'media-engine/overlay/fermata/src/main/assets/evcockpit/evcockpit-mediaengine-media.js'),'utf8');
const readme=fs.readFileSync(path.join(root,'README.md'),'utf8');
const tr=fs.readFileSync(path.join(root,'README_TR.md'),'utf8');
const changes=fs.readFileSync(path.join(root,'CHANGELOG.md'),'utf8');
for(const [file,content] of [['README',readme],['README_TR',tr],['CHANGELOG',changes],['kokpit',ui],['harita',legacy],['kartlar',cards]]){
 assert(!/ID\.4 Yol Arkadaşım|Volkswagen ID\.4|VOLKSWAGEN/i.test(content),
   file+': legacy public-facing brand remained');
}
assert(ui.includes('<strong>EV Cockpit</strong>'));
assert(/<title>EV Cockpit/.test(ui));
assert(/<title>EV Cockpit/.test(legacy));
assert(cards.includes("'EV Cockpit'")||
       cards.includes('"EV Cockpit"'));
assert(!/"NETFLIX"|"Disney\+"|"prime video"/.test(cards));
// A later documentation refresh must not reintroduce the former product
// name on GitHub. Historical GPL project names remain in legal attribution.
for (const p of ['NOTICE.md','SECURITY_TOMTOM.md','media-engine/TRAFFIC_PROVIDERS.md']) {
 const content=fs.readFileSync(path.join(root,p),'utf8');
 assert(!/ID\\.4 Yol Arkadaşım|ID\\.4 önizleme APK/i.test(content),
   p+': obsolete public product name returned');
}
console.log('PASS: public app brand consistent; license and technical identifiers untouched');
