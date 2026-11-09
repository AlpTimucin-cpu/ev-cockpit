// Live provider integration check; no fixture map, telemetry or simulated traffic.
const { chromium } = require('playwright');
const path = require('node:path');
const fs = require('node:fs');
const assert = require('node:assert/strict');
(async () => {
 const browser = await chromium.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome',
  args: ['--no-sandbox','--allow-file-access-from-files','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader'] });
 try {
  const page = await browser.newPage({viewport:{width:1536,height:864}});
  const errors=[], oldRaster=[];
  page.on('pageerror',e=>errors.push(e.message));
  page.on('console',m=>{if(m.type()==='error')console.log('BROWSER:',m.text().slice(0,350))});
  page.on('requestfailed',r=>console.log('REQUEST FAILED:',r.url().split('?')[0],r.failure()?.errorText));
  page.on('request',r=>{if(/tile\.openstreetmap\./.test(r.url()))oldRaster.push(r.url())});
  await page.goto('file://'+path.resolve(__dirname,'../overlay/fermata/src/main/assets/evcockpit/navigator.html'));
  try {
   await page.waitForFunction(()=>window.EvCockpitBaseMap.status().online,{},{timeout:60000});
  } catch(e) {
   console.log('MAP STATUS:',await page.evaluate(()=>window.EvCockpitBaseMap.status()));
   const out=process.env.EVCOCKPIT_SCREENSHOTS||'/tmp/evcockpit-layout';fs.mkdirSync(out,{recursive:true});
   await page.screenshot({path:path.join(out,'vector-load-failure.png')});
   throw e;
  }
  assert.equal(await page.evaluate(()=>window.EvCockpitBaseMap.status().source),'OpenFreeMap');
  assert.equal(await page.locator('.maplibregl-canvas').count(),1);
  assert.match(await page.locator('#map-credit').innerText(),/OpenMapTiles/);
  const out=process.env.EVCOCKPIT_SCREENSHOTS||'/tmp/evcockpit-layout';fs.mkdirSync(out,{recursive:true});
  await page.waitForTimeout(1500);
  await page.screenshot({path:path.join(out,'openfreemap-turkiye.png')});
  // Pan/zoom the actual Leaflet map; vector layer must follow it and load detail.
  await page.evaluate(()=>window.mediaengineDestination({lat:41.015,lon:28.979,label:'İstanbul'}));
  await page.waitForTimeout(7000);
  await page.screenshot({path:path.join(out,'openfreemap-istanbul.png')});
  assert.deepEqual(oldRaster,[],'Old OSM raster tile server must never be contacted');
  assert.deepEqual(errors,[],'Runtime JavaScript errors');
  console.log('PASS: live OpenFreeMap vector canvas, Turkey, Istanbul, no legacy raster');
 } finally {await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
